package com.rork.mindsetframestracker.util

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
import android.service.chooser.ChooserAction
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.rork.mindsetframestracker.R
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.data.completedCountOn
import com.rork.mindsetframestracker.data.currentMood
import com.rork.mindsetframestracker.data.dailyCheckInStreak
import com.rork.mindsetframestracker.data.isCheckedToday
import com.rork.mindsetframestracker.data.sortedHabits
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Renders stylized, branded 1080x1350 (4:5, Instagram-friendly) share cards,
 * then opens the native share sheet. Weekly/monthly summaries show per-day
 * bars tinted by that day's logged mood plus a mood-mix strip; the daily
 * goal-complete card celebrates checking off every frame with a closed ring,
 * the completed habit list, and streak/mood chips. All cards use the app's
 * editorial serif voice and the brand "frame" border motif, and a copy is
 * saved to the device gallery.
 */
object ProgressShareImage {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    // Brand palette (from the Mindset Frames logo)
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

    /** How many days in the period carried each logged mood (empty moods skipped). */
    private fun moodCountsFor(data: AppData, days: List<LocalDate>): Map<MoodMode, Int> =
        MoodMode.entries
            .associateWith { mode -> days.count { data.moodHistory[Dates.key(it)] == mode } }
            .filterValues { it > 0 }

    /**
     * Generates the weekly (7-day) summary image and launches the share sheet.
     */
    fun shareWeeklySummary(context: Context, data: AppData) {
        val title = "My Week in Frames"
        val caption = "My week of habits & moods with Mindset Frames. " +
            "#MindsetFrames #HabitTracker #WeeklyWins"
        share(
            context,
            bitmap = render(context, data, dayCount = 7, title = title),
            summaryText = buildSummaryText(data, dayCount = 7, title = title, caption = caption),
            title = title,
            caption = caption,
            fileName = "weekly_summary.png",
        )
    }

    /**
     * Generates the monthly (30-day) summary image and launches the share sheet.
     */
    fun shareMonthlySummary(context: Context, data: AppData) {
        val title = "My Month in Frames"
        val caption = "My month of habits & moods with Mindset Frames. " +
            "#MindsetFrames #HabitTracker #MonthlyWins"
        share(
            context,
            bitmap = render(context, data, dayCount = 30, title = title),
            summaryText = buildSummaryText(data, dayCount = 30, title = title, caption = caption),
            title = title,
            caption = caption,
            fileName = "monthly_summary.png",
        )
    }

    /**
     * Generates the "daily goal complete" celebration card and launches the
     * share sheet — used the moment every frame is checked off for the day.
     */
    fun shareDailyCompletion(context: Context, data: AppData) {
        val caption = "Completed every habit today with Mindset Frames. " +
            "#MindsetFrames #HabitTracker #DailyWins"
        share(
            context,
            bitmap = renderDailyCard(context, data),
            summaryText = buildDailySummaryText(data, caption),
            title = "Daily Goal Complete",
            caption = caption,
            fileName = "daily_goal.png",
        )
    }

    private fun share(
        context: Context,
        bitmap: Bitmap,
        summaryText: String,
        title: String,
        caption: String,
        fileName: String,
    ) {
        val file = writeToCache(context, bitmap, fileName)
        saveToGallery(context, bitmap, fileName)
        bitmap.recycle()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            putExtra(Intent.EXTRA_TITLE, title)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Share your progress")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            chooser.putExtra(
                Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS,
                arrayOf(buildCopyAction(context, summaryText)),
            )
        } else {
            // Older Android share sheets don't support custom actions, so copy
            // the text summary to the clipboard as the share sheet opens.
            copyToClipboard(context, summaryText)
        }
        context.startActivity(chooser)
    }

    /**
     * Builds the plain-text summary of the progress card (including the mood
     * mix), used by the "Copy summary" share-sheet action.
     */
    private fun buildSummaryText(data: AppData, dayCount: Int, title: String, caption: String): String {
        val days: List<LocalDate> = Dates.lastDays(dayCount)
        val habitCount = data.habits.size
        val completed = days.sumOf { day -> data.completedCountOn(Dates.key(day)) }
        val possible = habitCount * dayCount
        val percent = if (possible > 0) ((completed * 100f) / possible).toInt().coerceIn(0, 100) else 0
        val streak = data.dailyCheckInStreak()
        val fmt = DateTimeFormatter.ofPattern("MMM d")
        val range = "${days.first().format(fmt)} \u2013 ${days.last().format(fmt)}"
        val streakText = if (streak == 1) "1 day" else "$streak days"
        val moodCounts = moodCountsFor(data, days)
        val moodLine = if (moodCounts.isEmpty()) {
            null
        } else {
            val parts = MoodMode.entries.mapNotNull { mode ->
                moodCounts[mode]?.let { "${moodLabel(mode)} \u00d7$it" }
            }
            "Mood mix: ${parts.joinToString(", ")}"
        }
        return buildString {
            appendLine("$title ($range)")
            appendLine("$percent% mindset frames completed \u2014 $completed of $possible frames done")
            moodLine?.let { appendLine(it) }
            appendLine("Check-in streak: $streakText")
            append(caption)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun buildCopyAction(context: Context, summaryText: String): ChooserAction {
        val intent = Intent(context, CopySummaryReceiver::class.java).apply {
            action = CopySummaryReceiver.ACTION_COPY_SUMMARY
            putExtra(CopySummaryReceiver.EXTRA_SUMMARY_TEXT, summaryText)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            summaryText.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return ChooserAction.Builder(
            android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_menu_edit),
            "Copy summary",
            pendingIntent,
        ).build()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Mindset Frames summary", text))
        Toast.makeText(context, "Summary copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun render(context: Context, data: AppData, dayCount: Int, title: String): Bitmap {
        val days: List<LocalDate> = Dates.lastDays(dayCount)
        val habitCount = data.habits.size
        val completed = days.sumOf { day -> data.completedCountOn(Dates.key(day)) }
        val possible = habitCount * dayCount
        val percent = if (possible > 0) ((completed * 100f) / possible).toInt().coerceIn(0, 100) else 0
        val streak = data.dailyCheckInStreak()

        val moodByDay: List<MoodMode?> = days.map { data.moodHistory[Dates.key(it)] }
        val moodCounts = moodCountsFor(data, days)
        val loggedTotal = moodCounts.values.sum()
        val dominantMood = moodCounts.maxByOrNull { it.value }?.key
        val heroColor = dominantMood?.let { moodColor(it) } ?: terracotta

        // The app's editorial serif (DM Serif Display) keeps the card on-brand.
        val serif: Typeface = ResourcesCompat.getFont(context, R.font.dm_serif_display)
            ?: Typeface.create(Typeface.SERIF, Typeface.NORMAL)

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Warm cream base with a glow tinted by the period's dominant mood.
        canvas.drawColor(cream)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, HEIGHT * 0.42f,
                ColorUtils.setAlphaComponent(heroColor, 42), Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT * 0.42f, glow)

        // Decorative "frame" border — the brand motif.
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = ColorUtils.setAlphaComponent(deepBrown, 70)
        }
        canvas.drawRoundRect(RectF(30f, 30f, WIDTH - 30f, HEIGHT - 30f), 44f, 44f, framePaint)

        // Logo (left-aligned near top, smaller)
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.brand_logo)
        if (logo != null) {
            val target = 120f
            val scale = target / maxOf(logo.width, logo.height).coerceAtLeast(1)
            val logoW = logo.width * scale
            val logoH = logo.height * scale
            val dst = RectF(
                60f, 76f,
                60f + logoW, 76f + logoH,
            )
            canvas.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            logo.recycle()
        }

        // Title (serif) + date range
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 62f
            color = deepBrown
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(title, WIDTH / 2f, 330f, titlePaint)

        val fmt = DateTimeFormatter.ofPattern("MMM d")
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 34f
            color = softBrown
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "${days.first().format(fmt)} – ${days.last().format(fmt)}",
            WIDTH / 2f, 384f, captionPaint,
        )

        // Hero percentage in the dominant mood's color
        val heroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 182f
            color = heroColor
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("$percent%", WIDTH / 2f, 560f, heroPaint)
        captionPaint.textSize = 38f
        canvas.drawText("of habit frames completed", WIDTH / 2f, 616f, captionPaint)

        // Chart card: per-day bars tinted by that day's mood
        val card = RectF(84f, 660f, WIDTH - 84f, 1000f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardIvory }
        canvas.drawRoundRect(card, 40f, 40f, cardPaint)
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = outlineSand
        }
        canvas.drawRoundRect(card, 40f, 40f, cardStroke)

        val innerLeft = card.left + 36f
        val innerRight = card.right - 36f
        val innerW = innerRight - innerLeft

        val microPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 26f
            color = ColorUtils.setAlphaComponent(softBrown, 210)
            letterSpacing = 0.14f
        }
        canvas.drawText("DAILY FRAMES", innerLeft, 716f, microPaint)

        val barsTop = 744f
        val barsBottom = 892f
        val barAreaH = barsBottom - barsTop
        val n = days.size
        val barW = minOf(64f, innerW / (n * 1.62f))
        val gap = if (n > 1) (innerW - n * barW) / (n - 1) else 0f
        val corner = minOf(14f, barW / 2f)
        val today = LocalDate.now()

        days.forEachIndexed { i, day ->
            val left = innerLeft + i * (barW + gap)
            val right = left + barW
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (day == today) {
                    ColorUtils.setAlphaComponent(heroColor, 38)
                } else {
                    ColorUtils.setAlphaComponent(softBrown, 26)
                }
            }
            canvas.drawRoundRect(RectF(left, barsTop, right, barsBottom), corner, corner, track)

            val done = data.completedCountOn(Dates.key(day))
            val fraction = if (habitCount > 0) (done.toFloat() / habitCount).coerceIn(0f, 1f) else 0f
            if (fraction > 0f) {
                val tint = moodByDay[i]?.let { moodColor(it) } ?: terracotta
                val fillTop = barsBottom - maxOf(barAreaH * fraction, 16f)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, fillTop, 0f, barsBottom,
                        ColorUtils.blendARGB(tint, Color.WHITE, 0.22f), tint,
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRoundRect(RectF(left, fillTop, right, barsBottom), corner, corner, fill)
            }
        }

        // Weekly: day letters + a mood dot under each day. Monthly: edge dates.
        val dayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 30f
            color = softBrown
            textAlign = Paint.Align.CENTER
        }
        if (n <= 7) {
            days.forEachIndexed { i, day ->
                val cx = innerLeft + i * (barW + gap) + barW / 2f
                canvas.drawText(
                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    cx, 934f, dayLabelPaint,
                )
                val mood = moodByDay[i]
                if (mood != null) {
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = moodColor(mood) }
                    canvas.drawCircle(cx, 964f, 11f, dot)
                } else {
                    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 2.5f
                        color = ColorUtils.setAlphaComponent(softBrown, 90)
                    }
                    canvas.drawCircle(cx, 964f, 8f, ring)
                }
            }
        } else {
            dayLabelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(days.first().format(fmt), innerLeft, 944f, dayLabelPaint)
            dayLabelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(days.last().format(fmt), innerRight, 944f, dayLabelPaint)
        }

        // Mood mix strip: proportional segments of the period's logged moods
        canvas.drawText("MOOD MIX", innerLeft, 1040f, microPaint)
        val mixRect = RectF(innerLeft, 1052f, innerRight, 1078f)
        val mixTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(softBrown, 26)
        }
        canvas.drawRoundRect(mixRect, 13f, 13f, mixTrack)
        if (loggedTotal > 0) {
            val clip = Path().apply { addRoundRect(mixRect, 13f, 13f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            var x = mixRect.left
            MoodMode.entries.forEach { mode ->
                val count = moodCounts[mode] ?: return@forEach
                val w = mixRect.width() * count / loggedTotal
                val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = moodColor(mode) }
                canvas.drawRect(x, mixRect.top, x + w, mixRect.bottom, seg)
                x += w
            }
            canvas.restore()
        }

        // Legend under the strip (shrinks to fit if all four moods appear)
        val legendBaseline = 1122f
        if (loggedTotal == 0) {
            captionPaint.textSize = 30f
            canvas.drawText("No moods logged this period yet", WIDTH / 2f, legendBaseline, captionPaint)
        } else {
            val legendText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.SANS_SERIF
                textSize = 30f
                color = softBrown
            }
            val dotR = 9f
            val dotGap = 12f
            val chipGap = 34f

            fun chipWidths(): List<Pair<MoodMode, Float>> = MoodMode.entries.mapNotNull { mode ->
                val count = moodCounts[mode] ?: return@mapNotNull null
                mode to (dotR * 2 + dotGap + legendText.measureText("${moodLabel(mode)} \u00d7$count"))
            }

            var chips = chipWidths()
            var total = chips.sumOf { it.second.toDouble() }.toFloat() + chipGap * (chips.size - 1)
            val maxW = WIDTH - 168f
            if (total > maxW) {
                legendText.textSize = (legendText.textSize * maxW / total).coerceAtLeast(22f)
                chips = chipWidths()
                total = chips.sumOf { it.second.toDouble() }.toFloat() + chipGap * (chips.size - 1)
            }
            var x = (WIDTH - total) / 2f
            chips.forEach { (mode, width) ->
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = moodColor(mode) }
                canvas.drawCircle(x + dotR, legendBaseline - 10f, dotR, dot)
                canvas.drawText(
                    "${moodLabel(mode)} \u00d7${moodCounts.getValue(mode)}",
                    x + dotR * 2 + dotGap, legendBaseline, legendText,
                )
                x += width + chipGap
            }
        }

        // Stats row: frames done + check-in streak
        drawStatChip(
            canvas, RectF(84f, 1146f, WIDTH / 2f - 14f, 1250f),
            "$completed of $possible", "frames done", serif, sage,
        )
        drawStatChip(
            canvas, RectF(WIDTH / 2f + 14f, 1146f, WIDTH - 84f, 1250f),
            if (streak == 1) "1 day" else "$streak days", "check-in streak", serif, terracotta,
        )

        // Footer branding: logo mark + serif wordmark centered bottom middle.
        drawBrandFooter(context, canvas, serif, baseline = 1302f)

        return bitmap
    }

    /**
     * Renders the branded 1080x1350 "daily goal complete" card: today's mood
     * glow, a closed progress ring with a checkmark, the completed frame list,
     * and streak/mood stat chips. Public so the in-app celebration dialog can
     * preview the exact image before sharing.
     */
    fun renderDailyCard(context: Context, data: AppData): Bitmap {
        val mood = data.currentMood()
        val heroColor = moodColor(mood)
        val total = data.habits.size
        val done = data.habits.count { data.isCheckedToday(it.id) }
        val streak = data.dailyCheckInStreak()

        val serif: Typeface = ResourcesCompat.getFont(context, R.font.dm_serif_display)
            ?: Typeface.create(Typeface.SERIF, Typeface.NORMAL)

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Warm cream base with a glow tinted by today's mood.
        canvas.drawColor(cream)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, HEIGHT * 0.46f,
                ColorUtils.setAlphaComponent(heroColor, 46), Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT * 0.46f, glow)

        // Decorative "frame" border — the brand motif.
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = ColorUtils.setAlphaComponent(deepBrown, 70)
        }
        canvas.drawRoundRect(RectF(30f, 30f, WIDTH - 30f, HEIGHT - 30f), 44f, 44f, framePaint)

        // Logo (left-aligned near top, smaller)
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.brand_logo)
        if (logo != null) {
            val target = 120f
            val scale = target / maxOf(logo.width, logo.height).coerceAtLeast(1)
            val logoW = logo.width * scale
            val logoH = logo.height * scale
            val dst = RectF(
                60f, 72f,
                60f + logoW, 72f + logoH,
            )
            canvas.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            logo.recycle()
        }

        // Title (serif) + full date
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 62f
            color = deepBrown
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Daily Goal Complete", WIDTH / 2f, 322f, titlePaint)

        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 34f
            color = softBrown
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
            WIDTH / 2f, 376f, captionPaint,
        )

        // Closed progress ring with a checkmark — echoes the in-app ring.
        val cx = WIDTH / 2f
        val cy = 556f
        val radius = 130f
        val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 26f
            color = ColorUtils.setAlphaComponent(softBrown, 30)
        }
        canvas.drawCircle(cx, cy, radius, ringTrack)
        val innerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(heroColor, 26)
        }
        canvas.drawCircle(cx, cy, radius - 32f, innerGlow)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 26f
            strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(
                cx - radius, cy - radius, cx + radius, cy + radius,
                ColorUtils.blendARGB(heroColor, Color.WHITE, 0.25f), heroColor,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, radius, ringPaint)
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 20f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = heroColor
        }
        val check = Path().apply {
            moveTo(cx - 56f, cy + 4f)
            lineTo(cx - 14f, cy + 48f)
            lineTo(cx + 62f, cy - 42f)
        }
        canvas.drawPath(check, checkPaint)

        // Completion statement under the ring
        val statementPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 50f
            color = heroColor
            textAlign = Paint.Align.CENTER
        }
        val frameWord = if (total == 1) "frame" else "frames"
        val statement = if (total > 0 && done == total) {
            "All $total $frameWord done today"
        } else {
            "$done of $total $frameWord done today"
        }
        canvas.drawText(statement, WIDTH / 2f, 748f, statementPaint)

        // Completed frames list card
        val card = RectF(84f, 792f, WIDTH - 84f, 1122f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardIvory }
        canvas.drawRoundRect(card, 40f, 40f, cardPaint)
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = outlineSand
        }
        canvas.drawRoundRect(card, 40f, 40f, cardStroke)

        val innerLeft = card.left + 36f
        val microPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 26f
            color = ColorUtils.setAlphaComponent(softBrown, 210)
            letterSpacing = 0.14f
        }
        canvas.drawText("TODAY'S FRAMES", innerLeft, 844f, microPaint)

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 34f
            color = deepBrown
        }
        val nameLeft = innerLeft + 46f
        val maxNameWidth = card.right - 36f - nameLeft
        val habits = data.sortedHabits()
        val maxRows = 5
        val shown = if (habits.size <= maxRows) habits else habits.take(maxRows - 1)
        var baseline = 892f
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = heroColor }
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = cardIvory
        }
        shown.forEach { habit ->
            val dotCy = baseline - 12f
            canvas.drawCircle(innerLeft + 13f, dotCy, 13f, dotPaint)
            val tick = Path().apply {
                moveTo(innerLeft + 7f, dotCy + 0.5f)
                lineTo(innerLeft + 11.5f, dotCy + 5f)
                lineTo(innerLeft + 19.5f, dotCy - 4.5f)
            }
            canvas.drawPath(tick, tickPaint)
            canvas.drawText(ellipsize(habit.name, namePaint, maxNameWidth), nameLeft, baseline, namePaint)
            baseline += 54f
        }
        if (habits.size > maxRows) {
            val morePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.SANS_SERIF
                textSize = 32f
                color = softBrown
            }
            canvas.drawText("+${habits.size - shown.size} more frames", nameLeft, baseline, morePaint)
        }

        // Stat chips: check-in streak + today's mood
        drawStatChip(
            canvas, RectF(84f, 1146f, WIDTH / 2f - 14f, 1250f),
            if (streak == 1) "1 day" else "$streak days", "check-in streak", serif, terracotta,
        )
        drawStatChip(
            canvas, RectF(WIDTH / 2f + 14f, 1146f, WIDTH - 84f, 1250f),
            moodLabel(mood), "today's mood", serif, heroColor,
        )

        // Footer branding: logo mark + serif wordmark centered bottom middle.
        drawBrandFooter(context, canvas, serif, baseline = 1302f)

        return bitmap
    }

    /**
     * Centered bottom-middle brand footer: a small, letter-spaced uppercase
     * "MINDSET FRAMES" wordmark — quiet and editorial, no logo mark (the
     * logo already anchors the top-left corner of every card).
     */
    private fun drawBrandFooter(
        context: Context,
        canvas: Canvas,
        serif: Typeface,
        baseline: Float,
    ) {
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 26f
            letterSpacing = 0.22f
            color = softBrown
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("MINDSET FRAMES", WIDTH / 2f, baseline, brandPaint)
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

    /** Plain-text version of the daily card for the "Copy summary" action. */
    private fun buildDailySummaryText(data: AppData, caption: String): String {
        val total = data.habits.size
        val streak = data.dailyCheckInStreak()
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        val streakText = if (streak == 1) "1 day" else "$streak days"
        val frameWord = if (total == 1) "mindset frame" else "mindset frames"
        return buildString {
            appendLine("Daily Goal Complete \u2014 $date")
            appendLine("All $total $frameWord done today \u2014 mood: ${moodLabel(data.currentMood())}")
            appendLine("Check-in streak: $streakText")
            append(caption)
        }
    }

    private fun drawStatChip(
        canvas: Canvas,
        rect: RectF,
        value: String,
        label: String,
        serif: Typeface,
        accent: Int,
    ) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(accent, 30)
        }
        canvas.drawRoundRect(rect, 32f, 32f, bg)
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 54f
            color = ColorUtils.blendARGB(accent, deepBrown, 0.25f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(value, rect.centerX(), rect.centerY() - 4f, valuePaint)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 29f
            color = softBrown
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(label, rect.centerX(), rect.centerY() + 40f, labelPaint)
    }

    /**
     * Saves a copy of the summary image into the device gallery
     * (Pictures/Mindset Frames). On Android 10+ this uses scoped MediaStore
     * inserts (no permission needed); on Android 9 and below it requests
     * WRITE_EXTERNAL_STORAGE the first time.
     */
    private fun saveToGallery(context: Context, bitmap: Bitmap, fileName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                (context as? Activity)?.let {
                    ActivityCompat.requestPermissions(
                        it, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4021,
                    )
                }
                Toast.makeText(
                    context,
                    "Allow storage access, then share again to save a copy to your gallery",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
        }

        try {
            val displayName = "mindset_frames_${System.currentTimeMillis()}_$fileName"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mindset Frames")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("ProgressShareImage", "Failed to save image to gallery", e)
            Toast.makeText(context, "Couldn't save to gallery", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeToCache(context: Context, bitmap: Bitmap, fileName: String): File {
        val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }
}
