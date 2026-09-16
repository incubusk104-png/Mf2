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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
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

    // NOTE: there is deliberately no CLIENT_SECRET here. The client secret
    // lives only in the polar-token-exchange Edge Function, so it can never
    // be read out of the shipped APK's dex. All token exchange goes through
    // that function — see [exchangeCodeForTokens].

    private const val REDIRECT_URI = "mindsetframes://polar-callback"
    private const val AUTH_URL = "https://flow.polar.com/oauth2/authorization"
    private const val TOKEN_URL = "https://polarremote.com/v2/oauth2/token"
    private const val API_BASE = "https://www.polaraccesslink.com/v3"

    private val EDGE_FUNCTION_URL =
        "${BuildConfig.SUPABASE_URL.trim().trimEnd('/')}/functions/v1/polar-token-exchange"

    /**
     * True when the Polar Connect button can work. Only the PUBLIC client id
     * (baked in, or discoverable from the Edge Function) is required — the
     * client secret is server-side and never present in this build.
     *
     * This now means exactly [canAttemptConnect]. It previously OR'd in a
     * BuildConfig secret that no longer exists, which made the two disagree:
     * a build with a Supabase URL but no baked-in client id reported
     * "configured" while `canAttemptConnect` was false, so the UI offered a
     * Connect button that could only ever fail.
     */
    val isConfigured: Boolean
        get() = canAttemptConnect

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
    val supportedActivityIconIds: Set<String> =
        com.rork.mindsetframestracker.data.SPORT_ACTIVITY_ICON_IDS

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
     * The ONLY path is the polar-token-exchange Edge Function, where the
     * client secret stays server-side. There is deliberately no direct
     * Basic-auth fallback: that variant required the secret inside the APK,
     * which is exactly the exposure this avoids. If the function is
     * unreachable the exchange fails, and the caller surfaces a real error.
     *
     * Returns a [PolarTokens] on success, or null on failure.
     */
    suspend fun exchangeCodeForTokens(code: String): PolarTokens? = withContext(Dispatchers.IO) {
        exchangeViaEdgeFunction(code)
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
     * Reads the newest available daily activity summary through Polar's
     * transaction API:
     *
     *  1. POST /users/{userId}/activity-transactions
     *     → 201 + transaction-id (new data available) or 204 (nothing new)
     *  2. GET the transaction's activity list
     *  3. GET each daily-activity summary, keep the newest one
     *  4. PUT (commit) the transaction so Polar can release the data
     *
     * Returns the summary, or null when there is no new data / any request
     * fails. [userId] is the numeric Polar user id captured at token exchange
     * (x_user_id).
     *
     * This used to return a bare `Long?` step count, reading **only**
     * `active-steps` and discarding every other field Polar sent in the same
     * response — distance, calories and active duration were fetched and then
     * thrown away, so a Polar user's Weekly and Insight views showed steps and
     * nothing else however much Polar actually reported.
     */
    suspend fun readLatestDailyActivity(accessToken: String, userId: Long): PolarDailyActivity? =
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

                // 3. Read each summary; keep the newest one by its own date.
                var latest: PolarDailyActivity? = null
                for (activityUrl in activityUrls) {
                    val actConn = URL(activityUrl).openConnection() as HttpURLConnection
                    actConn.setRequestProperty("Authorization", "Bearer $accessToken")
                    actConn.setRequestProperty("Accept", "application/json")
                    actConn.connectTimeout = 15_000
                    actConn.readTimeout = 15_000
                    if (actConn.responseCode != 200) continue
                    val actBody = actConn.inputStream.bufferedReader().use { it.readText() }
                    val actObj = json.parseToJsonElement(actBody).jsonObject
                    val date = actObj["date"]?.jsonPrimitive?.contentOrNull ?: continue
                    val steps = actObj["active-steps"]?.jsonPrimitive?.longOrNull
                    // active-calories is the activity's own burn; total-calories
                    // includes resting metabolism, so it is the wrong number for
                    // "what did this workout cost me". Prefer active, fall back.
                    val calories = actObj["active-calories"]?.jsonPrimitive?.intOrNull
                        ?: actObj["total-calories"]?.jsonPrimitive?.intOrNull
                    val distance = actObj["distance"]?.jsonPrimitive?.doubleOrNull
                    val durationMinutes = parsePolarDurationMinutes(
                        actObj["duration"]?.jsonPrimitive?.contentOrNull,
                    )
                    if (steps == null && calories == null &&
                        distance == null && durationMinutes == null
                    ) {
                        continue
                    }
                    val candidate = PolarDailyActivity(
                        date = date,
                        steps = steps,
                        calories = calories,
                        distanceMeters = distance,
                        durationMinutes = durationMinutes,
                    )
                    if (latest == null || candidate.date >= latest.date) latest = candidate
                }

                // 4. Commit so Polar releases this batch (otherwise the same
                //    transaction blocks all future reads for 10 minutes).
                commitTransaction(txUrl, transactionId, accessToken)

                latest
            }.onFailure {
                Log.w(TAG, "readLatestDailyActivity error: ${it.message}")
            }.getOrNull()
        }

    /**
     * Parses Polar's ISO-8601 duration ("PT2H30M", "PT45M") into whole minutes,
     * rounding up so a 40-second entry is never reported as "0 min".
     *
     * Returns null rather than 0 on an unparseable value: 0 would be a claim
     * that no time was spent, which is a different statement from "Polar did
     * not tell us".
     */
    private fun parsePolarDurationMinutes(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val seconds = java.time.Duration.parse(raw).seconds
            if (seconds <= 0) null else ((seconds + 59) / 60).toInt()
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
     * Reads the latest daily activity and books it onto [habitId] as an
     * ActivityRecord. Returns true when new data was saved.
     */
    suspend fun syncTodayToHabit(
        context: Context,
        accessToken: String,
        userId: Long,
        habitId: String,
        activityType: String,
    ): Boolean {
        val activity = readLatestDailyActivity(accessToken, userId)
        if (activity == null) {
            Log.w(TAG, "syncTodayToHabit: no new activity data from Polar")
            return false
        }
        val record = ActivityRecord(
            // Stable per (day, habit) rather than a fresh UUID per sync. Polar's
            // daily summary is a ROLL-UP for one calendar date, so re-reading it
            // is the same data, not a new session — and the old random id made
            // every sync append a duplicate row whose steps then got summed into
            // the user's daily and weekly totals again.
            id = "polar_${activity.date}_$habitId",
            habitId = habitId,
            source = "polar",
            activityType = activityType,
            timestamp = parsePolarDate(activity.date) ?: System.currentTimeMillis(),
            durationMinutes = activity.durationMinutes,
            distanceMeters = activity.distanceMeters,
            steps = activity.steps,
            calories = activity.calories,
        )
        // Honest result: a failed write must not be reported to the caller as a
        // successful sync. saveActivityRecord swallows its own exception and
        // returns null, so its return value is the only signal available.
        val stored = MindsetRepository(context).saveActivityRecord(record)
        if (stored == null) {
            Log.w(TAG, "Could not persist Polar activity for $habitId")
            return false
        }
        return true
    }

    /** Polar's daily `date` ("2026-09-14") as epoch millis at local midnight. */
    private fun parsePolarDate(raw: String): Long? = runCatching {
        java.time.LocalDate.parse(raw)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}

/**
 * One of Polar's daily activity roll-ups, as Polar actually reports it.
 *
 * Every field except [date] is nullable because Polar omits what it has no
 * data for, and "absent" must stay distinguishable from "zero".
 */
data class PolarDailyActivity(
    val date: String,
    val steps: Long? = null,
    val calories: Int? = null,
    val distanceMeters: Double? = null,
    val durationMinutes: Int? = null,
)

data class PolarTokens(
    val accessToken: String,
    val userId: Long?,
)
