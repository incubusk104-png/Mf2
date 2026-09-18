package com.rork.mindsetframestracker.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.ExportRange
import com.rork.mindsetframestracker.data.HabitDataExport
import com.rork.mindsetframestracker.data.HabitExportBundle
import com.rork.mindsetframestracker.data.HabitExportWriters
import com.rork.mindsetframestracker.data.HabitShareCodec
import java.io.File
import java.time.LocalDate

/**
 * The Android side of exporting and sharing: turns the (pure, testable) export
 * engine's output into a real file and a real share sheet.
 *
 * Everything here is deliberately thin. All the logic that decides *what* is
 * exported lives in [HabitDataExport]/[HabitExportWriters]/[HabitShareCodec],
 * which have no Android dependency and are unit-tested. This file only does the
 * three things that genuinely need a `Context`: writing bytes, handing a Uri to
 * another app, and reading a user-picked file back.
 */
object HabitShare {

    private const val TAG = "HabitShare"

    /**
     * Subdirectory of `cacheDir` used for generated exports.
     *
     * Must stay within a path declared in `res/xml/file_paths.xml` or
     * [FileProvider] refuses the Uri. `file_paths.xml` declares
     * `reports/`, which is already used by the PDF report; reusing it means an
     * export needs no manifest or provider change.
     */
    private const val REPORT_DIR = "reports"

    /** The formats a user can choose. */
    enum class ExportFormat(val extension: String, val mimeType: String) {
        JSON("json", "application/json"),
        CSV("csv", "text/csv"),
        REPORT("txt", "text/plain"),
    }

    /**
     * Builds the bundle for the current data and a chosen scope.
     *
     * @param range null exports the entire history, which is the default and the
     *   one that matters: the complaint this work answers is data going missing
     *   from a requested file.
     */
    fun buildBundle(
        data: AppData,
        range: ExportRange? = null,
        appVersionName: String = "",
        appVersionCode: Int = 0,
    ): HabitExportBundle = HabitDataExport.build(
        data = data,
        range = range,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
    )

    /** Renders [bundle] in [format]. */
    fun render(bundle: HabitExportBundle, format: ExportFormat): String = when (format) {
        ExportFormat.JSON -> HabitExportWriters.json(bundle)
        ExportFormat.CSV -> HabitExportWriters.csv(bundle)
        ExportFormat.REPORT -> HabitExportWriters.report(bundle)
    }

    /** A stable, filesystem-safe file name; the date keeps repeat exports apart. */
    fun fileName(format: ExportFormat, label: String = LocalDate.now().toString()): String =
        "mindset-frames-export-$label.${format.extension}"

    /**
     * Writes [content] into the shared cache directory and returns the file.
     *
     * A UTF-8 BOM is prepended for CSV only: Excel on Windows otherwise reads a
     * UTF-8 CSV as the local ANSI codepage, which turns every emoji and accented
     * character in a habit name into mojibake. JSON must not carry one (a BOM is
     * not legal at the start of a JSON document), and the plain-text report is
     * read by editors that cope either way.
     */
    fun writeToCache(context: Context, content: String, fileName: String, format: ExportFormat): File {
        val dir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        val bytes = if (format == ExportFormat.CSV) {
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray(Charsets.UTF_8)
        } else {
            content.toByteArray(Charsets.UTF_8)
        }
        file.writeBytes(bytes)
        return file
    }

    /** The content Uri for a cache file, via the app's FileProvider. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Shares a rendered export through the system share sheet.
     *
     * Returns the file that was written, so a caller can report its size, or null
     * if the export could not be produced. Failures are surfaced with the real
     * reason rather than swallowed: an export that silently does nothing is the
     * exact failure this feature exists to end.
     */
    fun shareExport(
        context: Context,
        data: AppData,
        format: ExportFormat,
        range: ExportRange? = null,
        appVersionName: String = "",
        appVersionCode: Int = 0,
        onError: (String) -> Unit = {},
    ): File? = try {
        val bundle = buildBundle(data, range, appVersionName, appVersionCode)
        val content = render(bundle, format)
        val name = fileName(format)
        val file = writeToCache(context, content, name, format)

        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "Mindset Frames export — ${bundle.summary.habitCount} habits, " +
                    "${bundle.verification.totalRecords} records",
            )
            putExtra(Intent.EXTRA_TEXT, buildShareBlurb(bundle))
            // clipData as well as EXTRA_STREAM: some targets honour only the
            // ClipData grant, and without it the receiver opens the Uri and is
            // refused. The PDF report path carries both for the same reason.
            clipData = ClipData.newRawUri("Mindset Frames export", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Mindset Frames export").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        file
    } catch (e: Exception) {
        Log.e(TAG, "Failed to share export", e)
        onError(e.message ?: e.javaClass.simpleName)
        null
    }

    /**
     * Shares habits as a **code** — the path for "send my habits to a friend"
     * where a file would be awkward.
     *
     * The code is plain text, so it travels through any chat app.
     */
    fun shareHabitCode(
        context: Context,
        data: AppData,
        includeHistory: Boolean = true,
        appVersionName: String = "",
        appVersionCode: Int = 0,
        onError: (String) -> Unit = {},
    ): String? = try {
        val bundle = buildBundle(data, null, appVersionName, appVersionCode)
        val code = HabitShareCodec.encode(bundle, includeHistory = includeHistory)
        val habitCount = bundle.habits.size
        val message = buildString {
            appendLine("My Mindset Frames habits ($habitCount habit" + (if (habitCount == 1) ")" else "s)"))
            if (includeHistory) appendLine("Shared with history: check-ins, logs and alarm records included.")
            appendLine()
            appendLine("Open Settings → Data & sharing → Import habits, and paste this code:")
            appendLine()
            appendLine(code)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra(Intent.EXTRA_SUBJECT, "My Mindset Frames habits")
        }
        val chooser = Intent.createChooser(intent, "Share your habits").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        code
    } catch (e: Exception) {
        Log.e(TAG, "Failed to share habit code", e)
        onError(e.message ?: e.javaClass.simpleName)
        null
    }

    /**
     * The text that accompanies a shared file, stating what is inside and
     * whether it is complete - so the recipient can tell the export apart from a
     * generic attachment, and knows it is whole.
     */
    private fun buildShareBlurb(bundle: HabitExportBundle): String = buildString {
        val s = bundle.summary
        appendLine("Mindset Frames — complete habit export")
        appendLine("${s.habitCount} habits · ${s.totalCheckIns} check-ins · " +
            "${s.totalLogEntries} log entries · ${s.totalAlarmEvents} alarm records")
        appendLine("Range: ${bundle.rangeStartDay ?: "—"} to ${bundle.rangeEndDay ?: "—"}")
        appendLine(
            if (bundle.verification.isComplete) {
                "Verified complete: all ${bundle.verification.totalRecords} records exported."
            } else {
                "Note: ${bundle.verification.omissions.size} record(s) could not be included."
            },
        )
    }
}
