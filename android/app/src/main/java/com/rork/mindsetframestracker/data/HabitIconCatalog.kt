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
    /**
     * The To-Do List icon behaves differently from every other icon: tapping
     * it opens the goal → checklist creation flow instead of instantly adding
     * a catalog habit with an alarm.
     */
    val isTodoList: Boolean = false,
)

object HabitIconCatalog {
    val icons: List<HabitIcon> = listOf(
        HabitIcon("todoList", R.drawable.ic_productivity_todo_list, "To-Do List", HabitCategory.PRODUCTIVITY, 0xFF9CCC65, 9 * 60, isTodoList = true),
        HabitIcon("water", R.drawable.ic_health_water_glass, "Drink Water", HabitCategory.HEALTH, 0xFF4FC3F7, 9 * 60),
        HabitIcon("walking", R.drawable.ic_health_walking, "Walk", HabitCategory.HEALTH, 0xFFAED581, 7 * 60),
        HabitIcon("sleep", R.drawable.ic_health_moon_stars, "Sleep Early", HabitCategory.HEALTH, 0xFF7986CB, 22 * 60),
        HabitIcon("stretch", R.drawable.ic_health_stretching, "Stretch", HabitCategory.HEALTH, 0xFF4DB6AC, 7 * 60 + 30),
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
        HabitIcon("walk2", R.drawable.ic_health_walk, "Walk", HabitCategory.HEALTH, 0xFFAED581, 18 * 60),
        HabitIcon("basketball", R.drawable.ic_health_basketball, "Basketball", HabitCategory.HEALTH, 0xFFAED581, 17 * 60),
        HabitIcon("gym", R.drawable.ic_health_gym_all_activity, "Gym", HabitCategory.HEALTH, 0xFFAED581, 6 * 60),
        HabitIcon("medicine", R.drawable.ic_health_take_medicine, "Take Medicine", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("biotin", R.drawable.ic_health_biotin, "Biotin", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("protein", R.drawable.ic_health_protein_intake, "Protein Intake", HabitCategory.HEALTH, 0xFF4FC3F7, 12 * 60),
        HabitIcon("cholesterol", R.drawable.ic_health_cholesterol_intake, "Cholesterol Check", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("meeting", R.drawable.ic_productivity_meeting, "Meeting Prep", HabitCategory.PRODUCTIVITY, 0xFF78909C, 9 * 60),
        
        // Strava activities based on drawable filenames found in res/drawable:
        HabitIcon("strava_alpine_ski", R.drawable.ic_strava_alpine_ski, "Alpine Ski", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_backcountry_ski", R.drawable.ic_strava_backcountry_ski, "Backcountry Ski", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_badminton", R.drawable.ic_strava_badminton, "Badminton", HabitCategory.HEALTH, 0xFFAED581, 17 * 60),
        HabitIcon("strava_canoe", R.drawable.ic_strava_canoe, "Canoe", HabitCategory.HEALTH, 0xFF4FC3F7, 10 * 60),
        HabitIcon("strava_cricket", R.drawable.ic_strava_cricket, "Cricket", HabitCategory.HEALTH, 0xFFAED581, 15 * 60),
        HabitIcon("strava_crossfit", R.drawable.ic_strava_crossfit, "Crossfit", HabitCategory.HEALTH, 0xFFAED581, 7 * 60),
        HabitIcon("strava_dance", R.drawable.ic_strava_dance, "Dance", HabitCategory.HEALTH, 0xFFAED581, 19 * 60),
        HabitIcon("strava_ebike_ride", R.drawable.ic_strava_ebike_ride, "E-Bike Ride", HabitCategory.HEALTH, 0xFF4FC3F7, 10 * 60),
        HabitIcon("strava_elliptical", R.drawable.ic_strava_elliptical, "Elliptical", HabitCategory.HEALTH, 0xFFAED581, 8 * 60),
        HabitIcon("strava_emtb_ride", R.drawable.ic_strava_emtb_ride, "E-MTB Ride", HabitCategory.HEALTH, 0xFF4FC3F7, 9 * 60),
        HabitIcon("strava_football", R.drawable.ic_strava_football, "Football", HabitCategory.HEALTH, 0xFFAED581, 16 * 60),
        HabitIcon("strava_golf", R.drawable.ic_strava_golf, "Golf", HabitCategory.HEALTH, 0xFFAED581, 13 * 60),
        HabitIcon("strava_gravel_ride", R.drawable.ic_strava_gravel_ride, "Gravel Ride", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_handcycle", R.drawable.ic_strava_handcycle, "Handcycle", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_hiit", R.drawable.ic_strava_hiit, "HIIT", HabitCategory.HEALTH, 0xFFAED581, 7 * 60),
        HabitIcon("strava_hike", R.drawable.ic_strava_hike, "Hike", HabitCategory.HEALTH, 0xFFAED581, 9 * 60),
        HabitIcon("strava_ice_skate", R.drawable.ic_strava_ice_skate, "Ice Skate", HabitCategory.HEALTH, 0xFF4FC3F7, 14 * 60),
        HabitIcon("strava_inline_skate", R.drawable.ic_strava_inline_skate, "Inline Skate", HabitCategory.HEALTH, 0xFF4FC3F7, 15 * 60),
        HabitIcon("strava_kayak", R.drawable.ic_strava_kayak, "Kayak", HabitCategory.HEALTH, 0xFF4FC3F7, 11 * 60),
        HabitIcon("strava_kitesurf", R.drawable.ic_strava_kitesurf, "Kitesurf", HabitCategory.HEALTH, 0xFF4FC3F7, 12 * 60),
        HabitIcon("strava_mountain_bike_ride", R.drawable.ic_strava_mountain_bike_ride, "Mountain Bike", HabitCategory.HEALTH, 0xFF4FC3F7, 9 * 60),
        HabitIcon("strava_nordic_ski", R.drawable.ic_strava_nordic_ski, "Nordic Ski", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_padel", R.drawable.ic_strava_padel, "Padel", HabitCategory.HEALTH, 0xFFAED581, 16 * 60),
        HabitIcon("strava_pickleball", R.drawable.ic_strava_pickleball, "Pickleball", HabitCategory.HEALTH, 0xFFAED581, 10 * 60),
        HabitIcon("strava_pilates", R.drawable.ic_strava_pilates, "Pilates", HabitCategory.HEALTH, 0xFF4DB6AC, 8 * 60),
        HabitIcon("strava_racquetball", R.drawable.ic_strava_racquetball, "Racquetball", HabitCategory.HEALTH, 0xFFAED581, 17 * 60),
        HabitIcon("strava_ride", R.drawable.ic_strava_ride, "Ride", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_rock_climb", R.drawable.ic_strava_rock_climb, "Rock Climb", HabitCategory.HEALTH, 0xFFAED581, 14 * 60),
        HabitIcon("strava_roller_ski", R.drawable.ic_strava_roller_ski, "Roller Ski", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_rowing", R.drawable.ic_strava_rowing, "Rowing", HabitCategory.HEALTH, 0xFF4FC3F7, 7 * 60),
        HabitIcon("strava_sailing", R.drawable.ic_strava_sailing, "Sailing", HabitCategory.HEALTH, 0xFF4FC3F7, 12 * 60),
        HabitIcon("strava_skateboarding", R.drawable.ic_strava_skateboarding, "Skateboarding", HabitCategory.HEALTH, 0xFFAED581, 15 * 60),
        HabitIcon("strava_snowboard", R.drawable.ic_strava_snowboard, "Snowboard", HabitCategory.HEALTH, 0xFF4FC3F7, 9 * 60),
        HabitIcon("strava_snowshoe", R.drawable.ic_strava_snowshoe, "Snowshoe", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_squash", R.drawable.ic_strava_squash, "Squash", HabitCategory.HEALTH, 0xFFAED581, 17 * 60),
        HabitIcon("strava_stair_stepper", R.drawable.ic_strava_stair_stepper, "Stair Stepper", HabitCategory.HEALTH, 0xFFAED581, 7 * 60),
        HabitIcon("strava_stand_up_paddling", R.drawable.ic_strava_stand_up_paddling, "SUP", HabitCategory.HEALTH, 0xFF4FC3F7, 10 * 60),
        HabitIcon("strava_surf", R.drawable.ic_strava_surf, "Surf", HabitCategory.HEALTH, 0xFF4FC3F7, 9 * 60),
        HabitIcon("strava_swim", R.drawable.ic_strava_swim, "Swim", HabitCategory.HEALTH, 0xFF4FC3F7, 6 * 60),
        HabitIcon("table_tennis", R.drawable.ic_strava_table_tennis, "Table Tennis", HabitCategory.HEALTH, 0xFFAED581, 18 * 60),
        HabitIcon("strava_tennis", R.drawable.ic_strava_tennis, "Tennis", HabitCategory.HEALTH, 0xFFAED581, 16 * 60),
        HabitIcon("strava_trail_run", R.drawable.ic_strava_trail_run, "Trail Run", HabitCategory.HEALTH, 0xFFAED581, 7 * 60),
        HabitIcon("strava_velomobile", R.drawable.ic_strava_velomobile, "Velomobile", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_virtual_ride", R.drawable.ic_strava_virtual_ride, "Virtual Ride", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_virtual_rowing", R.drawable.ic_strava_virtual_rowing, "Virtual Rowing", HabitCategory.HEALTH, 0xFF4FC3F7, 7 * 60),
        HabitIcon("strava_virtual_run", R.drawable.ic_strava_virtual_run, "Virtual Run", HabitCategory.HEALTH, 0xFFAED581, 7 * 60),
        HabitIcon("strava_volleyball", R.drawable.ic_strava_volleyball, "Volleyball", HabitCategory.HEALTH, 0xFFAED581, 17 * 60),
        HabitIcon("strava_weight_training", R.drawable.ic_strava_weight_training, "Weight Training", HabitCategory.HEALTH, 0xFFAED581, 8 * 60),
        HabitIcon("strava_wheelchair", R.drawable.ic_strava_wheelchair, "Wheelchair", HabitCategory.HEALTH, 0xFF4FC3F7, 8 * 60),
        HabitIcon("strava_windsurf", R.drawable.ic_strava_windsurf, "Windsurf", HabitCategory.HEALTH, 0xFF4FC3F7, 11 * 60),
        HabitIcon("strava_workout", R.drawable.ic_strava_workout, "Workout", HabitCategory.HEALTH, 0xFFAED581, 7 * 60),
        HabitIcon("strava_yoga", R.drawable.ic_strava_yoga, "Yoga", HabitCategory.MIND, 0xFFBA68C8, 7 * 60),
    )

    fun byId(id: String): HabitIcon? = icons.find { it.id == id }
    fun byCategory(category: HabitCategory): List<HabitIcon> = icons.filter { it.category == category }
}
