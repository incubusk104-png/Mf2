package com.rork.mindsetframestracker.ui.avatar

import androidx.compose.ui.graphics.Color
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.BadgeTier
import com.rork.mindsetframestracker.data.CompanionTask
import com.rork.mindsetframestracker.data.CompanionTaskType
import com.rork.mindsetframestracker.data.isMet

/**
 * Circular background frame styles. Each is drawn procedurally in
 * [drawFrameBackground] — no bundled image assets, so the catalog stays
 * lightweight and every frame renders crisply at any size.
 */
sealed interface FrameStyle {
    data class Solid(val color: Color) : FrameStyle
    data class Gradient(val colors: List<Color>) : FrameStyle
    data class Dots(val bg: Color, val dot: Color) : FrameStyle
    data class Stripes(val bg: Color, val stripe: Color) : FrameStyle
    data class Rays(val bg: Color, val ray: Color) : FrameStyle
    data class NightSky(val top: Color, val bottom: Color, val star: Color) : FrameStyle
    data class Hills(val sky: Color, val far: Color, val near: Color) : FrameStyle
    data class Waves(val sky: Color, val sea: Color, val foam: Color) : FrameStyle
    data class Mountain(val sky: Color, val peak: Color, val sun: Color) : FrameStyle
    data class Confetti(val bg: Color, val pieces: List<Color>) : FrameStyle
}

/**
 * One earnable circular frame. [requiredTier] gates it behind a permanent
 * streak achievement badge; [foundingOnly] marks the Founding Member
 * exclusive. Frames with neither are free starters.
 */
data class AvatarFrame(
    val id: String,
    val style: FrameStyle,
    val requiredTier: BadgeTier? = null,
    val foundingOnly: Boolean = false,
)

/** True when this frame is available to the user right now. */
fun AvatarFrame.isUnlocked(earned: Set<BadgeTier>): Boolean =
    (requiredTier == null || requiredTier in earned) && !foundingOnly

/**
 * Clothing silhouettes the outfit renderer knows how to draw. Each design is
 * real garment artwork (collars, hoods, lapels, straps…) — not just a color
 * swap.
 */
enum class OutfitDesign {
    TEE, HOODIE, SHIRT, SAILOR, TURTLENECK, STRIPES, OVERALLS,
    BLAZER, VEST, SCARF, VARSITY, CAPE, GALAXY, SUNSET, GRADIENT,
}

/**
 * One outfit: a drawn design plus its palette. A non-null [task] marks a
 * Studio exclusive that unlocks by completing the daily task/focus goal.
 */
data class OutfitSpec(
    val id: String,
    val design: OutfitDesign,
    val base: Color,
    val accent: Color,
    val task: CompanionTask? = null,
)

/**
 * A little sidekick that sits on or near the companion's shoulder.
 * A non-null [task] marks an exclusive pet earned through daily tasks.
 */
data class PetSpec(
    val id: String,
    val emoji: String,
    val task: CompanionTask? = null,
)

/**
 * A facial expression preset. Index 0 ("custom") renders the user's own
 * eye/mouth picks; the rest carry unique artwork. A non-null [task] marks
 * an exclusive expression earned through daily tasks.
 */
data class ExpressionSpec(
    val id: String,
    val task: CompanionTask? = null,
)

/**
 * The full companion customization catalog — all drawn procedurally.
 * Gender models, outfit designs, expression presets, and shoulder pets;
 * exclusive items unlock through [CompanionTask] daily goals.
 */
object AvatarCatalog {

    /** 0 = female, 1 = male. Same core art style, different build. */
    const val GENDER_COUNT = 2

    val skinTones: List<Color> = listOf(
        Color(0xFFFFE4D0), Color(0xFFFAD7B8), Color(0xFFF2C49B),
        Color(0xFFE8B088), Color(0xFFD99C6B), Color(0xFFC98850),
        Color(0xFFB57544), Color(0xFF9C5F35), Color(0xFF84492A),
        Color(0xFF6B3A22), Color(0xFF54301E), Color(0xFF3E2418),
    )

    const val FACE_COUNT = 6
    const val EYES_COUNT = 12
    const val MOUTH_COUNT = 10
    const val HAIR_COUNT = 14

    val hairColors: List<Color> = listOf(
        Color(0xFF2B2118), Color(0xFF4A3220), Color(0xFF6B4A2B),
        Color(0xFF8B5E3C), Color(0xFFB5854E), Color(0xFFE0C068),
        Color(0xFFEDE3D0), Color(0xFFA84E32), Color(0xFF7A7A85),
        Color(0xFFC98BC0),
    )

    /**
     * Outfits: 12 free designs (first 12 keep the legacy palette so old
     * saved indices still look familiar) + 6 task-locked exclusives.
     */
    val outfits: List<OutfitSpec> = listOf(
        OutfitSpec("tee", OutfitDesign.TEE, Color(0xFF5D8A66), Color(0xFFEFE5D2)),
        OutfitSpec("hoodie", OutfitDesign.HOODIE, Color(0xFFC7724F), Color(0xFFA85A3B)),
        OutfitSpec("shirt", OutfitDesign.SHIRT, Color(0xFF33655A), Color(0xFFF6F1E4)),
        OutfitSpec("sailor", OutfitDesign.SAILOR, Color(0xFF93B5C6), Color(0xFF2E4A5B)),
        OutfitSpec("turtleneck", OutfitDesign.TURTLENECK, Color(0xFF9C8FCB), Color(0xFF6C5F9E)),
        OutfitSpec("stripes", OutfitDesign.STRIPES, Color(0xFFB0567A), Color(0xFFF3E8D7)),
        OutfitSpec("overalls", OutfitDesign.OVERALLS, Color(0xFFE0A25E), Color(0xFFF6EBD7)),
        OutfitSpec("blazer", OutfitDesign.BLAZER, Color(0xFF4C463C), Color(0xFFF6F1E4)),
        OutfitSpec("vest", OutfitDesign.VEST, Color(0xFF2E5B6B), Color(0xFFEFE5D2)),
        OutfitSpec("scarf", OutfitDesign.SCARF, Color(0xFFCE5A6D), Color(0xFF8F3448)),
        OutfitSpec("varsity", OutfitDesign.VARSITY, Color(0xFF7A7466), Color(0xFFEFE5D2)),
        OutfitSpec("linen", OutfitDesign.TEE, Color(0xFFEFE5D2), Color(0xFF9CAF88)),
        // ── Task-locked exclusives ───────────────────────────────
        OutfitSpec(
            "sunset", OutfitDesign.SUNSET, Color(0xFFF6E7CB), Color(0xFFE2794A),
            CompanionTask(CompanionTaskType.CHECKIN_STREAK, 5),
        ),
        OutfitSpec(
            "gardener", OutfitDesign.OVERALLS, Color(0xFF6B9873), Color(0xFFF6EBD7),
            CompanionTask(CompanionTaskType.REFLECTIONS_WRITTEN, 7),
        ),
        OutfitSpec(
            "galaxy", OutfitDesign.GALAXY, Color(0xFF2B3160), Color(0xFFAAB6F0),
            CompanionTask(CompanionTaskType.TOTAL_CHECKINS, 25),
        ),
        OutfitSpec(
            "champion", OutfitDesign.VARSITY, Color(0xFFE2B33C), Color(0xFF4C3A1E),
            CompanionTask(CompanionTaskType.FULL_STREAK, 7),
        ),
        OutfitSpec(
            "moodweaver", OutfitDesign.GRADIENT, Color(0xFF9CAF88), Color(0xFFE9B44C),
            CompanionTask(CompanionTaskType.MOODS_LOGGED, 10),
        ),
        OutfitSpec(
            "cape", OutfitDesign.CAPE, Color(0xFF3E4E6B), Color(0xFFC94F4F),
            CompanionTask(CompanionTaskType.FULL_STREAK, 14),
        ),
    )

    /**
     * Shoulder pets. The first 10 (incl. "none") keep the legacy order so
     * saved indices stay valid; the rest are task-locked exclusives.
     */
    val pets: List<PetSpec> = listOf(
        PetSpec("none", ""),
        PetSpec("cat", "🐱"),
        PetSpec("dog", "🐶"),
        PetSpec("rabbit", "🐰"),
        PetSpec("fox", "🦊"),
        PetSpec("parrot", "🦜"),
        PetSpec("butterfly", "🦋"),
        PetSpec("turtle", "🐢"),
        PetSpec("plant", "🪴"),
        PetSpec("star", "⭐"),
        // ── Task-locked exclusives ───────────────────────────────
        PetSpec("bee", "🐝", CompanionTask(CompanionTaskType.COMPLETE_ALL_TODAY, 1)),
        PetSpec("hamster", "🐹", CompanionTask(CompanionTaskType.TOTAL_CHECKINS, 10)),
        PetSpec("owl", "🦉", CompanionTask(CompanionTaskType.REFLECTIONS_WRITTEN, 3)),
        PetSpec("penguin", "🐧", CompanionTask(CompanionTaskType.CHECKIN_STREAK, 3)),
        PetSpec("koala", "🐨", CompanionTask(CompanionTaskType.MOODS_LOGGED, 7)),
        PetSpec("whale", "🐳", CompanionTask(CompanionTaskType.CHECKIN_STREAK, 7)),
        PetSpec("panda", "🐼", CompanionTask(CompanionTaskType.TOTAL_CHECKINS, 30)),
        PetSpec("dragon", "🐉", CompanionTask(CompanionTaskType.FULL_STREAK, 7)),
        PetSpec("unicorn", "🦄", CompanionTask(CompanionTaskType.FULL_STREAK, 14)),
    )

    /**
     * Expression presets. Index 0 = "custom" (user's own eyes + mouth);
     * 1–5 are free presets; the rest are task-locked exclusives.
     */
    val expressions: List<ExpressionSpec> = listOf(
        ExpressionSpec("custom"),
        ExpressionSpec("smiling"),
        ExpressionSpec("winking"),
        ExpressionSpec("neutral"),
        ExpressionSpec("focused"),
        ExpressionSpec("cheerful"),
        // ── Task-locked exclusives ───────────────────────────────
        ExpressionSpec("sleepy", CompanionTask(CompanionTaskType.MOODS_LOGGED, 3)),
        ExpressionSpec("starstruck", CompanionTask(CompanionTaskType.COMPLETE_ALL_TODAY, 1)),
        ExpressionSpec("determined", CompanionTask(CompanionTaskType.CHECKIN_STREAK, 5)),
        ExpressionSpec("joyful", CompanionTask(CompanionTaskType.TOTAL_CHECKINS, 20)),
        ExpressionSpec("silly", CompanionTask(CompanionTaskType.REFLECTIONS_WRITTEN, 5)),
        ExpressionSpec("hearts", CompanionTask(CompanionTaskType.FULL_STREAK, 3)),
    )

    /**
     * Ids of every task-gated item whose requirement [data] currently
     * satisfies. Used by the ViewModel to persist newly earned unlocks.
     */
    fun taskUnlockableIds(data: AppData): List<String> = buildList {
        outfits.forEach { spec -> spec.task?.let { if (it.isMet(data)) add(spec.id) } }
        pets.forEach { spec -> spec.task?.let { if (it.isMet(data)) add(spec.id) } }
        expressions.forEach { spec -> spec.task?.let { if (it.isMet(data)) add(spec.id) } }
    }

    /** Display-name lookup key helpers (mapped to AppStrings tables). */
    fun petNameKey(id: String): String = "pet" + id.replaceFirstChar { it.uppercaseChar() }
    fun outfitNameKey(id: String): String = "outfit" + id.replaceFirstChar { it.uppercaseChar() }
    fun expressionNameKey(id: String): String = "expr" + id.replaceFirstChar { it.uppercaseChar() }

    /**
     * 30 circular frames: 10 free starters, then tiers unlocked by the
     * permanent 3/7/14/30-day full-completion badges, plus one Founding
     * Member exclusive. Earned, never sold.
     */
    val frames: List<AvatarFrame> = listOf(
        // ── Free starters ────────────────────────────────────────
        AvatarFrame("sage", FrameStyle.Solid(Color(0xFF9CAF88))),
        AvatarFrame("cream", FrameStyle.Solid(Color(0xFFF3E8D7))),
        AvatarFrame("terracotta", FrameStyle.Solid(Color(0xFFD08B6A))),
        AvatarFrame("dustyBlue", FrameStyle.Solid(Color(0xFF93B5C6))),
        AvatarFrame("blush", FrameStyle.Solid(Color(0xFFE8B4B8))),
        AvatarFrame("charcoal", FrameStyle.Solid(Color(0xFF4C463C))),
        AvatarFrame("meadow", FrameStyle.Gradient(listOf(Color(0xFFA8C686), Color(0xFF6B9873)))),
        AvatarFrame("dawn", FrameStyle.Gradient(listOf(Color(0xFFF6D8AE), Color(0xFFE8A87C)))),
        AvatarFrame("mist", FrameStyle.Gradient(listOf(Color(0xFFCFE0E8), Color(0xFF93B5C6)))),
        AvatarFrame("lilac", FrameStyle.Gradient(listOf(Color(0xFFD7CDE8), Color(0xFF9C8FCB)))),
        // ── 3-day streak ─────────────────────────────────────────
        AvatarFrame("sunrise", FrameStyle.Gradient(listOf(Color(0xFFF2B880), Color(0xFFD96C4F))), BadgeTier.THREE_DAYS),
        AvatarFrame("ocean", FrameStyle.Gradient(listOf(Color(0xFF8ED4E8), Color(0xFF2A7C97))), BadgeTier.THREE_DAYS),
        AvatarFrame("dotsSage", FrameStyle.Dots(Color(0xFFF7F0E1), Color(0xFF9CAF88)), BadgeTier.THREE_DAYS),
        AvatarFrame("stripesSand", FrameStyle.Stripes(Color(0xFFF6E7CB), Color(0xFFE2A25E)), BadgeTier.THREE_DAYS),
        AvatarFrame("rose", FrameStyle.Gradient(listOf(Color(0xFFF4AFC6), Color(0xFFB0567A))), BadgeTier.THREE_DAYS),
        // ── 7-day streak ─────────────────────────────────────────
        AvatarFrame("raysHoney", FrameStyle.Rays(Color(0xFFF7E7B2), Color(0xFFEFC26B)), BadgeTier.SEVEN_DAYS),
        AvatarFrame("waves", FrameStyle.Waves(Color(0xFFCDEAF0), Color(0xFF4EA8C2), Color(0xFFFFFFFF)), BadgeTier.SEVEN_DAYS),
        AvatarFrame("dotsNight", FrameStyle.Dots(Color(0xFF3B4368), Color(0xFFAAB6F0)), BadgeTier.SEVEN_DAYS),
        AvatarFrame("stripesSage", FrameStyle.Stripes(Color(0xFFE3EDE0), Color(0xFF5D8A66)), BadgeTier.SEVEN_DAYS),
        AvatarFrame("confetti", FrameStyle.Confetti(Color(0xFFFDF6E9), listOf(Color(0xFF9CAF88), Color(0xFFC7724F), Color(0xFFE9B44C), Color(0xFF6D82C4))), BadgeTier.SEVEN_DAYS),
        // ── 14-day streak ────────────────────────────────────────
        AvatarFrame("hills", FrameStyle.Hills(Color(0xFFFBE8C9), Color(0xFFA8C686), Color(0xFF6B9873)), BadgeTier.FOURTEEN_DAYS),
        AvatarFrame("mountain", FrameStyle.Mountain(Color(0xFFFCE3C8), Color(0xFF8A6650), Color(0xFFE9B44C)), BadgeTier.FOURTEEN_DAYS),
        AvatarFrame("nightSky", FrameStyle.NightSky(Color(0xFF2B3160), Color(0xFF141830), Color(0xFFF8F1E2)), BadgeTier.FOURTEEN_DAYS),
        AvatarFrame("wavesDusk", FrameStyle.Waves(Color(0xFFF4C7A1), Color(0xFFC2643A), Color(0xFFFFF3E0)), BadgeTier.FOURTEEN_DAYS),
        AvatarFrame("hillsEvening", FrameStyle.Hills(Color(0xFFD7CDE8), Color(0xFF9C8FCB), Color(0xFF6C5F9E)), BadgeTier.FOURTEEN_DAYS),
        // ── 30-day streak ────────────────────────────────────────
        AvatarFrame("aurora", FrameStyle.Gradient(listOf(Color(0xFF7BE0AD), Color(0xFF5AA9E6), Color(0xFF9C8FCB))), BadgeTier.THIRTY_DAYS),
        AvatarFrame("galaxy", FrameStyle.NightSky(Color(0xFF1B1035), Color(0xFF3D2B6B), Color(0xFFFFD9F2)), BadgeTier.THIRTY_DAYS),
        AvatarFrame("raysGold", FrameStyle.Rays(Color(0xFFF9EDD2), Color(0xFFE2B33C)), BadgeTier.THIRTY_DAYS),
        AvatarFrame("champion", FrameStyle.Confetti(Color(0xFFFFF7E6), listOf(Color(0xFFE2B33C), Color(0xFFC7724F), Color(0xFF9CAF88), Color(0xFFCE5A6D))), BadgeTier.THIRTY_DAYS),
        // ── Founding Member exclusive ────────────────────────────
        AvatarFrame("foundingGlow", FrameStyle.Rays(Color(0xFFFDEBD2), Color(0xFFD98A2B)), foundingOnly = true),
    )
}
