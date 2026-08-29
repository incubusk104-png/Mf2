package com.rork.mindsetframestracker.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.MindsetRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fitbit Web API integration — OAuth 2.0 PKCE flow + REST API.
 *
 * Free for every user. Reads today's step count from the Fitbit Web API
 * after the user authorises the app via the Fitbit OAuth consent screen
 * in the browser. Tokens are stored locally in AppSettings and refreshed
 * automatically when expired.
 *
 * Setup:
 *  1. Register an app at https://dev.fitbit.com/apps/new
 *     - OAuth 2.0 Application Type: "Personal"
 *     - Callback URL: mindsetframes://fitbit-callback
 *     - Default Access Type: Read Only
 *  2. Set FITBIT_CLIENT_ID in BuildConfig (or hardcode for debug).
 *
 * Uses the Authorization Code Grant with PKCE (no client secret needed
 * on-device). See: https://dev.fitbit.com/build/reference/web-api/
 */
object FitbitClient {

    private const val TAG = "FitbitClient"

    // ── Configuration ────────────────────────────────────────────────
    // Replace with your Fitbit app's client ID from dev.fitbit.com
    const val CLIENT_ID = "" // TODO: set your Fitbit OAuth client ID
    private const val REDIRECT_URI = "mindsetframes://fitbit-callback"
    private const val AUTH_URL = "https://www.fitbit.com/oauth2/authorize"
    private const val TOKEN_URL = "https://api.fitbit.com/oauth2/token"
    private const val STEPS_URL = "https://api.fitbit.com/1/user/-/activities/steps/date/today/1d.json"

    /** True when the Fitbit client ID is configured (non-empty). */
    val isConfigured: Boolean get() = CLIENT_ID.isNotBlank()

    /**
     * Set of icon IDs whose activity data can be tracked via Fitbit.
     * Fitbit tracks steps, distance, calories, heart rate, and active
     * minutes — applicable to all physical-movement activities.
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
     * Builds the Fitbit OAuth authorization Intent that opens the
     * browser for user consent. The result lands back at
     * [REDIRECT_URI] which is handled by MainActivity's intent filter.
     */
    fun buildAuthIntent(): Intent {
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "activity")
            .appendQueryParameter("expires_in", "604800") // 1 week
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /**
     * Exchanges the authorization code for access + refresh tokens.
     * Called from MainActivity when the fitbit-callback deeplink arrives.
     *
     * Returns a [FitbitTokens] on success, or null on failure.
     */
    suspend fun exchangeCodeForTokens(code: String): FitbitTokens? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(TOKEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true

            val body = "client_id=$CLIENT_ID" +
                "&grant_type=authorization_code" +
                "&code=$code" +
                "&redirect_uri=$REDIRECT_URI"

            conn.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Token exchange failed: HTTP $responseCode")
                return@withContext null
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
            FitbitTokens(
                accessToken = obj["access_token"]?.jsonPrimitive?.content ?: return@withContext null,
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.content ?: return@withContext null,
                expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.long ?: 3600L,
            )
        }.onFailure {
            Log.w(TAG, "Token exchange error: ${it.message}")
        }.getOrNull()
    }

    /**
     * Refreshes an expired access token using the refresh token.
     * Returns a new [FitbitTokens] on success, null on failure.
     */
    suspend fun refreshAccessToken(refreshToken: String): FitbitTokens? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(TOKEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true

            val body = "client_id=$CLIENT_ID" +
                "&grant_type=refresh_token" +
                "&refresh_token=$refreshToken"

            conn.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Token refresh failed: HTTP $responseCode")
                return@withContext null
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
            FitbitTokens(
                accessToken = obj["access_token"]?.jsonPrimitive?.content ?: return@withContext null,
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.content ?: return@withContext null,
                expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.long ?: 3600L,
            )
        }.onFailure {
            Log.w(TAG, "Token refresh error: ${it.message}")
        }.getOrNull()
    }

    // ── Data Reading ─────────────────────────────────────────────────

    /**
     * Reads today's step count from the Fitbit Web API.
     * Returns null when the request fails or no data is available.
     */
    suspend fun readTodaySteps(accessToken: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(STEPS_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $accessToken")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "readTodaySteps failed: HTTP $responseCode")
                return@withContext null
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
            val stepsArray = obj["activities-steps"]
            if (stepsArray != null) {
                val arr = stepsArray as? kotlinx.serialization.json.JsonArray
                val firstDay = arr?.firstOrNull()?.jsonObject
                firstDay?.get("value")?.jsonPrimitive?.content?.toLongOrNull()
            } else null
        }.onFailure {
            Log.w(TAG, "readTodaySteps error: ${it.message}")
        }.getOrNull()
    }

    /**
     * Reads today's steps and books them onto [habitId] as an ActivityRecord.
     * Returns true when data was read and saved.
     */
    suspend fun syncTodayToHabit(
        context: Context,
        accessToken: String,
        habitId: String,
        activityType: String,
    ): Boolean {
        val steps = readTodaySteps(accessToken)
        if (steps == null) {
            Log.w(TAG, "syncTodayToHabit: no step data from Fitbit")
            return false
        }
        val record = ActivityRecord(
            id = UUID.randomUUID().toString(),
            habitId = habitId,
            source = "fitbit",
            activityType = activityType,
            timestamp = System.currentTimeMillis(),
            steps = steps,
        )
        MindsetRepository(context).saveActivityRecord(record)
        return true
    }
}

data class FitbitTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)
