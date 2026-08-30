package com.rork.mindsetframestracker.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.rork.mindsetframestracker.BuildConfig
import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.MindsetRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Polar AccessLink API integration — OAuth 2.0 flow + REST API.
 *
 * Free for every user. Reads daily activity data (steps, calories,
 * distance) from Polar's AccessLink API after the user authorises the
 * app through Polar Flow's OAuth consent screen.
 *
 * Setup:
 *  1. Register at https://admin.polaraccesslink.com/
 *  2. Create an API client:
 *     - Authorization Callback URL: mindsetframes://polar-callback
 *     - Scopes: accesslink.read_all (or at minimum dailyActivity)
 *  3. Set POLAR_CLIENT_ID and POLAR_CLIENT_SECRET in BuildConfig.
 *
 * See: https://www.polar.com/accesslink-api/
 */
object PolarClient {

    private const val TAG = "PolarClient"

    // ── Configuration ────────────────────────────────────────────────
    /**
     * Polar AccessLink OAuth client ID — injected at build time via the
     * POLAR_CLIENT_ID env var / gradle property; see build.gradle.kts.
     */
    val CLIENT_ID: String = BuildConfig.POLAR_CLIENT_ID

    /**
     * Polar AccessLink OAuth client secret — needed client-side because
     * Polar's token endpoint requires HTTP Basic auth (client_id:client_secret).
     * Injected at build time via the POLAR_CLIENT_SECRET_KEY env var /
     * gradle property; see build.gradle.kts.
     */
    val CLIENT_SECRET: String = BuildConfig.POLAR_CLIENT_SECRET
    private const val REDIRECT_URI = "mindsetframes://polar-callback"
    private const val AUTH_URL = "https://flow.polar.com/oauth2/authorization"
    private const val TOKEN_URL = "https://polarremote.com/v2/oauth2/token"
    private const val REGISTER_URL = "https://www.polaraccesslink.com/v3/users"
    private const val DAILY_ACTIVITY_URL = "https://www.polaraccesslink.com/v3/users/daily-activity"

    /** True when the Polar client ID is configured (non-empty). */
    val isConfigured: Boolean get() = CLIENT_ID.isNotBlank() && CLIENT_SECRET.isNotBlank()

    /**
     * Same set of physical-movement activities supported via step-based
     * tracking from the Polar device/app.
     */
    val supportedActivityIconIds = setOf(
        "walking", "running", "basketball", "gym", "stretch",
        "strava_badminton", "strava_crossfit", "strava_dance",
        "strava_elliptical", "strava_football", "strava_hiit",
        "strava_hike", "strava_inline_skate", "strava_pilates",
        "strava_racquetball", "strava_ride", "strava_rock_climb",
        "strava_rowing", "strava_squash", "strava_stair_stepper",
        "strava_swim", "strava_tennis", "strava_trail_run",
        "strava_volleyball", "strava_weight_training", "strava_workout",
        "strava_yoga", "strava_mountain_bike_ride", "strava_gravel_ride",
        "strava_ebike_ride", "strava_emtb_ride", "strava_virtual_ride",
        "strava_virtual_run", "strava_virtual_rowing", "strava_pickleball",
        "strava_padel", "strava_cricket", "strava_skateboarding",
        "strava_ice_skate", "strava_snowboard", "strava_snowshoe",
        "strava_alpine_ski", "strava_backcountry_ski", "strava_nordic_ski",
        "strava_roller_ski", "table_tennis",
    )

    fun isActivitySupported(iconId: String): Boolean = iconId in supportedActivityIconIds

    // ── OAuth Flow ───────────────────────────────────────────────────

    /**
     * Builds the Polar OAuth authorization Intent that opens the
     * browser for user consent.
     */
    fun buildAuthIntent(): Intent {
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /**
     * Exchanges the authorization code for an access token.
     * Polar AccessLink uses Basic auth (client_id:client_secret) for
     * the token exchange.
     *
     * Returns a [PolarTokens] on success, or null on failure.
     */
    suspend fun exchangeCodeForTokens(code: String): PolarTokens? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(TOKEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")

            // Polar requires Basic auth for the token endpoint
            val credentials = "$CLIENT_ID:$CLIENT_SECRET"
            val encoded = android.util.Base64.encodeToString(
                credentials.toByteArray(), android.util.Base64.NO_WRAP,
            )
            conn.setRequestProperty("Authorization", "Basic $encoded")
            conn.doOutput = true

            val body = "grant_type=authorization_code&code=$code&redirect_uri=$REDIRECT_URI"
            conn.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Token exchange failed: HTTP $responseCode")
                return@withContext null
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
            val accessToken = obj["access_token"]?.jsonPrimitive?.content ?: return@withContext null
            val userId = obj["x_user_id"]?.jsonPrimitive?.long

            PolarTokens(
                accessToken = accessToken,
                userId = userId,
            )
        }.onFailure {
            Log.w(TAG, "Token exchange error: ${it.message}")
        }.getOrNull()
    }

    /**
     * Registers the user with Polar AccessLink after first authorization.
     * This is required before you can pull daily activity data.
     * Returns true on success or if already registered (HTTP 409).
     */
    suspend fun registerUser(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(REGISTER_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            // Polar requires a member-id field when registering
            conn.outputStream.use {
                it.write("""{"member-id":"mindset-frames-user"}""".toByteArray())
            }

            val responseCode = conn.responseCode
            // 200 = registered, 409 = already registered — both are success
            responseCode in listOf(200, 409)
        }.onFailure {
            Log.w(TAG, "registerUser error: ${it.message}")
        }.getOrDefault(false)
    }

    // ── Data Reading ─────────────────────────────────────────────────

    /**
     * Reads today's step count from Polar AccessLink daily activity.
     * Returns null when the request fails or no data is available.
     *
     * Note: Polar AccessLink uses a transaction model — you must first
     * create a daily activity transaction, then list its activities.
     * For simplicity, this reads the latest available daily activity
     * summary which includes steps.
     */
    suspend fun readTodaySteps(accessToken: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(DAILY_ACTIVITY_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "readTodaySteps failed: HTTP $responseCode")
                return@withContext null
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject

            // Polar daily activity response contains "active-steps" field
            obj["active-steps"]?.jsonPrimitive?.long
                ?: obj["steps"]?.jsonPrimitive?.long
        }.onFailure {
            Log.w(TAG, "readTodaySteps error: ${it.message}")
        }.getOrNull()
    }

    /**
     * Reads today's steps and books them onto [habitId] as an ActivityRecord.
     */
    suspend fun syncTodayToHabit(
        context: Context,
        accessToken: String,
        habitId: String,
        activityType: String,
    ): Boolean {
        val steps = readTodaySteps(accessToken)
        if (steps == null) {
            Log.w(TAG, "syncTodayToHabit: no step data from Polar")
            return false
        }
        val record = ActivityRecord(
            id = UUID.randomUUID().toString(),
            habitId = habitId,
            source = "polar",
            activityType = activityType,
            timestamp = System.currentTimeMillis(),
            steps = steps,
        )
        MindsetRepository(context).saveActivityRecord(record)
        return true
    }
}

data class PolarTokens(
    val accessToken: String,
    val userId: Long?,
)
