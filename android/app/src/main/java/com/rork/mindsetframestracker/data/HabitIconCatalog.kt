package com.rork.mindsetframestracker.data

import com.rork.mindsetframestracker.R

data class HabitIcon(
    val id: String,
    val drawableRes: Int,
    val label: String,
    val category: HabitCategory,
    val colorHex: Long,
    /** Default reminder time in minutes-from-midnight, pre-filled when this icon is tapped. */
    val defaultReminderMinutes: Int,
)

object HabitIconCatalog {
    val icons: List<HabitIcon> = listOf(
        HabitIcon("water", R.drawable.ic_health_water_glass, "Drink Water", HabitCategory.HEALTH, 0xFF4FC3F7, 9 * 60),
        HabitIcon("walking", R.drawable.ic_health_walking, "Walk", HabitCategory.HEALTH, 0xFFAED581, 7 * 60),
        HabitIcon("sleep", R.drawable.ic_health_moon_stars, "Sleep Early", HabitCategory.HEALTH, 0xFF7986CB, 22 * 60),
        HabitIcon("stretch", R.drawable.ic_health_stretching, "Stretch", HabitCategory.HEALTH, 0xFF4DB6AC, 7 * 60 + 30),
        HabitIcon("meditate", R.drawable.ic_mind_meditating, "Meditate", HabitCategory.MIND, 0xFFBA68C8, 8 * 60),
        HabitIcon("journal", R.drawable.ic_mind_notebook, "Journal", HabitCategory.MIND, 0xFFFFB74D, 21 * 60),
        HabitIcon("gratitude", R.drawable.ic_mind_gratitude, "Gratitude", HabitCategory.MIND, 0xFFFFD54F, 21 * 60 + 30),
        HabitIcon("noPhone", R.drawable.ic_mind_phone_noslash, "No Phone Morning", HabitCategory.MIND, 0xFF9575CD, 7 * 60),
        HabitIcon("read", R.drawable.ic_productivity_book, "Read", HabitCategory.PRODUCTIVITY, 0xFF90A4AE, 20 * 60),
        HabitIcon("plan", R.drawable.ic_productivity_calendar, "Plan Tomorrow", HabitCategory.PRODUCTIVITY, 0xFF80CBC4, 21 * 60),
        HabitIcon("tidy", R.drawable.ic_productivity_broom, "Tidy Up", HabitCategory.PRODUCTIVITY, 0xFFA1887F, 18 * 60),
        HabitIcon("inbox", R.drawable.ic_productivity_inbox, "Inbox Zero", HabitCategory.PRODUCTIVITY, 0xFF78909C, 17 * 60),
        HabitIcon("message", R.drawable.ic_social_speech_heart, "Message Someone", HabitCategory.SOCIAL, 0xFFF06292, 19 * 60),
        HabitIcon("compliment", R.drawable.ic_social_smile_sparkle, "Compliment", HabitCategory.SOCIAL, 0xFFFF8A65, 12 * 60),
        HabitIcon("spend", R.drawable.ic_finance_coin_plus, "Track Spending", HabitCategory.FINANCE, 0xFF81C784, 20 * 60),
        HabitIcon("noSpend", R.drawable.ic_finance_wallet_noslash, "No Impulse Buy", HabitCategory.FINANCE, 0xFFE57373, 9 * 60),
        HabitIcon("running", R.drawable.ic_health_running, "Running", HabitCategory.HEALTH, 0xFFAED581, 6 * 60 + 30),
        HabitIcon("walk2", R.drawable.ic_health_walk, "Evening Walk", HabitCategory.HEALTH, 0xFFAED581, 18 * 60),
        HabitIcon("basketball", R.drawable.ic_health_basketball, "Basketball", HabitCategory.HEALTH, 0xFFAED581, 17 * 60),
        HabitIcon("gym", R.drawable.ic_health_gym_all_activity, "Gym", HabitCategory.HEALTH, 0xFFAED581, 6 * 60),
        HabitIcon("medicine", R.drawable.ic_health_take_medicine, "Take Medicine", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("biotin", R.drawable.ic_health_biotin, "Biotin", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("protein", R.drawable.ic_health_protein_intake, "Protein Intake", HabitCategory.HEALTH, 0xFF4FC3F7, 12 * 60),
        HabitIcon("cholesterol", R.drawable.ic_health_cholesterol_intake, "Cholesterol Check", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("meeting", R.drawable.ic_productivity_meeting, "Meeting Prep", HabitCategory.PRODUCTIVITY, 0xFF78909C, 9 * 60),
    )

    fun byId(id: String): HabitIcon? = icons.find { it.id == id }
    fun byCategory(category: HabitCategory): List<HabitIcon> = icons.filter { it.category == category }
}
