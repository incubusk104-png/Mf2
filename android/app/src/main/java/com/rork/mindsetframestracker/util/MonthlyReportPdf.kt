package com.rork.mindsetframestracker.util

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.rork.mindsetframestracker.R
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.data.completedCountOn
import com.rork.mindsetframestracker.data.isCheckedOn
import com.rork.mindsetframestracker.data.sortedHabits
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Generates a professional, print-ready summary report as an A4 PDF and
 * opens the native share sheet. A report covers either a full calendar month
 * or a custom date range (up to about a year) — every label, chart axis, and
 * file name adapts to the chosen period. Everything except the small brand
 * logo is drawn as vector text and shapes, so the report stays razor-sharp
 * at any zoom or print resolution.
 *
 * Page 1 is the overview: branded header, one-line period summary, key stat
 * chips, a per-day completion chart tinted by each day's logged mood, the
 * mood mix strip, and the start of the habit breakdown table. Extra habits
 * flow onto continuation pages automatically. A copy is saved to Downloads
 * on Android 10+.
 */
object MonthlyReportPdf {

    private const val TAG = "MonthlyReportPdf"

    /** Longest custom range (in days) a report supports — about one year. */
    const val MAX_RANGE_DAYS = 366L

    // A4 portrait in PostScript points; drawing happens in a 1080-wide design
    // space (matching the share-card code style) scaled down onto the page.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val DESIGN_W = 1080f
    private const val DESIGN_H = 1528f
    private const val SCALE = PAGE_W / DESIGN_W

    private const val MARGIN = 76f
    private const val CONTENT_RIGHT = DESIGN_W - MARGIN
    private const val CONTENT_BOTTOM = 1400f
    private const val ROW_H = 56f
    private const val MAX_ROWS_FIRST_PAGE = 5
    private const val ROWS_PER_EXTRA_PAGE = 20

    // Brand palette (from the Mindset Frames logo) — print-friendly on white.
    private val pageWhite = Color.parseColor("#FFFFFF")
    private val cream = Color.parseColor("#FAF3E9")
    private val cardIvory = Color.parseColor("#FFFDF7")
    private val terracotta = Color.parseColor("#C7724F")
    private val deepBrown = Color.parseColor("#5C3A28")
    private val softBrown = Color.parseColor("#8A6650")
    private val sage = Color.parseColor("#9CAF88")
    private val outlineSand = Color.parseColor("#DCD3C2")

    /** Print color per mood — matches the in-app "classic" accent pack. */
    private fun moodColor(mode: MoodMode): Int = when (mode) {
        MoodMode.CALM -> Color.parseColor("#5D8A66")
        MoodMode.FOCUSED -> Color.parseColor("#33655A")
        MoodMode.MOTIVATED -> Color.parseColor("#B65C36")
        MoodMode.OVERWHELMED -> Color.parseColor("#6E6A5D")
    }

    private fun moodLabel(mode: MoodMode): String = when (mode) {
        MoodMode.CALM -> "Calm"
        MoodMode.FOCUSED -> "Focused"
        MoodMode.MOTIVATED -> "Motivated"
        MoodMode.OVERWHELMED -> "Overwhelmed"
    }

    /** One habit's line in the breakdown table. */
    private class HabitRow(
        val name: String,
        val done: Int,
        val tracked: Int,
        val percent: Int,
        val bestRun: Int,
    )

    /**
     * Everything the renderer needs, computed once up front. Labels are
     * pre-baked so the drawing code never cares whether the period is a
     * calendar month or a custom date range.
     */
    private class ReportStats(
        val elapsed: Int,
        val todayIndex: Int?,
        val dayLabels: List<Pair<Int, String>>,
        val microLabel: String,
        val title: String,
        val rangeLine: String,
        val continuationTitle: String,
        val continuationRight: String,
        val summaryLead: String,
        val periodNoun: String,
        val cacheName: String,
        val downloadName: String,
        val shareLabel: String,
        val habitCount: Int,
        val completedTotal: Int,
        val possibleTotal: Int,
        val percent: Int,
        val activeDays: Int,
        val bestRun: Int,
        val completedByDay: List<Int>,
        val moodByDay: List<MoodMode?>,
        val moodCounts: Map<MoodMode, Int>,
        val dominantMood: MoodMode?,
        val habitRows: List<HabitRow>,
    )

    /**
     * Months offered in the export picker: every month that has any check-in
     * or mood data, plus the current month, newest first (max 12).
     */
    fun exportableMonths(data: AppData): List<YearMonth> {
        val current = YearMonth.now()
        val fromData = buildSet {
            data.checkIns.values.forEach { keys -> keys.forEach { parseMonth(it)?.let(::add) } }
            data.moodHistory.keys.forEach { parseMonth(it)?.let(::add) }
        }
        return (fromData + current).filter { it <= current }.sortedDescending().take(12)
    }

    private fun parseMonth(dayKey: String): YearMonth? =
        runCatching { YearMonth.from(LocalDate.parse(dayKey)) }.getOrNull()

    /**
     * Builds the report for [month], writes it as a PDF, saves a copy to
     * Downloads (Android 10+), and opens the share sheet.
     */
    fun shareMonthlyReport(context: Context, data: AppData, month: YearMonth) {
        shareReport(context) { buildMonthStats(data, month) }
    }

    /**
     * Builds the report for a custom inclusive [start]..[end] range. Reversed
     * inputs are swapped, future days are clamped to today, and spans longer
     * than [MAX_RANGE_DAYS] are trimmed from the start.
     */
    fun shareRangeReport(context: Context, data: AppData, start: LocalDate, end: LocalDate) {
        shareReport(context) { buildRangeStats(data, start, end) }
    }

    private fun shareReport(context: Context, build: () -> ReportStats) {
        try {
            val stats = build()
            val file = writeReport(context, stats)
            saveToDownloads(context, file, stats)
            shareFile(context, file, stats)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export report", e)
            Toast.makeText(context, "Couldn't create the report — please try again", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildMonthStats(data: AppData, month: YearMonth): ReportStats {
        val today = LocalDate.now()
        val lastDay = month.lengthOfMonth()
        val isCurrent = YearMonth.from(today) == month
        val elapsed = if (isCurrent) today.dayOfMonth.coerceAtMost(lastDay) else lastDay
        val days = (1..lastDay).map { month.atDay(it) }
        val fmt = DateTimeFormatter.ofPattern("MMM d")
        val monthTitle = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        return computeStats(
            data = data,
            days = days,
            elapsed = elapsed,
            todayIndex = if (isCurrent) today.dayOfMonth - 1 else null,
            dayLabels = listOf(1, 7, 14, 21, 28).filter { it <= lastDay }.map { it - 1 to it.toString() },
            microLabel = "MONTHLY MINDSET REPORT",
            title = monthTitle,
            rangeLine = "${days.first().format(fmt)} \u2013 ${days[elapsed - 1].format(fmt)}",
            continuationTitle = "Mindset Frames \u2014 Monthly Report",
            continuationRight = "$monthTitle \u00b7 continued",
            summaryLead = month.format(DateTimeFormatter.ofPattern("MMMM")),
            periodNoun = "month",
            cacheName = "mindset_frames_report_$month.pdf",
            downloadName = "Mindset Frames Report - $monthTitle.pdf",
            shareLabel = monthTitle,
        )
    }

    private fun buildRangeStats(data: AppData, rawStart: LocalDate, rawEnd: LocalDate): ReportStats {
        val today = LocalDate.now()
        val end = maxOf(rawStart, rawEnd).coerceAtMost(today)
        var start = minOf(rawStart, rawEnd).coerceAtMost(end)
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            start = end.minusDays(MAX_RANGE_DAYS - 1)
        }
        val days = generateSequence(start) { d -> d.plusDays(1).takeIf { !it.isAfter(end) } }.toList()
        val n = days.size

        val fmtShort = DateTimeFormatter.ofPattern("MMM d")
        val fmtFull = DateTimeFormatter.ofPattern("MMM d, yyyy")
        val sameYear = start.year == end.year
        val singleDay = start == end
        val title = when {
            singleDay -> start.format(fmtShort)
            sameYear -> "${start.format(fmtShort)} \u2013 ${end.format(fmtShort)}"
            else -> "${start.format(fmtFull)} \u2013 ${end.format(fmtFull)}"
        }
        val dayWord = if (n == 1) "1 day" else "$n days"

        // Up to five evenly spaced date labels along the chart axis.
        val labelCount = minOf(5, n)
        val labelIndices = if (n == 1) listOf(0)
        else (0 until labelCount).map { it * (n - 1) / (labelCount - 1) }.distinct()

        return computeStats(
            data = data,
            days = days,
            elapsed = n,
            todayIndex = days.indexOf(today).takeIf { it >= 0 },
            dayLabels = labelIndices.map { it to days[it].format(fmtShort) },
            microLabel = "MINDSET REPORT \u00b7 CUSTOM RANGE",
            title = title,
            rangeLine = if (sameYear) "$dayWord \u00b7 ${end.year}" else dayWord,
            continuationTitle = "Mindset Frames \u2014 Progress Report",
            continuationRight = "$title \u00b7 continued",
            summaryLead = title,
            periodNoun = "period",
            cacheName = "mindset_frames_report_${start}_$end.pdf",
            downloadName = if (singleDay) {
                "Mindset Frames Report - ${start.format(fmtFull)}.pdf"
            } else {
                "Mindset Frames Report - ${start.format(fmtFull)} to ${end.format(fmtFull)}.pdf"
            },
            shareLabel = when {
                singleDay -> start.format(fmtFull)
                sameYear -> "${start.format(fmtShort)} \u2013 ${end.format(fmtFull)}"
                else -> title
            },
        )
    }

    /** Shared stats computation over an explicit day list plus pre-baked labels. */
    private fun computeStats(
        data: AppData,
        days: List<LocalDate>,
        elapsed: Int,
        todayIndex: Int?,
        dayLabels: List<Pair<Int, String>>,
        microLabel: String,
        title: String,
        rangeLine: String,
        continuationTitle: String,
        continuationRight: String,
        summaryLead: String,
        periodNoun: String,
        cacheName: String,
        downloadName: String,
        shareLabel: String,
    ): ReportStats {
        val dayKeys = days.map { Dates.key(it) }
        val habitCount = data.habits.size

        val completedByDay = dayKeys.map { data.completedCountOn(it) }
        val elapsedCompleted = completedByDay.take(elapsed)
        val activeDays = elapsedCompleted.count { it > 0 }
        var bestRun = 0
        var run = 0
        elapsedCompleted.forEach { c ->
            if (c > 0) {
                run++
                if (run > bestRun) bestRun = run
            } else {
                run = 0
            }
        }

        val zone = ZoneId.systemDefault()
        val habitRows = data.sortedHabits().map { habit ->
            // Habits created mid-period are only judged on the days they existed.
            val createdDate = if (habit.createdAt > 0L) {
                Instant.ofEpochMilli(habit.createdAt).atZone(zone).toLocalDate()
            } else {
                null
            }
            val trackedDays = days.take(elapsed).filter { day ->
                createdDate == null || !day.isBefore(createdDate)
            }
            val checked = trackedDays.map { data.isCheckedOn(habit.id, Dates.key(it)) }
            val done = checked.count { it }
            var habitBest = 0
            var habitRun = 0
            checked.forEach { c ->
                if (c) {
                    habitRun++
                    if (habitRun > habitBest) habitBest = habitRun
                } else {
                    habitRun = 0
                }
            }
            HabitRow(
                name = habit.name,
                done = done,
                tracked = trackedDays.size,
                percent = if (trackedDays.isNotEmpty()) done * 100 / trackedDays.size else 0,
                bestRun = habitBest,
            )
        }

        val completedTotal = elapsedCompleted.sum()
        val possibleTotal = habitRows.sumOf { it.tracked }
        val percent = if (possibleTotal > 0) {
            ((completedTotal * 100f) / possibleTotal).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val moodByDay = dayKeys.map { data.moodHistory[it] }
        val moodCounts = MoodMode.entries
            .associateWith { mode -> moodByDay.count { it == mode } }
            .filterValues { it > 0 }

        return ReportStats(
            elapsed = elapsed,
            todayIndex = todayIndex,
            dayLabels = dayLabels,
            microLabel = microLabel,
            title = title,
            rangeLine = rangeLine,
            continuationTitle = continuationTitle,
            continuationRight = continuationRight,
            summaryLead = summaryLead,
            periodNoun = periodNoun,
            cacheName = cacheName,
            downloadName = downloadName,
            shareLabel = shareLabel,
            habitCount = habitCount,
            completedTotal = completedTotal,
            possibleTotal = possibleTotal,
            percent = percent,
            activeDays = activeDays,
            bestRun = bestRun,
            completedByDay = completedByDay,
            moodByDay = moodByDay,
            moodCounts = moodCounts,
            dominantMood = moodCounts.maxByOrNull { it.value }?.key,
            habitRows = habitRows,
        )
    }

    // ---------------------------------------------------------------- render

    private fun writeReport(context: Context, stats: ReportStats): File {
        val serif: Typeface = ResourcesCompat.getFont(context, R.font.dm_serif_display)
            ?: Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val doc = PdfDocument()
        val extraRows = (stats.habitRows.size - MAX_ROWS_FIRST_PAGE).coerceAtLeast(0)
        val totalPages = 1 + if (extraRows > 0) {
            (extraRows + ROWS_PER_EXTRA_PAGE - 1) / ROWS_PER_EXTRA_PAGE
        } else {
            0
        }

        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        var canvas = page.canvas.apply { scale(SCALE, SCALE) }
        canvas.drawColor(pageWhite)

        drawHeaderBand(context, canvas, stats, serif)
        drawSummaryLine(canvas, stats, serif)
        drawStatChips(canvas, stats, serif)
        drawDailyChart(canvas, stats)
        drawMoodMix(canvas, stats)

        drawSectionLabel(canvas, "HABIT BREAKDOWN", 1044f)
        drawTableHeader(canvas, 1078f)
        var rowIndex = 0
        var y = 1092f
        if (stats.habitRows.isEmpty()) {
            val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.SANS_SERIF
                textSize = 28f
                color = softBrown
            }
            canvas.drawText("No habits tracked this ${stats.periodNoun}.", MARGIN + 16f, y + 40f, empty)
        }
        while (rowIndex < stats.habitRows.size && rowIndex < MAX_ROWS_FIRST_PAGE) {
            drawHabitRow(canvas, y, rowIndex, stats.habitRows[rowIndex])
            y += ROW_H
            rowIndex++
        }
        drawFooter(canvas, pageNumber, totalPages)
        doc.finishPage(page)

        // Continuation pages for the rest of the habit table.
        while (rowIndex < stats.habitRows.size) {
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            canvas = page.canvas.apply { scale(SCALE, SCALE) }
            canvas.drawColor(pageWhite)
            drawContinuationHeader(context, canvas, stats, serif)
            drawTableHeader(canvas, 220f)
            y = 234f
            var onThisPage = 0
            while (rowIndex < stats.habitRows.size && onThisPage < ROWS_PER_EXTRA_PAGE) {
                drawHabitRow(canvas, y, rowIndex, stats.habitRows[rowIndex])
                y += ROW_H
                rowIndex++
                onThisPage++
            }
            drawFooter(canvas, pageNumber, totalPages)
            doc.finishPage(page)
        }

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, stats.cacheName)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Cream masthead: logo, wordmark, report label, period title, and range. */
    private fun drawHeaderBand(context: Context, canvas: Canvas, stats: ReportStats, serif: Typeface) {
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cream }
        canvas.drawRect(0f, 0f, DESIGN_W, 220f, band)
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = terracotta
            strokeWidth = 3f
        }
        canvas.drawLine(0f, 220f, DESIGN_W, 220f, rule)

        drawLogo(context, canvas, MARGIN, 52f, 96f)

        val wordmark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 46f
            color = deepBrown
        }
        canvas.drawText("Mindset Frames", 200f, 100f, wordmark)
        val micro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 24f
            color = ColorUtils.setAlphaComponent(softBrown, 220)
            letterSpacing = 0.14f
        }
        canvas.drawText(stats.microLabel, 200f, 140f, micro)

        // Long custom-range titles shrink to fit the space right of the wordmark.
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 58f
            color = deepBrown
            textAlign = Paint.Align.RIGHT
        }
        drawFittedText(canvas, stats.title, CONTENT_RIGHT, 104f, titlePaint, 430f)
        val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 27f
            color = softBrown
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(stats.rangeLine, CONTENT_RIGHT, 144f, rangePaint)
    }

    /** One editorial serif sentence summarizing the period. */
    private fun drawSummaryLine(canvas: Canvas, stats: ReportStats, serif: Typeface) {
        val dayWord = if (stats.activeDays == 1) "day" else "days"
        val moodPart = stats.dominantMood?.let { ", mostly ${moodLabel(it)}" } ?: ""
        val line = when {
            stats.habitCount == 0 ->
                "No habit frames were tracked this ${stats.periodNoun} — add your first habit and this report fills itself in."
            stats.activeDays == 0 ->
                "${stats.summaryLead} in review: no check-ins logged this ${stats.periodNoun} yet — your frames are waiting."
            else ->
                "${stats.summaryLead} in review: ${stats.percent}% of habit frames completed across " +
                    "${stats.activeDays} active $dayWord$moodPart."
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 31f
            color = deepBrown
        }
        canvas.drawText(ellipsize(line, paint, CONTENT_RIGHT - MARGIN), MARGIN, 282f, paint)
    }

    /** Four key stat chips: completion, frames done, active days, best run. */
    private fun drawStatChips(canvas: Canvas, stats: ReportStats, serif: Typeface) {
        drawSectionLabel(canvas, "AT A GLANCE", 348f)
        val gap = 16f
        val chipW = (CONTENT_RIGHT - MARGIN - 3 * gap) / 4f
        val top = 366f
        val bottom = 484f
        val runWord = if (stats.bestRun == 1) "1 day" else "${stats.bestRun} days"
        val chips = listOf(
            Triple("${stats.percent}%", "completion", terracotta),
            Triple("${stats.completedTotal} of ${stats.possibleTotal}", "frames done", sage),
            Triple("${stats.activeDays} of ${stats.elapsed}", "active days", moodColor(MoodMode.CALM)),
            Triple(runWord, "best run", softBrown),
        )
        chips.forEachIndexed { i, (value, label, accent) ->
            val left = MARGIN + i * (chipW + gap)
            val rect = RectF(left, top, left + chipW, bottom)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(accent, 26)
            }
            canvas.drawRoundRect(rect, 22f, 22f, bg)
            val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = serif
                textSize = 42f
                color = ColorUtils.blendARGB(accent, deepBrown, 0.3f)
                textAlign = Paint.Align.CENTER
            }
            drawFittedText(canvas, value, rect.centerX(), top + 58f, valuePaint, chipW - 28f)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.SANS_SERIF
                textSize = 25f
                color = softBrown
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(label, rect.centerX(), top + 96f, labelPaint)
        }
    }

    /** Per-day completion bars, tinted by that day's mood, on an ivory card. */
    private fun drawDailyChart(canvas: Canvas, stats: ReportStats) {
        drawSectionLabel(canvas, "DAILY COMPLETION", 550f)

        // Perfect-day legend, right-aligned on the section label line.
        val legendText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 22f
            color = softBrown
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("dot marks a perfect day", CONTENT_RIGHT, 550f, legendText)
        val legendDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = terracotta }
        canvas.drawCircle(
            CONTENT_RIGHT - legendText.measureText("dot marks a perfect day") - 16f,
            542f, 5f, legendDot,
        )

        val card = RectF(MARGIN, 568f, CONTENT_RIGHT, 820f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardIvory }
        canvas.drawRoundRect(card, 24f, 24f, cardPaint)
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = outlineSand
        }
        canvas.drawRoundRect(card, 24f, 24f, cardStroke)

        val innerLeft = card.left + 32f
        val innerRight = card.right - 32f
        val innerW = innerRight - innerLeft
        val barsTop = 604f
        val barsBottom = 744f
        val n = stats.completedByDay.size
        val slot = innerW / n
        val barW = (slot * 0.6f).coerceAtMost(40f)
        val corner = (barW / 2f).coerceAtMost(8f)

        stats.completedByDay.forEachIndexed { i, done ->
            val cx = innerLeft + i * slot + slot / 2f
            val left = cx - barW / 2f
            val right = cx + barW / 2f
            val isElapsed = i < stats.elapsed
            val isToday = stats.todayIndex == i
            val tint = stats.moodByDay[i]?.let { moodColor(it) } ?: terracotta

            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when {
                    isToday -> ColorUtils.setAlphaComponent(tint, 46)
                    isElapsed -> ColorUtils.setAlphaComponent(softBrown, 24)
                    else -> ColorUtils.setAlphaComponent(softBrown, 10)
                }
            }
            canvas.drawRoundRect(RectF(left, barsTop, right, barsBottom), corner, corner, track)

            val fraction = if (stats.habitCount > 0) {
                (done.toFloat() / stats.habitCount).coerceIn(0f, 1f)
            } else {
                0f
            }
            if (isElapsed && fraction > 0f) {
                val fillTop = barsBottom - ((barsBottom - barsTop) * fraction).coerceAtLeast(10f)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, fillTop, 0f, barsBottom,
                        ColorUtils.blendARGB(tint, Color.WHITE, 0.22f), tint,
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRoundRect(RectF(left, fillTop, right, barsBottom), corner, corner, fill)
                if (fraction >= 1f) {
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ColorUtils.blendARGB(tint, deepBrown, 0.25f)
                    }
                    canvas.drawCircle(cx, fillTop - 12f, 4.5f, dot)
                }
            }
        }

        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = outlineSand
            strokeWidth = 2f
        }
        canvas.drawLine(innerLeft, barsBottom, innerRight, barsBottom, axis)

        val dayLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 22f
            color = softBrown
            textAlign = Paint.Align.CENTER
        }
        stats.dayLabels.forEach { (index, label) ->
            val cx = innerLeft + index * slot + slot / 2f
            canvas.drawText(label, cx, 780f, dayLabel)
        }
    }

    /** Proportional mood strip plus a dot legend with per-mood day counts. */
    private fun drawMoodMix(canvas: Canvas, stats: ReportStats) {
        drawSectionLabel(canvas, "MOOD MIX", 886f)
        val strip = RectF(MARGIN, 904f, CONTENT_RIGHT, 932f)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(softBrown, 24)
        }
        canvas.drawRoundRect(strip, 14f, 14f, track)

        val total = stats.moodCounts.values.sum()
        val legendBaseline = 978f
        if (total == 0) {
            val none = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.SANS_SERIF
                textSize = 27f
                color = softBrown
            }
            canvas.drawText("No moods logged this ${stats.periodNoun}.", MARGIN, legendBaseline, none)
            return
        }

        val clip = Path().apply { addRoundRect(strip, 14f, 14f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        var x = strip.left
        MoodMode.entries.forEach { mode ->
            val count = stats.moodCounts[mode] ?: return@forEach
            val w = strip.width() * count / total
            val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = moodColor(mode) }
            canvas.drawRect(x, strip.top, x + w, strip.bottom, seg)
            x += w
        }
        canvas.restore()

        val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 27f
            color = softBrown
        }
        var lx = MARGIN
        MoodMode.entries.forEach { mode ->
            val count = stats.moodCounts[mode] ?: return@forEach
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = moodColor(mode) }
            canvas.drawCircle(lx + 8f, legendBaseline - 9f, 8f, dot)
            val text = "${moodLabel(mode)} \u00d7$count"
            canvas.drawText(text, lx + 26f, legendBaseline, legend)
            lx += 26f + legend.measureText(text) + 34f
        }
    }

    /** Compact masthead for continuation pages of the habit table. */
    private fun drawContinuationHeader(context: Context, canvas: Canvas, stats: ReportStats, serif: Typeface) {
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cream }
        canvas.drawRect(0f, 0f, DESIGN_W, 140f, band)
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = terracotta
            strokeWidth = 3f
        }
        canvas.drawLine(0f, 140f, DESIGN_W, 140f, rule)

        drawLogo(context, canvas, MARGIN, 36f, 64f)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 38f
            color = deepBrown
        }
        canvas.drawText(stats.continuationTitle, 168f, 84f, title)
        val right = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 27f
            color = softBrown
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(stats.continuationRight, CONTENT_RIGHT, 84f, right)
        drawSectionLabel(canvas, "HABIT BREAKDOWN (CONTINUED)", 190f)
    }

    private fun drawTableHeader(canvas: Canvas, baseline: Float) {
        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 22f
            color = ColorUtils.setAlphaComponent(softBrown, 220)
            letterSpacing = 0.12f
        }
        canvas.drawText("HABIT", 92f, baseline, header)
        canvas.drawText("PROGRESS", 432f, baseline, header)
        header.textAlign = Paint.Align.CENTER
        canvas.drawText("DONE", 726f, baseline, header)
        canvas.drawText("RATE", 830f, baseline, header)
        canvas.drawText("BEST RUN", 948f, baseline, header)
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = outlineSand
            strokeWidth = 2.5f
        }
        canvas.drawLine(MARGIN, baseline + 14f, CONTENT_RIGHT, baseline + 14f, rule)
    }

    private fun drawHabitRow(canvas: Canvas, top: Float, index: Int, row: HabitRow) {
        if (index % 2 == 1) {
            val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(cream, 160)
            }
            canvas.drawRect(MARGIN, top, CONTENT_RIGHT, top + ROW_H, shade)
        }
        val baseline = top + 36f

        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 30f
            color = deepBrown
        }
        canvas.drawText(ellipsize(row.name, name, 300f), 92f, baseline, name)

        // Progress bar
        val bar = RectF(432f, top + 21f, 660f, top + 35f)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(softBrown, 26)
        }
        canvas.drawRoundRect(bar, 7f, 7f, track)
        if (row.done > 0 && row.tracked > 0) {
            val w = (bar.width() * row.done / row.tracked).coerceAtLeast(10f)
            val fillRect = RectF(bar.left, bar.top, bar.left + w, bar.bottom)
            val accent = if (row.percent >= 100) sage else terracotta
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    bar.left, 0f, bar.left + w, 0f,
                    ColorUtils.blendARGB(accent, Color.WHITE, 0.2f), accent,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(fillRect, 7f, 7f, fill)
        }

        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 27f
            color = softBrown
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("${row.done}/${row.tracked}", 726f, baseline, value)
        val rate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 27f
            color = deepBrown
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("${row.percent}%", 830f, baseline, rate)
        val best = when {
            row.bestRun <= 0 -> "\u2014"
            row.bestRun == 1 -> "1 day"
            else -> "${row.bestRun} days"
        }
        canvas.drawText(best, 948f, baseline, value)

        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(outlineSand, 150)
            strokeWidth = 1.5f
        }
        canvas.drawLine(MARGIN, top + ROW_H, CONTENT_RIGHT, top + ROW_H, divider)
    }

    private fun drawFooter(canvas: Canvas, pageNumber: Int, totalPages: Int) {
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = outlineSand
            strokeWidth = 2f
        }
        canvas.drawLine(MARGIN, 1432f, CONTENT_RIGHT, 1432f, rule)
        val left = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 26f
            color = softBrown
        }
        canvas.drawText("Mindset Frames \u00b7 Habit & Mood Report", MARGIN, 1468f, left)
        val right = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 26f
            color = softBrown
            textAlign = Paint.Align.RIGHT
        }
        val generated = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        canvas.drawText(
            "Generated $generated \u00b7 Page $pageNumber of $totalPages",
            CONTENT_RIGHT, 1468f, right,
        )
    }

    // --------------------------------------------------------------- helpers

    private fun drawSectionLabel(canvas: Canvas, text: String, baseline: Float) {
        val micro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 26f
            color = ColorUtils.setAlphaComponent(softBrown, 210)
            letterSpacing = 0.14f
        }
        canvas.drawText(text, MARGIN, baseline, micro)
    }

    /** Draws the brand logo scaled by its largest dimension into [target] px. */
    private fun drawLogo(context: Context, canvas: Canvas, left: Float, top: Float, target: Float) {
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.brand_logo) ?: return
        val scale = target / maxOf(logo.width, logo.height).coerceAtLeast(1)
        val dst = RectF(left, top, left + logo.width * scale, top + logo.height * scale)
        canvas.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        logo.recycle()
    }

    /** Shrinks the text size just enough to fit [maxWidth], then draws it. */
    private fun drawFittedText(canvas: Canvas, text: String, cx: Float, baseline: Float, paint: Paint, maxWidth: Float) {
        val w = paint.measureText(text)
        if (w > maxWidth) paint.textSize = paint.textSize * maxWidth / w
        canvas.drawText(text, cx, baseline, paint)
    }

    /** Truncates [text] with an ellipsis so it fits within [maxWidth]. */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var trimmed = text
        while (trimmed.isNotEmpty() && paint.measureText("$trimmed\u2026") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed\u2026"
    }

    // ---------------------------------------------------------- save & share

    /**
     * Saves a copy into the public Downloads folder (Downloads/Mindset Frames)
     * via scoped MediaStore — Android 10+ only, no permission needed. Older
     * devices still get the file through the share sheet.
     */
    private fun saveToDownloads(context: Context, file: File, stats: ReportStats) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, stats.downloadName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Mindset Frames")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Toast.makeText(context, "Report saved to Downloads", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save report to Downloads", e)
        }
    }

    private fun shareFile(context: Context, file: File, stats: ReportStats) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Mindset Frames report \u2014 ${stats.shareLabel}")
            putExtra(Intent.EXTRA_TITLE, "Mindset report \u2014 ${stats.shareLabel}")
            clipData = ClipData.newRawUri("Mindset report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share report")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
