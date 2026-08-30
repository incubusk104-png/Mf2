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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Polar AccessLink API integration — OAuth 2.0 flow + REST API.
 *
 * Free for every user. Reads daily activity data (steps) from Polar's
 * AccessLink API after the user authorises the app through Polar Flow's
 * OAuth consent screen.
 *
 * Security: the token exchange goes through the polar-token-exchange
 * Supabase Edge Function so the Polar client secret NEVER ships inside the
 * APK. A legacy direct-exchange fallback (HTTP Basic auth) remains for
 * builds that were configured with POLAR_CLIENT_SECRET before the Edge
 * Function existed.
 *
 * Setup:
 *  1. Register at https://admin.polaraccesslink.com/
 *  2. Create an API client:
 *     - Authorization Callback URL: mindsetframes://polar-callback
 *       (must match EXACTLY, scheme and host, or Polar rejects the redirect)
 *     - Scopes: accesslink.read_all (or at minimum dailyActivity)
 *  3. Set POLAR_CLIENT_ID at build time AND deploy the Edge Function:
 *       supabase functions deploy polar-token-exchange
 *       supabase secrets set POLAR_CLIENT_ID=... POLAR_CLIENT_SECRET=...
 *
 * Data model: Polar AccessLink is TRANSACTION based. You cannot simply GET
 * today's steps — you must (1) open an activity transaction for the user,
 * (2) list the new daily-activity summaries inside it, (3) read each
 * summary, then (4) commit the transaction. A transaction only ever
 * contains data that arrived since the last committed transaction, and a
 * user must be REGISTERED with the API client before any transaction works.
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
     * Legacy: Polar AccessLink OAuth client secret for the direct token
     * exchange fallback. New builds should leave this BLANK and rely on the
     * polar-token-exchange Edge Function instead, so no secret ships in the
     * APK.
     */
    val CLIENT_SECRET: String = BuildConfig.POLAR_CLIENT_SECRET

    private const val REDIRECT_URI = "mindsetframes://polar-callback"
    private const val AUTH_URL = "https://flow.polar.com/oauth2/authorization"
    private const val TOKEN_URL = "https://polarremote.com/v2/oauth2/token"
    private const val API_BASE = "https://www.polaraccesslink.com/v3"

    private val EDGE_FUNCTION_URL =
        "${BuildConfig.SUPABASE_URL.trim().trimEnd('/')}/functions/v1/polar-token-exchange"

    /**
     * True when the Polar Connect button can work. Only the PUBLIC client id
     * is required now — the secret lives server-side in the Edge Function.
     * (Previously this also demanded CLIENT_SECRET, which made every build
     * without the extra CI secret show "Polar isn't configured".)
     */
    val isConfigured: Boolean
        get() = CLIENT_ID.isNotBlank() &&
            (BuildConfig.SUPABASE_URL.isNotBlank() || CLIENT_SECRET.isNotBlank())

    /**
     * True when a Connect attempt can be made AT ALL — either the client id
     * was baked into this build, or a Supabase URL is configured so the id
     * can be discovered from the Edge Function at runtime (see
     * [resolveClientId]). This is what finally kills the
     * "Polar isn't configured for this build yet" dead end: APKs built
     * without the POLAR_CLIENT_ID CI secret still connect fine as long as
     * the polar-token-exchange function + its secrets are deployed.
     */
    val canAttemptConnect: Boolean
        get() = CLIENT_ID.isNotBlank() || BuildConfig.SUPABASE_URL.isNotBlank()

    /** Runtime-discovered client id (cached for the process lifetime). */
    @Volatile
    private var remoteClientId: String? = null

    /**
     * Resolves the PUBLIC Polar OAuth client id: build-time value first,
     * then a one-time GET to the polar-token-exchange Edge Function, which
     * returns the id it holds server-side. Returns null when neither source
     * is available (function not deployed / secrets not set).
     */
    suspend fun resolveClientId(): String? = withContext(Dispatchers.IO) {
        CLIENT_ID.takeIf { it.isNotBlank() }
            ?: remoteClientId
            ?: runCatching {
                if (BuildConfig.SUPABASE_URL.isBlank()) return@runCatching null
                val conn = URL(EDGE_FUNCTION_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                if (conn.responseCode != 200) {
                    Log.w(TAG, "Polar client-id discovery failed: HTTP ${conn.responseCode}")
                    return@runCatching null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val id = json.parseToJsonElement(body).jsonObject["client_id"]
                    ?.jsonPrimitive?.contentOrNull
                id?.takeIf { it.isNotBlank() }?.also { remoteClientId = it }
            }.onFailure {
                Log.w(TAG, "Polar client-id discovery error: ${it.message}")
            }.getOrNull()
    }

    private val json = Json { ignoreUnknownKeys = true }

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
     * browser for user consent. Pass the id from [resolveClientId] when the
     * build-time [CLIENT_ID] may be blank.
     */
    fun buildAuthIntent(clientId: String = CLIENT_ID): Intent {
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "accesslink.read_all")
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /**
     * Exchanges the authorization code for an access token.
     *
     * Primary path: the polar-token-exchange Edge Function (client secret
     * stays server-side). Fallback: legacy direct Basic-auth exchange, used
     * only when the Edge Function is unreachable AND a client secret was
     * baked into this build.
     *
     * Returns a [PolarTokens] on success, or null on failure.
     */
    suspend fun exchangeCodeForTokens(code: String): PolarTokens? = withContext(Dispatchers.IO) {
        exchangeViaEdgeFunction(code)
            ?: if (CLIENT_SECRET.isNotBlank()) exchangeDirect(code) else null
    }

    private fun exchangeViaEdgeFunction(code: String): PolarTokens? = runCatching {
        if (BuildConfig.SUPABASE_URL.isBlank()) return null
        val conn = URL(EDGE_FUNCTION_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.outputStream.use {
            it.write("""{"code":"${code.replace("\"", "")}"}""".toByteArray())
        }
        if (conn.responseCode != 200) {
            Log.w(TAG, "Edge token exchange failed: HTTP ${conn.responseCode}")
            return null
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        parseTokenResponse(body)
    }.onFailure {
        Log.w(TAG, "Edge token exchange error: ${it.message}")
    }.getOrNull()

    /** Legacy direct exchange — requires the client secret in BuildConfig. */
    private fun exchangeDirect(code: String): PolarTokens? = runCatching {
        val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        val credentials = "$CLIENT_ID:$CLIENT_SECRET"
        val encoded = android.util.Base64.encodeToString(
            credentials.toByteArray(), android.util.Base64.NO_WRAP,
        )
        conn.setRequestProperty("Authorization", "Basic $encoded")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        val body = "grant_type=authorization_code&code=$code" +
            "&redirect_uri=${Uri.encode(REDIRECT_URI)}"
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode != 200) {
            Log.w(TAG, "Direct token exchange failed: HTTP ${conn.responseCode}")
            return null
        }
        val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
        parseTokenResponse(responseBody)
    }.onFailure {
        Log.w(TAG, "Direct token exchange error: ${it.message}")
    }.getOrNull()

    private fun parseTokenResponse(body: String): PolarTokens? {
        val obj = json.parseToJsonElement(body).jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val userId = obj["x_user_id"]?.jsonPrimitive?.longOrNull
        return PolarTokens(accessToken = accessToken, userId = userId)
    }

    // ── User registration ────────────────────────────────────────────

    /**
     * Registers the user with Polar AccessLink after first authorization.
     * This is REQUIRED before any transaction endpoint works — an
     * unregistered user gets 403 on every data call.
     *
     * The member-id must be unique per user within this API client, so a
     * random UUID is used (the previous fixed "mindset-frames-user" id
     * collided across users: the second person to ever connect got 409 with
     * someone ELSE holding the registration, and all their data calls then
     * failed with 403).
     *
     * Returns true on success or if this user is already registered (409).
     */
    suspend fun registerUser(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$API_BASE/users").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            val memberId = UUID.randomUUID().toString().take(35)
            conn.outputStream.use {
                it.write("""{"member-id":"$memberId"}""".toByteArray())
            }
            val responseCode = conn.responseCode
            // 200 = registered, 409 = this user is already registered — both fine
            if (responseCode !in listOf(200, 409)) {
                Log.w(TAG, "registerUser failed: HTTP $responseCode")
            }
            responseCode in listOf(200, 409)
        }.onFailure {
            Log.w(TAG, "registerUser error: ${it.message}")
        }.getOrDefault(false)
    }

    // ── Data Reading (transaction flow) ──────────────────────────────

    /**
     * Reads the newest available daily step count through Polar's
     * transaction API:
     *
     *  1. POST /users/{userId}/activity-transactions
     *     → 201 + transaction-id (new data available) or 204 (nothing new)
     *  2. GET the transaction's activity list
     *  3. GET each daily-activity summary, keep the newest "active-steps"
     *  4. PUT (commit) the transaction so Polar can release the data
     *
     * Returns the step count, or null when there is no new data / any
     * request fails. [userId] is the numeric Polar user id captured at
     * token exchange (x_user_id).
     */
    suspend fun readLatestSteps(accessToken: String, userId: Long): Long? =
        withContext(Dispatchers.IO) {
            runCatching {
                // 1. Open a transaction.
                val txUrl = "$API_BASE/users/$userId/activity-transactions"
                val open = URL(txUrl).openConnection() as HttpURLConnection
                open.requestMethod = "POST"
                open.setRequestProperty("Authorization", "Bearer $accessToken")
                open.setRequestProperty("Accept", "application/json")
                open.connectTimeout = 15_000
                open.readTimeout = 15_000
                when (open.responseCode) {
                    201 -> Unit // new data available — continue below
                    204 -> {
                        Log.i(TAG, "No new activity data from Polar (204)")
                        return@withContext null
                    }
                    else -> {
                        Log.w(TAG, "Open transaction failed: HTTP ${open.responseCode}")
                        return@withContext null
                    }
                }
                val txBody = open.inputStream.bufferedReader().use { it.readText() }
                val txObj = json.parseToJsonElement(txBody).jsonObject
                val transactionId = txObj["transaction-id"]?.jsonPrimitive?.longOrNull
                    ?: return@withContext null

                // 2. List activity summaries inside the transaction.
                val listConn = URL("$txUrl/$transactionId").openConnection() as HttpURLConnection
                listConn.setRequestProperty("Authorization", "Bearer $accessToken")
                listConn.setRequestProperty("Accept", "application/json")
                listConn.connectTimeout = 15_000
                listConn.readTimeout = 15_000
                if (listConn.responseCode != 200) {
                    Log.w(TAG, "List transaction failed: HTTP ${listConn.responseCode}")
                    commitTransaction(txUrl, transactionId, accessToken)
                    return@withContext null
                }
                val listBody = listConn.inputStream.bufferedReader().use { it.readText() }
                val activityUrls = json.parseToJsonElement(listBody)
                    .jsonObject["activity-log"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()

                // 3. Read each summary; keep the newest active-steps value.
                var latestSteps: Long? = null
                for (activityUrl in activityUrls) {
                    val actConn = URL(activityUrl).openConnection() as HttpURLConnection
                    actConn.setRequestProperty("Authorization", "Bearer $accessToken")
                    actConn.setRequestProperty("Accept", "application/json")
                    actConn.connectTimeout = 15_000
                    actConn.readTimeout = 15_000
                    if (actConn.responseCode != 200) continue
                    val actBody = actConn.inputStream.bufferedReader().use { it.readText() }
                    val actObj = json.parseToJsonElement(actBody).jsonObject
                    val steps = actObj["active-steps"]?.jsonPrimitive?.longOrNull
                    if (steps != null) latestSteps = steps
                }

                // 4. Commit so Polar releases this batch (otherwise the same
                //    transaction blocks all future reads for 10 minutes).
                commitTransaction(txUrl, transactionId, accessToken)

                latestSteps
            }.onFailure {
                Log.w(TAG, "readLatestSteps error: ${it.message}")
            }.getOrNull()
        }

    private fun commitTransaction(txUrl: String, transactionId: Long, accessToken: String) {
        runCatching {
            val commit = URL("$txUrl/$transactionId").openConnection() as HttpURLConnection
            commit.requestMethod = "PUT"
            commit.setRequestProperty("Authorization", "Bearer $accessToken")
            commit.connectTimeout = 15_000
            commit.readTimeout = 15_000
            commit.responseCode // force execution
        }.onFailure { Log.w(TAG, "Commit transaction failed: ${it.message}") }
    }

    /**
     * Reads the latest steps and books them onto [habitId] as an
     * ActivityRecord. Returns true when new data was saved.
     */
    suspend fun syncTodayToHabit(
        context: Context,
        accessToken: String,
        userId: Long,
        habitId: String,
        activityType: String,
    ): Boolean {
        val steps = readLatestSteps(accessToken, userId)
        if (steps == null) {
            Log.w(TAG, "syncTodayToHabit: no new step data from Polar")
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
