package com.rork.mindsetframestracker.ui.avatar

import androidx.compose.ui.graphics.Color
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.BadgeTier
import com.rork.mindsetframestracker.data.CompanionTask
import com.rork.mindsetframestracker.data.CompanionTaskType
import com.rork.mindsetframestracker.data.isMet
import com.rork.mindsetframestracker.ui.theme.Mf2Artwork

/**
 * Circular background frame styles. Each is drawn procedurally in
 * [drawFrameBackground] - no bundled image assets, so the catalog stays
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
 * real garment artwork (collars, hoods, lapels, straps...) - not just a color
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
 * The full companion customization catalog - all drawn procedurally.
 * Gender models, outfit designs, expression presets, and shoulder pets;
 * exclusive items unlock through [CompanionTask] daily goals.
 */
object AvatarCatalog {

    /** 0 = female, 1 = male. Same core art style, different build. */
    const val GENDER_COUNT = 2

    val skinTones: List<Color> = Mf2Artwork.skinTones

    const val FACE_COUNT = 6
    const val EYES_COUNT = 12
    const val MOUTH_COUNT = 10
    const val HAIR_COUNT = 14

    val hairColors: List<Color> = Mf2Artwork.hairColors

    /**
     * Outfits: 12 free designs (first 12 keep the legacy palette so old
     * saved indices still look familiar) + 6 task-locked exclusives.
     */
    val outfits: List<OutfitSpec> = listOf(
        outfit("tee", OutfitDesign.TEE),
        outfit("hoodie", OutfitDesign.HOODIE),
        outfit("shirt", OutfitDesign.SHIRT),
        outfit("sailor", OutfitDesign.SAILOR),
        outfit("turtleneck", OutfitDesign.TURTLENECK),
        outfit("stripes", OutfitDesign.STRIPES),
        outfit("overalls", OutfitDesign.OVERALLS),
        outfit("blazer", OutfitDesign.BLAZER),
        outfit("vest", OutfitDesign.VEST),
        outfit("scarf", OutfitDesign.SCARF),
        outfit("varsity", OutfitDesign.VARSITY),
        // Same TEE design as "tee", different fabric.
        outfit("linen", OutfitDesign.TEE),
        // -- Task-locked exclusives --
        outfit("sunset", OutfitDesign.SUNSET, CompanionTask(CompanionTaskType.CHECKIN_STREAK, 5)),
        outfit("gardener", OutfitDesign.OVERALLS, CompanionTask(CompanionTaskType.REFLECTIONS_WRITTEN, 7)),
        outfit("galaxy", OutfitDesign.GALAXY, CompanionTask(CompanionTaskType.TOTAL_CHECKINS, 25)),
        outfit("champion", OutfitDesign.VARSITY, CompanionTask(CompanionTaskType.FULL_STREAK, 7)),
        outfit("moodweaver", OutfitDesign.GRADIENT, CompanionTask(CompanionTaskType.MOODS_LOGGED, 10)),
        outfit("cape", OutfitDesign.CAPE, CompanionTask(CompanionTaskType.FULL_STREAK, 14)),
    )

    /**
     * Builds an [OutfitSpec] from the fabric tokens in [Mf2Artwork].
     *
     * [id] doubles as the key into that palette, so an outfit's design and its
     * colours are declared together on one line and can never drift apart.
     */
    private fun outfit(
        id: String,
        design: OutfitDesign,
        task: CompanionTask? = null,
    ): OutfitSpec {
        val colors = Mf2Artwork.outfitColors(id)
        return OutfitSpec(id, design, colors.base, colors.accent, task)
    }

    /**
     * Shoulder pets. The first 10 (incl. "none") keep the legacy order so
     * saved indices stay valid; the rest are task-locked exclusives.
     */
    val pets: List<PetSpec> = listOf(
        PetSpec("none", ""),
        PetSpec("cat", "\uD83D\uDC31"),
        PetSpec("dog", "\uD83D\uDC36"),
        PetSpec("rabbit", "\uD83D\uDC30"),
        PetSpec("fox", "\uD83E\uDD8A"),
        PetSpec("parrot", "\uD83E\uDD9C"),
        PetSpec("butterfly", "\uD83E\uDD8B"),
        PetSpec("turtle", "\uD83D\uDC22"),
        PetSpec("plant", "\uD83E\uDEB4"),
        PetSpec("star", "\u2B50"),
        // -- Task-locked exclusives --
        PetSpec("bee", "\uD83D\uDC1D", CompanionTask(CompanionTaskType.COMPLETE_ALL_TODAY, 1)),
        PetSpec("hamster", "\uD83D\uDC39", CompanionTask(CompanionTaskType.TOTAL_CHECKINS, 10)),
        PetSpec("owl", "\uD83E\uDD89", CompanionTask(CompanionTaskType.REFLECTIONS_WRITTEN, 3)),
        PetSpec("penguin", "\uD83D\uDC27", CompanionTask(CompanionTaskType.CHECKIN_STREAK, 3)),
        PetSpec("koala", "\uD83D\uDC28", CompanionTask(CompanionTaskType.MOODS_LOGGED, 7)),
        PetSpec("whale", "\uD83D\uDC33", CompanionTask(CompanionTaskType.CHECKIN_STREAK, 7)),
        PetSpec("panda", "\uD83D\uDC3C", CompanionTask(CompanionTaskType.TOTAL_CHECKINS, 30)),
        PetSpec("dragon", "\uD83D\uDC09", CompanionTask(CompanionTaskType.FULL_STREAK, 7)),
        PetSpec("unicorn", "\uD83E\uDD84", CompanionTask(CompanionTaskType.FULL_STREAK, 14)),
    )

    /**
     * Expression presets. Index 0 = "custom" (user's own eyes + mouth);
     * 1-5 are free presets; the rest are task-locked exclusives.
     */
    val expressions: List<ExpressionSpec> = listOf(
        ExpressionSpec("custom"),
        ExpressionSpec("smiling"),
        ExpressionSpec("winking"),
        ExpressionSpec("neutral"),
        ExpressionSpec("focused"),
        ExpressionSpec("cheerful"),
        // -- Task-locked exclusives --
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
        // -- Free starters --
        AvatarFrame("sage", FrameStyle.Solid(Mf2Artwork.FrameSage)),
        AvatarFrame("cream", FrameStyle.Solid(Mf2Artwork.FrameCream)),
        AvatarFrame("terracotta", FrameStyle.Solid(Mf2Artwork.FrameTerracotta)),
        AvatarFrame("dustyBlue", FrameStyle.Solid(Mf2Artwork.FrameDustyBlue)),
        AvatarFrame("blush", FrameStyle.Solid(Mf2Artwork.FrameBlush)),
        AvatarFrame("charcoal", FrameStyle.Solid(Mf2Artwork.FrameCharcoal)),
        AvatarFrame("meadow", FrameStyle.Gradient(listOf(Mf2Artwork.FrameMeadowOuter, Mf2Artwork.FrameMeadowInner))),
        AvatarFrame("dawn", FrameStyle.Gradient(listOf(Mf2Artwork.FrameDawnOuter, Mf2Artwork.FrameDawnInner))),
        AvatarFrame("mist", FrameStyle.Gradient(listOf(Mf2Artwork.FrameMistOuter, Mf2Artwork.FrameMistInner))),
        AvatarFrame("lilac", FrameStyle.Gradient(listOf(Mf2Artwork.FrameLilacOuter, Mf2Artwork.FrameLilacInner))),
        // -- 3-day streak --
        AvatarFrame("sunrise", FrameStyle.Gradient(listOf(Mf2Artwork.FrameSunriseOuter, Mf2Artwork.FrameSunriseInner)), BadgeTier.THREE_DAYS),
        AvatarFrame("ocean", FrameStyle.Gradient(listOf(Mf2Artwork.FrameOceanOuter, Mf2Artwork.FrameOceanInner)), BadgeTier.THREE_DAYS),
        AvatarFrame("dotsSage", FrameStyle.Dots(Mf2Artwork.FrameDotsSageBg, Mf2Artwork.FrameDotsSageDot), BadgeTier.THREE_DAYS),
        AvatarFrame("stripesSand", FrameStyle.Stripes(Mf2Artwork.FrameStripesSandBg, Mf2Artwork.FrameStripesSandStripe), BadgeTier.THREE_DAYS),
        AvatarFrame("rose", FrameStyle.Gradient(listOf(Mf2Artwork.FrameRoseOuter, Mf2Artwork.FrameRoseInner)), BadgeTier.THREE_DAYS),
        // -- 7-day streak --
        AvatarFrame("raysHoney", FrameStyle.Rays(Mf2Artwork.FrameRaysHoneyBg, Mf2Artwork.FrameRaysHoneyRay), BadgeTier.SEVEN_DAYS),
        AvatarFrame("waves", FrameStyle.Waves(Mf2Artwork.FrameWavesSky, Mf2Artwork.FrameWavesSea, Mf2Artwork.FrameFoam), BadgeTier.SEVEN_DAYS),
        AvatarFrame("dotsNight", FrameStyle.Dots(Mf2Artwork.FrameDotsNightBg, Mf2Artwork.FrameDotsNightDot), BadgeTier.SEVEN_DAYS),
        AvatarFrame("stripesSage", FrameStyle.Stripes(Mf2Artwork.FrameStripesSageBg, Mf2Artwork.FrameStripesSageStripe), BadgeTier.SEVEN_DAYS),
        AvatarFrame("confetti", FrameStyle.Confetti(Mf2Artwork.FrameConfettiBg, Mf2Artwork.ConfettiPieces), BadgeTier.SEVEN_DAYS),
        // -- 14-day streak --
        AvatarFrame("hills", FrameStyle.Hills(Mf2Artwork.FrameHillsSky, Mf2Artwork.FrameHillsFar, Mf2Artwork.FrameHillsNear), BadgeTier.FOURTEEN_DAYS),
        AvatarFrame("mountain", FrameStyle.Mountain(Mf2Artwork.FrameMountainSky, Mf2Artwork.FrameMountainPeak, Mf2Artwork.FrameMountainSun), BadgeTier.FOURTEEN_DAYS),
        AvatarFrame("nightSky", FrameStyle.NightSky(Mf2Artwork.FrameNightSkyTop, Mf2Artwork.FrameNightSkyBottom, Mf2Artwork.FrameNightSkyStar), BadgeTier.FOURTEEN_DAYS),
        AvatarFrame("wavesDusk", FrameStyle.Waves(Mf2Artwork.FrameWavesDuskSky, Mf2Artwork.FrameWavesDuskSea, Mf2Artwork.FrameWavesDuskFoam), BadgeTier.FOURTEEN_DAYS),
        AvatarFrame("hillsEvening", FrameStyle.Hills(Mf2Artwork.FrameHillsEveningSky, Mf2Artwork.FrameHillsEveningFar, Mf2Artwork.FrameHillsEveningNear), BadgeTier.FOURTEEN_DAYS),
        // -- 30-day streak --
        AvatarFrame("aurora", FrameStyle.Gradient(listOf(Mf2Artwork.FrameAuroraGreen, Mf2Artwork.FrameAuroraBlue, Mf2Artwork.FrameAuroraViolet)), BadgeTier.THIRTY_DAYS),
        AvatarFrame("galaxy", FrameStyle.NightSky(Mf2Artwork.FrameGalaxyTop, Mf2Artwork.FrameGalaxyBottom, Mf2Artwork.FrameGalaxyStar), BadgeTier.THIRTY_DAYS),
        AvatarFrame("raysGold", FrameStyle.Rays(Mf2Artwork.FrameRaysGoldBg, Mf2Artwork.FrameRaysGoldRay), BadgeTier.THIRTY_DAYS),
        AvatarFrame("champion", FrameStyle.Confetti(Mf2Artwork.FrameChampionBg, Mf2Artwork.FrameChampionPieces), BadgeTier.THIRTY_DAYS),
        // -- Founding Member exclusive --
        AvatarFrame("foundingGlow", FrameStyle.Rays(Mf2Artwork.FrameFoundingGlowBg, Mf2Artwork.FrameFoundingGlowRay), foundingOnly = true),
    )
}
