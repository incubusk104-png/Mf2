package com.rork.mindsetframestracker.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.rork.mindsetframestracker.BuildConfig
import com.rork.mindsetframestracker.util.TokenCipher
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.UUID

/** Snapshot of remote data pulled after sign-in (restore flow). */
data class RemoteSnapshot(
    val habits: List<Habit>,
    val checkIns: Map<String, List<String>>,
    val moodHistory: Map<String, MoodMode>,
    /**
     * What each habit's tracking tool actually recorded. Defaults to empty so
     * a project that hasn't run the habit_logs migration yet still restores
     * cleanly rather than failing the whole pull.
     */
    val habitLogs: List<HabitLogEntry> = emptyList(),
    /** Activity sessions previously uploaded from any device. */
    val activityRecords: List<ActivityRecord> = emptyList(),
)

/**
 * What a single table upsert did, so the caller can report the true state of a
 * backup instead of one opaque failure string.
 *
 * [Degraded] and [MissingTable] both mean data did NOT reach the server. They are
 * kept apart because the remedy differs: a missing column leaves a field out of
 * a row that otherwise landed, whereas a missing table is an entire payload the
 * project cannot accept yet. [Failed] is everything else (a constraint, an RLS
 * policy, a 5xx) and already carries a message safe to show the user.
 */
private sealed interface UpsertOutcome {
    /** Nothing to send, or every row landed. */
    object Ok : UpsertOutcome

    /**
     * Rows landed, but [missing] fields the live schema lacks were left out of
     * them. Names are qualified (`habits.alarm_times`) because the summary
     * collapses several tables into one sentence.
     */
    data class Degraded(val missing: List<String>) : UpsertOutcome

    /** The live project has no such table at all. */
    data class MissingTable(val table: String) : UpsertOutcome

    /** Anything else, with a message safe to show the user. */
    data class Failed(val message: String) : UpsertOutcome
}

/**
 * Cloud backup & sync against the user's own Supabase project.
 *
 * - Auth: Supabase GoTrue REST (email/password sign-up, sign-in, sign-out,
 *   password recovery) with the session persisted in SharedPreferences and
 *   automatic refresh on 401. Session tokens are sealed with a hardware-backed
 *   Android Keystore key (see [TokenCipher]) so they are never stored in
 *   plaintext on disk.
 * - Data: PostgREST upserts scoped by a stable per-install device_id; when the
 *   user is signed in, rows also carry user_id and requests use the user's
 *   access token so authenticated RLS policies apply.
 *
 * Credentials come from BuildConfig (SUPABASE_URL, SUPABASE_ANON_KEY). When
 * absent the service reports unavailable and the app stays fully local.
 */
class SupabaseSync(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("supabase_sync", Context.MODE_PRIVATE)

    private val baseUrl: String = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY.trim()

    /** True when Supabase credentials are configured. */
    val isConfigured: Boolean = baseUrl.startsWith("https://") && anonKey.isNotBlank()

    /** Stable per-install identifier used to scope rows for pre-auth pushes. */
    val deviceId: String =
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /** Email of the signed-in user, or null when signed out. */
    val sessionEmail: String? get() = prefs.getString(KEY_EMAIL, null)

    /** Auth provider of the active session — "huawei" or "email" — null when signed out. */
    val sessionProvider: String? get() = prefs.getString(KEY_PROVIDER, null)

    /** Epoch millis of the last successful cloud backup, 0 when never synced. */
    val lastSyncAtMs: Long get() = prefs.getLong(KEY_LAST_SYNC, 0L)

    /** True when a user session is active. Strict RLS denies all anon writes. */
    val isSignedIn: Boolean get() = sessionUserId != null && prefs.contains(KEY_ACCESS_TOKEN)

    /**
     * Best-effort connectivity check. Defaults to true when the system service
     * is unavailable so a broken check can never block a sync attempt.
     */
    val isOnline: Boolean
        get() {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    /**
     * Persisted marker for local changes that haven't reached the cloud yet
     * (e.g. edits made offline). Survives process death so the next launch
     * can retry the backup automatically. Local data itself is always safe —
     * it lives on-device regardless of this flag.
     */
    var hasPendingPush: Boolean
        get() = prefs.getBoolean(KEY_PENDING_PUSH, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PENDING_PUSH, value).apply()
        }

    /**
     * Habit ids that were deleted locally but not yet deleted from Supabase.
     * Persisted (not just in-memory) so a deletion made while offline, or
     * right before the app is killed, is still applied on the next sync
     * instead of being silently lost — which is what let deleted habits
     * "come back" after a restore or on another device.
     */
    private var pendingDeletedHabitIds: Set<String>
        get() = prefs.getStringSet(KEY_PENDING_DELETES, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_PENDING_DELETES, value).apply()
        }

    /** Marks a habit for deletion on the next sync. Call this at delete time. */
    fun queueHabitDeletion(habitId: String) {
        pendingDeletedHabitIds = pendingDeletedHabitIds + habitId
    }

    private var lastSignUpIdentitiesWasEmpty = false

    private val sessionUserId: String? get() = prefs.getString(KEY_USER_ID, null)
    private val accessToken: String? get() =
        prefs.getString(KEY_ACCESS_TOKEN, null)?.let(TokenCipher::open)
    /**
     * True when the last push landed its rows but could not store everything —
     * a table or column the live project does not have.
     *
     * Kept as state on the sync object rather than folded into [pushSnapshot]'s
     * return value, because that value is a plain String used as an error by
     * every caller (including the WorkManager job). A partial result must not
     * make `pushSnapshot` look like a failure there, but the UI still needs to
     * be able to tell the two apart. Reset at the start of every push.
     */
    var lastPushPartial: Boolean = false
        private set

    private val refreshToken: String? get() =
        prefs.getString(KEY_REFRESH_TOKEN, null)?.let(TokenCipher::open)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    }

    init {
        migrateTokenStorage()
    }

    /**
     * One-time upgrade path: seals any legacy plaintext session tokens with
     * the Keystore key, and drops a session whose sealed tokens can no longer
     * be decrypted (e.g. after a keystore reset) so the app cleanly asks the
     * user to sign in again instead of failing every request.
     */
    private fun migrateTokenStorage() {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return
        if (!TokenCipher.isSealed(access)) {
            val editor = prefs.edit().putString(KEY_ACCESS_TOKEN, TokenCipher.seal(access))
            prefs.getString(KEY_REFRESH_TOKEN, null)
                ?.takeUnless(TokenCipher::isSealed)
                ?.let { editor.putString(KEY_REFRESH_TOKEN, TokenCipher.seal(it)) }
            editor.apply()
        } else if (TokenCipher.open(access) == null) {
            Log.w(TAG, "Stored session tokens are unrecoverable — signing out")
            signOut()
        }
    }

    // ── Auth ─────────────────────────────────────────────────────────

    @Serializable
    private data class AuthCredentials(val email: String, val password: String)

    @Serializable
    private data class AuthIdentity(val id: String? = null)

    @Serializable
    private data class AuthUser(
        val id: String = "",
        val email: String? = null,
        val identities: List<AuthIdentity>? = null,
    )

    @Serializable
    private data class AuthSession(
        val access_token: String? = null,
        val refresh_token: String? = null,
        val user: AuthUser? = null,
    )

    @Serializable
    private data class RecoverBody(val email: String)

    @Serializable
    private data class RefreshBody(val refresh_token: String)

    @Serializable
    private data class UpdatePasswordBody(val password: String)

    /** Signs in with email/password. Returns null on success or an error message. */
    suspend fun signIn(email: String, password: String): String? {
        if (!isConfigured) return "Cloud sync is not configured"
        return try {
            val response = client.post("$baseUrl/auth/v1/token?grant_type=password") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(AuthCredentials(email.trim(), password))
            }
            if (!response.status.isSuccess()) authError(response)
            else saveSession(response.body<AuthSession>(), provider = "email")
        } catch (e: Exception) {
            Log.w(TAG, "Sign-in failed: ${e.message}")
            "Couldn't reach the server. Check your connection and try again."
        }
    }

    @Serializable
    private data class HuaweiExchangeBody(val idToken: String)

    /**
     * Exchanges a HUAWEI ID sign-in for a Supabase session.
     *
     * Supabase has no built-in Huawei OAuth provider, so the Huawei-signed
     * ID token is sent to the `huawei-auth` edge function, which has Huawei's
     * account server verify it (issuer, audience, expiry) and only then
     * provisions the internal account and returns a session. All credential
     * derivation happens server-side with a server-only secret — nothing in
     * this APK can mint, guess, or replay account credentials.
     *
     * The identity is keyed to the verified Huawei subject, so users keep
     * their cloud data even if they change or hide their Huawei email later.
     *
     * Returns null on success or a user-facing error message.
     */
    suspend fun signInWithHuawei(idToken: String, email: String?, displayName: String? = null): String? {
        if (!isConfigured) return "Cloud sync is not configured"
        if (idToken.isBlank()) return "Huawei sign-in failed — no credential returned. Try again."
        return try {
            val response = client.post("$baseUrl/functions/v1/huawei-auth") {
                header("apikey", anonKey)
                header(HttpHeaders.Authorization, "Bearer $anonKey")
                contentType(ContentType.Application.Json)
                setBody(HuaweiExchangeBody(idToken))
            }
            if (!response.status.isSuccess()) {
                Log.w(TAG, "Huawei exchange failed: ${response.status}")
                return when (response.status) {
                    HttpStatusCode.Unauthorized ->
                        "Huawei couldn't verify this sign-in. Try again."
                    else ->
                        "Couldn't connect your Huawei account (${response.status.value}). Try again."
                }
            }
            val session = response.body<AuthSession>()
            saveSession(session, provider = "huawei")?.let { return it }
            prefs.edit()
                .putString(
                    KEY_EMAIL,
                    email?.takeIf { it.isNotBlank() }
                        ?: displayName?.takeIf { it.isNotBlank() }
                        ?: "Huawei ID",
                )
                .apply()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Huawei sign-in failed: ${e.message}")
            "Couldn't reach the server. Check your connection and try again."
        }
    }

    /**
     * Creates an account. Returns null when the account is ready (session
     * active), or a message — either an error or "confirm your email" info.
     *
     * GoTrue's /signup response has TWO different shapes:
     *  - Email confirmation DISABLED → a session envelope:
     *    { "access_token": ..., "user": { "id": ..., "identities": [...] } }
     *  - Email confirmation ENABLED  → the bare user object at the TOP level:
     *    { "id": ..., "email": ..., "identities": [...] }  (no access_token)
     *
     * The previous implementation only understood the first shape, so with
     * confirmations enabled every brand-new sign-up parsed as user == null
     * and was wrongly reported as "already registered". Both shapes are now
     * handled, and the real duplicate signal — GoTrue returning an obfuscated
     * user whose "identities" array is EMPTY — is checked on whichever shape
     * came back.
     */
    suspend fun signUp(email: String, password: String): String? {
        if (!isConfigured) return "Cloud sync is not configured"
        lastSignUpIdentitiesWasEmpty = false
        return try {
            val response = client.post("$baseUrl/auth/v1/signup") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(AuthCredentials(email.trim(), password))
            }
            if (!response.status.isSuccess()) {
                // With confirmations disabled, a duplicate sign-up surfaces as
                // a 400/422 "User already registered" error instead of the
                // empty-identities marker — normalize it to the same message.
                val error = authError(response)
                val normalized = error.lowercase()
                return if ("already registered" in normalized || "already exists" in normalized ||
                    "user_already_exists" in normalized
                ) {
                    lastSignUpIdentitiesWasEmpty = true
                    "This email is already registered. Please log in instead."
                } else {
                    error
                }
            }

            val bodyText = response.bodyAsText()
            val root = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
                ?: return "Sign-up failed — unexpected server response. Try again."

            val accessToken = root["access_token"]?.jsonPrimitive?.contentOrNull
            // The user object is nested when a session is returned, top-level otherwise.
            val userObject = (root["user"] as? JsonObject) ?: root
            val userId = userObject["id"]?.jsonPrimitive?.contentOrNull
            val identities = userObject["identities"] as? JsonArray

            // GoTrue signals "this email already has a CONFIRMED account" by
            // returning an obfuscated user whose identities array is empty.
            // (identities == null is NOT a duplicate — it simply wasn't selected.)
            val isDuplicate = userId.isNullOrBlank() || (identities != null && identities.isEmpty())
            if (isDuplicate) {
                lastSignUpIdentitiesWasEmpty = true
                return "This email is already registered. Please log in instead."
            }

            if (accessToken.isNullOrBlank()) {
                "Account created — check $email to confirm, then sign in."
            } else {
                saveSession(json.decodeFromString(AuthSession.serializer(), bodyText), provider = "email")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sign-up failed: ${e.message}")
            "Couldn't reach the server. Check your connection and try again."
        }
    }

    fun lastSignUpIdentitiesEmpty(): Boolean = lastSignUpIdentitiesWasEmpty

    @Serializable
    private data class ResendBody(val type: String, val email: String)

    /**
     * Re-sends the sign-up confirmation email for an unconfirmed account
     * (GoTrue /resend). Returns null on success or an error message. Rate
     * limited server-side, so repeated taps are safe.
     */
    suspend fun resendSignupConfirmation(email: String): String? {
        if (!isConfigured) return "Cloud sync is not configured"
        if (email.isBlank()) return "Enter your email first"
        return try {
            val response = client.post("$baseUrl/auth/v1/resend") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(ResendBody(type = "signup", email = email.trim()))
            }
            if (response.status.isSuccess()) null else authError(response)
        } catch (e: Exception) {
            Log.w(TAG, "Resend confirmation failed: ${e.message}")
            "Couldn't reach the server. Check your connection and try again."
        }
    }

    /**
     * Completes the email-confirmation web-bridge: the site forwards the
     * verified Supabase tokens via deep link, and the app exchanges the
     * one-time refresh token for a fresh session. Supabase rotates refresh
     * tokens server-side, so a replayed link can never mint a second
     * session. Returns null on success or a user-facing error message.
     */
    suspend fun signInWithRecoveredToken(refreshToken: String): String? {
        if (!isConfigured) return "Cloud sync is not configured"
        if (refreshToken.isBlank()) return "This link is missing its credential. Sign in manually."
        return try {
            val response = client.post("$baseUrl/auth/v1/token?grant_type=refresh_token") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(RefreshBody(refreshToken))
            }
            if (!response.status.isSuccess()) {
                Log.w(TAG, "Bridge token exchange failed: ${response.status}")
                "This link has expired or was already used. Sign in to request a new one."
            } else {
                saveSession(response.body<AuthSession>(), provider = "email")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bridge sign-in failed: ${e.message}")
            "Couldn't reach the server. Check your connection and try again."
        }
    }

    /**
     * One-shot guard for auth deep links: records the link signature and
     * returns true only the first time it is seen. Re-delivered intents
     * (task re-parenting, history relaunch) are silently ignored, so a
     * link can never trigger a second sign-in attempt or a UI loop.
     */
    fun consumeAuthLink(signature: String): Boolean {
        if (signature.isBlank()) return false
        val previous = prefs.getString(KEY_CONSUMED_AUTH_LINK, null)
        if (previous == signature) return false
        prefs.edit().putString(KEY_CONSUMED_AUTH_LINK, signature).apply()
        return true
    }

    /** Sends a password-recovery email. Returns null on success. */
    suspend fun sendPasswordReset(email: String): String? {
        if (!isConfigured) return "Cloud sync is not configured"
        if (email.isBlank()) return "Enter your email first"
        return try {
            val response = client.post("$baseUrl/auth/v1/recover") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(RecoverBody(email.trim()))
            }
            if (response.status.isSuccess()) null else authError(response)
        } catch (e: Exception) {
            Log.w(TAG, "Recover failed: ${e.message}")
            "Couldn't reach the server. Check your connection and try again."
        }
    }

    /**
     * Updates the signed-in user's password via Supabase GoTrue. The caller
     * (ViewModel.changePassword) re-verifies the current password with a
     * fresh sign-in before calling this. Returns null on success or a
     * user-facing error message.
     */
    suspend fun updatePassword(newPassword: String): String? {
        if (!isConfigured) return "Cloud sync is not configured"
        if (accessToken == null) return "You're not signed in."
        return try {
            var response = updatePasswordRequest(newPassword)
            if (response.status == HttpStatusCode.Unauthorized && tryRefreshSession()) {
                response = updatePasswordRequest(newPassword)
            }
            if (response.status.isSuccess()) null else authError(response)
        } catch (e: Exception) {
            Log.w(TAG, "Update password failed: ${e.message}")
            "Couldn't reach the server. Check your connection and try again."
        }
    }

    private suspend fun updatePasswordRequest(newPassword: String): HttpResponse =
        client.put("$baseUrl/auth/v1/user") {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer ${accessToken ?: anonKey}")
            contentType(ContentType.Application.Json)
            setBody(UpdatePasswordBody(newPassword))
        }

    /**
     * Permanently deletes the signed-in user's account server-side via the
     * delete_user RPC: every owned row plus the
     * auth user itself are erased in one transaction, then the local session
     * is cleared. Local on-device data is untouched.
     * Returns null on success or a user-facing error message.
     */
    suspend fun deleteAccount(): String? {
        if (!isConfigured) return "Cloud sync is not configured"
        if (accessToken == null) return "You're not signed in."
        return try {
            var response = deleteAccountRequest()
            if (response.status == HttpStatusCode.Unauthorized && tryRefreshSession()) {
                response = deleteAccountRequest()
            }
            when {
                response.status.isSuccess() -> {
                    hasPendingPush = false
                    signOut()
                    null
                }
                response.status == HttpStatusCode.NotFound ->
                    "Account deletion isn't available on the server yet. Try again later or contact support."
                else -> {
                    Log.w(TAG, "Delete account failed: ${response.status} ${response.bodyAsText().take(200)}")
                    "Couldn't delete the account (${response.status.value}). Try again."
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Delete account failed: ${e.message}")
            "Couldn't reach the server. Check your connection and try again."
        }
    }

    private suspend fun deleteAccountRequest(): HttpResponse =
        client.post("$baseUrl/rest/v1/rpc/delete_user") {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer ${accessToken ?: anonKey}")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

    @Serializable
    private data class HabitRecommendBody(
        val existing_habits: List<String>,
        val mood: String? = null,
        val activity_summary: String? = null,
        val context_type: String? = null,
    )

    @Serializable
    data class RemoteHabitSuggestion(val name: String, val reason: String)

    @Serializable
    private data class HabitRecommendResponse(val suggestions: List<RemoteHabitSuggestion>? = null)

    /**
     * Calls the habit-recommend edge function (server-side Gemini free-tier
     * proxy — see backend/functions/habit-recommend). Returns null on ANY
     * failure — not signed in, network error, daily quota hit, bad response
     * — so the caller can fall back to the on-device HabitRecommender
     * without ever surfacing an error to the user.
     */
    suspend fun getAiHabitSuggestions(
        existingHabitNames: List<String>,
        mood: String? = null,
        activitySummary: String? = null,
        contextType: String? = null,
    ): List<RemoteHabitSuggestion>? {
        if (!isConfigured || accessToken == null) return null
        return try {
            val response = client.post("$baseUrl/functions/v1/habit-recommend") {
                header("apikey", anonKey)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(HabitRecommendBody(
                    existing_habits = existingHabitNames,
                    mood = mood,
                    activity_summary = activitySummary,
                    context_type = contextType,
                ))
            }
            if (!response.status.isSuccess()) {
                Log.i(TAG, "AI habit recommend unavailable: ${response.status}")
                return null
            }
            response.body<HabitRecommendResponse>().suggestions
        } catch (e: Exception) {
            Log.w(TAG, "AI habit recommend failed: ${e.message}")
            null
        }
    }

    /** Clears the local session. */
    fun signOut() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EMAIL)
            .remove(KEY_USER_ID)
            .remove(KEY_PROVIDER)
            .remove(KEY_LAST_SYNC)
            .apply()
    }

    /**
     * Persists a session with both tokens sealed via the Android Keystore;
     * returns null on success or an error message.
     */
    private fun saveSession(session: AuthSession, provider: String? = null): String? {
        val token = session.access_token ?: return "Sign-in failed — no session returned."
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, TokenCipher.seal(token))
            .putString(KEY_REFRESH_TOKEN, session.refresh_token?.let(TokenCipher::seal))
            .putString(KEY_USER_ID, session.user?.id)
        if (provider != null) {
            editor.putString(KEY_PROVIDER, provider)
            editor.putString(KEY_EMAIL, session.user?.email)
        }
        editor.apply()
        return null
    }

    /** Tries to refresh the access token. Returns true on success. */
    private suspend fun tryRefreshSession(): Boolean {
        val refresh = refreshToken ?: return false
        return try {
            val response = client.post("$baseUrl/auth/v1/token?grant_type=refresh_token") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(RefreshBody(refresh))
            }
            response.status.isSuccess() && saveSession(response.body<AuthSession>()) == null
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh failed: ${e.message}")
            false
        }
    }

    /** Extracts a friendly message from a GoTrue error response. */
    private suspend fun authError(response: HttpResponse): String {
        val body = response.bodyAsText()
        val parsed = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            (obj["msg"] ?: obj["message"] ?: obj["error_description"] ?: obj["error"])
                ?.jsonPrimitive?.content
        }.getOrNull()
        Log.w(TAG, "Auth error ${response.status}: ${body.take(200)}")
        return when {
            !parsed.isNullOrBlank() -> parsed
            response.status == HttpStatusCode.BadRequest -> "Invalid email or password."
            else -> "Request failed (${response.status.value}). Try again."
        }
    }

    // ── Tip purchases (server-side record + verification) ───────────

    @Serializable
    private data class TipPurchaseBody(
        val purchaseData: String,
        val signature: String? = null,
        val userId: String? = null,
    )

    /**
     * Reports a completed Huawei IAP tip purchase to the tip-purchase Edge
     * Function, which verifies it with Huawei's Order Service (when the
     * server credentials are configured) and records it in tip_purchases.
     * Fire-and-forget: a network failure never blocks the on-device thank-you
     * flow — the purchase itself already succeeded through Huawei.
     */
    suspend fun recordTipPurchase(purchaseData: String, signature: String?): Boolean {
        if (!isConfigured || purchaseData.isBlank()) return false
        return try {
            val response = client.post("$baseUrl/functions/v1/tip-purchase") {
                header("apikey", anonKey)
                header(HttpHeaders.Authorization, "Bearer ${accessToken ?: anonKey}")
                contentType(ContentType.Application.Json)
                setBody(
                    TipPurchaseBody(
                        purchaseData = purchaseData,
                        signature = signature,
                        userId = sessionUserId ?: deviceId,
                    ),
                )
            }
            response.status.isSuccess().also { ok ->
                if (!ok) Log.i(TAG, "tip-purchase record failed: ${response.status}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "tip-purchase record error: ${e.message}")
            false
        }
    }

    // ── Founding-member eligibility (cap enforcement) ──────────────

    /**
     * Three-state result of the pre-render eligibility check.
     *
     * [Unavailable] is deliberately distinct from [NotEligible]: the premium
     * sheet must fail CLOSED (hide the Founding Member card) when the check
     * cannot be completed, and it can only do that if "the server said no"
     * and "the server said nothing" are not collapsed into one value.
     *
     * [region] and [cap] describe WHICH bucket was measured and how big it is.
     * The cap is per country/region (500 by default), so it is not a constant
     * the client may assume — it comes back from the server with every answer.
     * [soldOut] is the server's own read on whether this region's target is
     * met, which is what lets the sheet hide the card rather than grey it out.
     */
    sealed interface FoundingEligibility {
        data class Eligible(
            val remaining: Int,
            val region: String,
            val cap: Int,
        ) : FoundingEligibility

        data class NotEligible(
            val remaining: Int,
            val alreadyClaimed: Boolean,
            val region: String,
            val cap: Int,
            val soldOut: Boolean,
        ) : FoundingEligibility

        data object Unavailable : FoundingEligibility
    }

    @Serializable
    private data class FoundingEligibilityBody(
        val eligible: Boolean = false,
        val claimed: Boolean = false,
        val remaining: Int = 0,
        val cap: Int = 0,
        val region: String = "",
        val soldOut: Boolean = false,
    )

    /**
     * The claim (POST) response. `charged` is the field that matters: it is true
     * only when THIS call consumed a founding slot, so it — and not `claimed`,
     * which is also true for a retry of an existing claim — is what decides
     * whether the user earned a slot just now.
     */
    @Serializable
    private data class FoundingClaimResponse(
        val claimed: Boolean = false,
        val charged: Boolean = false,
        val reason: String = "",
        val remaining: Int = 0,
        val cap: Int = 0,
        val region: String = "",
    )

    @Serializable
    private data class FoundingClaimBody(
        val user_id: String,
        val country: String = "",
        val plan_id: String = "",
        /** The `productId` Huawei reported for the completed order. */
        val product_id: String = "",
        /**
         * The `inAppPurchaseData` JSON string and its signature, exactly as
         * Huawei returned them in the purchase-result Intent.
         *
         * These are the payment evidence. The server re-parses `purchase_data`
         * to read the purchase token, then asks Huawei's Order Service whether
         * that token is a completed, paid order — so these two fields are what
         * stand between a founding slot and a claim that was never paid for.
         * Empty is treated as "no evidence" and refused server-side.
         */
        val purchase_data: String = "",
        val signature: String = "",
    )

    /**
     * The region bucket this install's claims are counted against.
     *
     * Mirrors the SQL function founding_member_region() and regionFor() in the
     * founding-member-eligibility edge function: upper-cased, trimmed, and
     * folded to [UNKNOWN_REGION] when there is nothing to work with. Keep the
     * three copies in step — if the client reports a different bucket than the
     * server counts, a user can be shown the card for a region that is already
     * full (or hidden from one that still has room).
     *
     * Locale.getDefault().country is the SIM-free device region, which is the
     * right signal here: it follows the user's chosen language/region rather
     * than wherever their carrier happens to be registered.
     */
    fun currentRegion(): String {
        val trimmed = Locale.getDefault().country.orEmpty().trim().uppercase()
        return trimmed.ifEmpty { UNKNOWN_REGION }
    }

    /**
     * Asks the founding-member-eligibility Edge Function whether this install
     * may still claim one of its region's founding slots.
     *
     * The cap is PER COUNTRY/REGION (500 by default), so the country is sent
     * with the request — without it the server would measure every caller
     * against the same catch-all bucket. The server stays the source of truth:
     * this call only decides whether the Founding Member card is worth
     * rendering. Every failure mode (not configured, offline, non-2xx,
     * malformed body, thrown request) returns [FoundingEligibility.Unavailable]
     * so the caller hides the card instead of offering a purchase that may no
     * longer be claimable.
     */
    suspend fun checkFoundingMemberEligibility(): FoundingEligibility {
        if (!isConfigured) return FoundingEligibility.Unavailable
        return try {
            val response = client.get("$baseUrl/functions/v1/founding-member-eligibility") {
                header("apikey", anonKey)
                header(HttpHeaders.Authorization, "Bearer ${accessToken ?: anonKey}")
                parameter("user_id", sessionUserId ?: deviceId)
                parameter("country", currentRegion())
            }
            if (!response.status.isSuccess()) {
                Log.i(TAG, "founding eligibility check failed: ${response.status}")
                return FoundingEligibility.Unavailable
            }
            val parsed = response.body<FoundingEligibilityBody>()
            if (parsed.eligible) {
                FoundingEligibility.Eligible(
                    remaining = parsed.remaining,
                    region = parsed.region,
                    cap = parsed.cap,
                )
            } else {
                FoundingEligibility.NotEligible(
                    remaining = parsed.remaining,
                    alreadyClaimed = parsed.claimed,
                    region = parsed.region,
                    cap = parsed.cap,
                    soldOut = parsed.soldOut,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "founding eligibility error: ${e.message}")
            FoundingEligibility.Unavailable
        }
    }

    /**
     * Records a founding-member claim after a successful founding-tier
     * purchase, mirroring [recordTipPurchase] exactly: fire-and-forget, so a
     * network failure never blocks the on-device "Premium unlocked" flow —
     * Huawei already completed the payment.
     *
     * Safe to call more than once for the same install. The server's
     * claim_founding_member() re-reads the row under an advisory lock and
     * reports the existing claim rather than consuming a second slot.
     */
    suspend fun recordFoundingMemberClaim(
        planId: String,
        productId: String,
        purchaseData: String,
        signature: String?,
    ): Boolean {
        if (!isConfigured) return false
        return try {
            val response = client.post("$baseUrl/functions/v1/founding-member-eligibility") {
                header("apikey", anonKey)
                header(HttpHeaders.Authorization, "Bearer ${accessToken ?: anonKey}")
                contentType(ContentType.Application.Json)
                setBody(
                    FoundingClaimBody(
                        user_id = sessionUserId ?: deviceId,
                        country = currentRegion(),
                        plan_id = planId,
                        product_id = productId,
                        purchase_data = purchaseData,
                        signature = signature.orEmpty(),
                    ),
                )
            }
            if (!response.status.isSuccess()) {
                // 402 = Huawei says the order is not a completed payment;
                // 503 = it could not be checked. Both mean no slot was charged.
                // The entitlement is NOT revoked here — the user already paid —
                // so this must be logged loudly enough for support to reconcile.
                val reason = runCatching { response.bodyAsText() }.getOrNull()
                Log.i(TAG, "founding-member claim refused: ${response.status} ${reason?.take(200)}")
                false
            } else {
                response.body<FoundingClaimResponse>().charged
            }
        } catch (e: Exception) {
            Log.w(TAG, "founding-member claim record error: ${e.message}")
            false
        }
    }

    // ── Data sync ────────────────────────────────────────────────────

    @Serializable
    private data class HabitRow(
        val id: String,
        val user_id: String? = null,
        val name: String,
        val created_at_ms: Long = 0L,
        val icon_id: String? = null,
        val reminder_minutes: Int? = null,
        /**
         * Every alarm time for this habit, minutes from midnight.
         *
         * Kept alongside [reminder_minutes] rather than replacing it: the legacy
         * column is still written (as the first entry) so an older client can
         * still restore a working single alarm, while this one carries the full
         * schedule the current client re-arms from.
         *
         * Defaulted to empty rather than made required, because an un-migrated
         * project returns no such column and PostgREST omits it — a non-null
         * default keeps deserialization working against the old schema, which is
         * what lets this client ship before the migration is applied.
         */
        val alarm_times: List<Int> = emptyList(),
        /**
         * The habit's motivational reminder line, or null for the curated
         * default.
         *
         * Nullable **with a default** deliberately: on a project whose `habits`
         * table has not yet had the companion migration applied, this field is
         * reported as a dropped field for this sync pass (see the missing-table /
         * dropped-column handling below) and the user's habits still sync — the
         * exact same degrade-gracefully path `alarm_times` takes when it is
         * absent. A non-default field here would instead fail the whole habit
         * upsert and lose every other edit in the batch.
         */
        val alarm_message: String? = null,
        val is_pinned: Boolean = false,
        val duration_seconds: Int? = null,
        val repeat_days_mask: Int = REPEAT_DAILY,
        val monitored_package: String? = null,
        val screen_time_limit_minutes: Int? = null,
        val monitored_app_label: String? = null,
        /**
         * The per-habit tracker links, as provider names.
         *
         * ## Why the link syncs but the *connection* does not
         *
         * The two are different kinds of fact. A connection (an OAuth token) is
         * bound to this device's authorisation and is deliberately **never**
         * uploaded — only the fact that a provider is connected is stored, never
         * the credential. A *link* is a piece of the user's own configuration:
         * "this Walk habit gets its data from Strava". Losing it on restore or on
         * a second device is exactly the same class of data loss as losing the
         * habit's name, so it belongs in the snapshot.
         *
         * The consequence is deliberate and worth stating: a restored link on a
         * device with no matching connection is harmless. The row reads "Link"
         * (the connection is absent), and `HabitTrackerLinks` refuses every
         * import until the user authorises that provider here — no data is
         * written and no orphaned record is created.
         *
         * Defaulted to empty so an un-migrated project (no such column yet)
         * degrades exactly as `alarm_message` does: the field is reported as
         * dropped for that pass and every other habit edit still syncs, rather
         * than the whole batch being lost because one column is absent.
         */
        val tracker_provider_ids: List<String> = emptyList(),
    )

    @Serializable
    private data class CheckinRow(
        val user_id: String? = null,
        val habit_id: String,
        val day: String,
    )

    @Serializable
    private data class MoodLogRow(
        val user_id: String? = null,
        val day: String,
        val mode: String,
    )

    @Serializable
    private data class SettingsRow(
        val id: String,
        val device_id: String,
        val user_id: String? = null,
        val payload: AppSettings,
    )

    /**
     * One [HabitLogEntry]. Field names mirror the `habit_logs` table exactly,
     * so PostgREST can upsert this straight through.
     */
    @Serializable
    private data class HabitLogRow(
        val id: String,
        val user_id: String? = null,
        val device_id: String? = null,
        val habit_id: String,
        val day: String,
        val mode: String? = null,
        val title: String? = null,
        val note: String? = null,
        val count: Int? = null,
        val unit: String? = null,
        val duration_seconds: Int? = null,
        val logged_at_ms: Long = 0L,
        /**
         * The alarm occurrence this record answers (`<day>@<minutes>`), or null
         * for an unprompted record.
         *
         * Round-tripped verbatim rather than recomputed on pull: the occurrence
         * is a fact about when the alarm actually rang on the device, and
         * deriving it from `day` + `mode` after the fact would silently relabel
         * a 07:00 entry as whatever the habit's current first alarm happens to be.
         */
        val occurrence_key: String? = null,
    )

    /**
     * One device-captured [ActivityRecord], written into the EXISTING
     * `activity_data` table that `smart-alarms` and `consistency-report`
     * already read.
     *
     * Two deliberate choices:
     * - `provider` is 'health_connect' (currently the only device-side
     *   source), and `provider_activity_id` is the record's own id, which is
     *   what makes the upload idempotent against the table's
     *   UNIQUE (user_id, provider, provider_activity_id).
     * - `connection_id` is omitted: a locally-captured record has no
     *   third-party OAuth connection, and the column is nullable for exactly
     *   this case.
     */
    @Serializable
    private data class ActivityRow(
        val user_id: String? = null,
        val provider: String,
        val provider_activity_id: String,
        val activity_type: String,
        val activity_name: String? = null,
        /** OffsetDateTime, in the format Strava sends. */
        val started_at: String,
        val ended_at: String? = null,
        val activity_date: String,
        val duration_seconds: Int? = null,
        val distance_meters: Double? = null,
        val calories_burned: Double? = null,
        val heart_rate_avg: Int? = null,
        val heart_rate_max: Int? = null,
        val steps: Int? = null,
        val elevation_gain_meters: Double? = null,
        val raw_data: JsonObject? = null,
    )

    /**
     * Upserts the full local snapshot into Supabase. Returns null on success
     * or a short user-facing error message on failure.
     */
    suspend fun pushSnapshot(data: AppData): String? {
        if (!isConfigured) return "Supabase is not configured"
        val uid = sessionUserId ?: return "Sign in first to back up your data"
        if (accessToken == null) return "Sign in first to back up your data"
        // Reset before every attempt so a previous partial result can never be
        // reported against a later, fully-successful push.
        lastPushPartial = false
        return try {
            val habits = data.habits.map {
                HabitRow(
                    id = it.id,
                    user_id = uid,
                    name = it.name,
                    created_at_ms = it.createdAt,
                    icon_id = it.iconId,
                    alarm_message = it.alarmMessage,
                    // Both spellings go up, normalised by the ONE shared rule
                    // (legacyAlarmTimes): the legacy column mirrors the first
                    // entry, and an empty list falls back to it. The pull path
                    // below applies the same function, which is what stops push
                    // and pull from reinterpreting one another for a habit that
                    // only ever had a single `reminder_minutes`.
                    reminder_minutes = it.reminderMinutes,
                    alarm_times = legacyAlarmTimes(it.alarmTimes, it.reminderMinutes),
                    is_pinned = it.isPinned,
                    duration_seconds = it.durationSeconds,
                    repeat_days_mask = it.repeatDaysMask,
                    monitored_package = it.monitoredPackage,
                    screen_time_limit_minutes = it.screenTimeLimitMinutes,
                    monitored_app_label = it.monitoredAppLabel,
                    // Sorted into canonical provider order, so the same link set
                    // never produces two different column values (which would
                    // make an otherwise-identical push look like a change and
                    // keep re-writing the row).
                    tracker_provider_ids = com.rork.mindsetframestracker.integrations.HabitTrackerLinks.sortProviderIds(it.trackerProviderIds),
                )
            }
            // Defense in depth: habit_id is a `uuid` column in Supabase.
            // MindsetRepository.load() already strips non-UUID keys (e.g. the
            // old "diagnostic_test" button bug) on read, but filtering again
            // here means this function can never again get stuck returning
            // the same 400 on every sync attempt just because one stray key
            // slipped into the local checkIns map some other way.
            val checkins = data.checkIns
                .filterKeys { habitId -> runCatching { java.util.UUID.fromString(habitId) }.isSuccess }
                .flatMap { (habitId, days) ->
                    days.map { day -> CheckinRow(user_id = uid, habit_id = habitId, day = day) }
                }
            val moods = data.moodHistory.map { (day, mood) ->
                MoodLogRow(user_id = uid, day = day, mode = mood.name)
            }
            val settings = listOf(
                SettingsRow(id = deviceId, device_id = deviceId, user_id = uid, payload = data.settings)
            )
            // The tracking payloads. habit_id is a uuid column server-side, so
            // an entry whose habit id isn't a UUID is dropped here rather than
            // failing the entire push — the same defence the check-ins above
            // use, and for the same reason: one stray key must not be able to
            // wedge sync permanently.
            val habitLogs = data.habitLogs
                .filter { runCatching { UUID.fromString(it.habitId) }.isSuccess }
                .map {
                    HabitLogRow(
                        id = it.id,
                        user_id = uid,
                        device_id = deviceId,
                        habit_id = it.habitId,
                        day = it.dayKey,
                        mode = it.mode?.name,
                        title = it.title,
                        note = it.note,
                        count = it.count,
                        unit = it.unit,
                        duration_seconds = it.durationSeconds,
                        logged_at_ms = it.recordedAtEpochMs,
                        occurrence_key = it.occurrenceKey,
                    )
                }
            // D2: partitioned, not filtered. The old `.filter { UUID.fromString
            // (it.habitId).isSuccess }` discarded every record the server's uuid
            // column cannot hold WITHOUT a count, a message or an entry in the
            // export's completeness list — unlike the habit payload, nothing
            // reconciled it. And because an unattributed record is never pushed,
            // it could never be attributed on another device either, so the loss
            // reproduced on every device forever. The dropped half is now a value
            // the report below can name.
            val (attributableActivity, unattributedActivity) =
                partitionByAttributableHabitId(data.activityRecords)
            val activities = attributableActivity
                .map { record ->
                    val startedAt = java.time.Instant.ofEpochMilli(record.timestamp)
                    ActivityRow(
                        user_id = uid,
                        // The SOURCE decides the provider, not a single constant.
                        // Every device row used to be uploaded as
                        // 'health_connect' — so a Strava run and a Polar day
                        // landed under the wrong provider, and the per-source
                        // breakdown the Insight screen is built on showed all of
                        // the user's activity as one source.
                        provider = activityProviderFor(record.source),
                        provider_activity_id = record.id,
                        activity_type = record.activityType,
                        activity_name = record.activityName,
                        started_at = startedAt.atOffset(java.time.ZoneOffset.UTC).toString(),
                        ended_at = record.endedAtMs
                            ?.let { java.time.Instant.ofEpochMilli(it).atOffset(java.time.ZoneOffset.UTC).toString() },
                        activity_date = startedAt
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .toString(),
                        duration_seconds = record.durationMinutes?.let { it * 60 },
                        distance_meters = record.distanceMeters,
                        // Sleep has no calorie figure, and the table has no
                        // sleep column — so the minutes ride in raw_data rather
                        // than being smuggled into calories_burned, which would
                        // report sleep as energy burned.
                        calories_burned = record.calories?.toDouble(),
                        heart_rate_avg = record.heartRateAvg,
                        heart_rate_max = record.heartRateMax,
                        steps = record.steps?.toInt(),
                        elevation_gain_meters = record.elevationGainMeters,
                        raw_data = activityRawData(
                            source = record.source,
                            // D1/D2: the attribution the server's columns cannot
                            // carry, so a restore can reattach this record to its
                            // habit instead of dropping the link.
                            habitId = record.habitId,
                            sleepMinutes = record.sleepMinutes,
                        ),
                    )
                }

            // Apply deletions before upserts: a habit removed locally must
            // actually be removed from Supabase, not just left out of this
            // upsert (upsert only ever adds/updates rows, it never deletes
            // ones that are missing from the payload).
            //
            // The result is no longer discarded. It used to be thrown away
            // (`?.let { return it }` was absent entirely), so a delete the
            // server kept rejecting stayed queued forever while every push
            // reported success — and the habit the user had deleted came back
            // on the next restore. Collected here and reported at the end.
            val pendingDeleteError = applyPendingDeletions(uid)

            // EVERY table is attempted, and the failures are collected rather
            // than short-circuiting.
            //
            // These used to be `upsert(...)?.let { return it }`, so the FIRST
            // failing table aborted the whole push and every later write was
            // skipped. That is what turned one schema problem into total data
            // loss: on a project whose `habits` table predates the alarm_times
            // migration, a single 400 on `habits` discarded the user's
            // check-ins, moods, settings and habit logs on every sync, forever,
            // while the app blamed the one table it happened to hit first.
            //
            // Order is kept (habits before the rows that reference a habit id),
            // but a failure in an earlier table no longer blocks an independent
            // later one from landing.
            val failures = mutableListOf<String>()
            val missingTables = mutableListOf<String>()
            val droppedFields = mutableListOf<String>()
            upsert("habits", habits, onConflict = "id").collect(failures, missingTables, droppedFields)
            upsert("checkins", checkins, onConflict = "user_id,habit_id,day")
                .collect(failures, missingTables, droppedFields)
            upsert("mood_log", moods, onConflict = "user_id,day")
                .collect(failures, missingTables, droppedFields)
            upsert("settings", settings, onConflict = "id")
                .collect(failures, missingTables, droppedFields)
            // The tracking payloads. These two were the gap: habit logs were
            // device-local only (a journal note was lost on reinstall), and
            // device-captured Health Connect activity never reached the server,
            // so smart-alarms and consistency-report could not see it.
            upsert("habit_logs", habitLogs, onConflict = "id")
                .collect(failures, missingTables, droppedFields)
            upsert(
                "activity_data",
                activities,
                onConflict = "user_id,provider,provider_activity_id",
            ).collect(failures, missingTables, droppedFields)
            // Report before the "backed up" timestamp is written: a push that
            // lost payloads must not be recorded as a clean sync, or the daily
            // backup would treat a partial failure as done for the whole day.
            //
            // Three distinct states, reported separately because the user's
            // remedy differs for each. Everything that was not a hard failure
            // used to fall through to `null`, i.e. "Backed up just now" — so a
            // project with no `habit_logs` table showed a clean success while the
            // journal payloads it had just tried to send were thrown away.
            if (failures.isNotEmpty()) {
                // Plain language FIRST, technical detail last. The reported
                // symptom was literally "I don't know" — a banner that leads with
                // a table name and a raw PostgREST string tells the user nothing
                // about the one thing they actually need to know: whether their
                // records are safe. The parenthetical keeps the detail available
                // for diagnosis without making it the headline.
                val detail = if (failures.size == 1) failures.first()
                else "${failures.size} items: " + failures.joinToString("; ")
                return "Couldn't finish the cloud backup — your records are safe " +
                    "on this phone, and it will retry on its own. ($detail)"
            }
            pendingDeleteError?.let { return it }
            // Nothing failed, but something was not stored in full. A success
            // with a caveat, and saying so is the whole point: the app must never
            // claim a complete backup it did not achieve.
            if (missingTables.isNotEmpty() || droppedFields.isNotEmpty() || unattributedActivity.isNotEmpty()) {
                // Names the affected fields compactly, then says the only thing
                // that matters to the user. Note there is deliberately NO
                // instruction to "apply a migration" any more: that was a
                // developer's remedy written into a user-facing banner, and the
                // user cannot perform it.
                val parts = mutableListOf<String>()
                if (missingTables.isNotEmpty()) parts += missingTables
                if (droppedFields.isNotEmpty()) parts += droppedFields
                // D2: named separately because it is a ROW count rather than a
                // field, and it is the one omission the user can actually act on
                // — by attributing the activity to a habit where it was captured.
                if (unattributedActivity.isNotEmpty()) {
                    parts += "${unattributedActivity.size} activity record(s) with no habit linked"
                }
                // Written after the full push, because these rows DID land.
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                lastPushPartial = true
                return "Your data is saved on this phone. A few newer extras " +
                    "(${parts.joinToString(", ")}) are still waiting to reach the " +
                    "cloud — this retries automatically and needs nothing from you."
            }
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: ${e.message}")
            "Couldn't reach Supabase. Check your connection and try again."
        }
    }

    /**
     * Pulls the signed-in user's rows (habits, check-ins, moods) for restore.
     * Requires an active session; strict owner-scoped RLS policies on the
     * server return only the caller's rows. Returns the snapshot or a null
     * snapshot with an error message via Pair semantics.
     */
    suspend fun pullSnapshot(): Pair<RemoteSnapshot?, String?> {
        if (!isConfigured) return null to "Supabase is not configured"
        if (accessToken == null) return null to "Sign in first to restore your data"
        return try {
            val habits = select<HabitRow>("habits") ?: return null to PULL_ERROR
            val checkins = select<CheckinRow>("checkins") ?: return null to PULL_ERROR
            val moods = select<MoodLogRow>("mood_log") ?: return null to PULL_ERROR
            // Additive: absence of these tables must not break a restore.
            val habitLogs = selectOrEmpty<HabitLogRow>("habit_logs")
            val activities = selectOrEmpty<ActivityRow>("activity_data")
            val snapshot = RemoteSnapshot(
                habits = habits.map {
                    Habit(
                        id = it.id,
                        name = it.name,
                        createdAt = it.created_at_ms,
                        iconId = it.icon_id,
                        alarmMessage = it.alarm_message,
                        reminderMinutes = it.reminder_minutes,
                        // A project that predates the `alarm_times` column returns
                        // it empty, in which case the legacy single time IS the
                        // schedule. Normalised through the same shared function
                        // the push path serialises with, so the round trip is
                        // symmetric and neither direction can reinterpret the
                        // other's rows.
                        // D6: a row whose two alarm columns disagree breaches the
                        // invariant every writer maintains
                        // (`alarm_times.firstOrNull() == reminder_minutes`), so it
                        // is evidence of a writer bug rather than a state to
                        // normalise. The breach is logged and the schedule is
                        // KEPT — the previous behaviour read it as "no alarm" and
                        // emptied it, silently deleting every alarm on the habit,
                        // which is the "my alarm vanished / it came back"
                        // complaint this path kept producing.
                        alarmTimes = restoredSchedule(it.alarm_times, it.reminder_minutes)
                            .also { schedule ->
                                if (schedule.inconsistent) {
                                    Log.w(
                                        TAG,
                                        "Habit ${it.id}: alarm_times=${it.alarm_times} with " +
                                            "reminder_minutes=null — invariant breached; " +
                                            "keeping the schedule instead of clearing it.",
                                    )
                                }
                            }
                            .times,
                        isPinned = it.is_pinned,
                        durationSeconds = it.duration_seconds,
                        repeatDaysMask = it.repeat_days_mask,
                        monitoredPackage = it.monitored_package,
                        screenTimeLimitMinutes = it.screen_time_limit_minutes,
                        monitoredAppLabel = it.monitored_app_label,
                        // Unknown provider names are dropped on read (see
                        // HabitTrackerLinks.providerOf) rather than carried
                        // through: a name written by a newer build that added a
                        // fourth provider must not survive into this build's
                        // state and then be written back in a different order.
                        trackerProviderIds = it.tracker_provider_ids
                            .filter { id -> com.rork.mindsetframestracker.integrations.HabitTrackerLinks.providerOf(id) != null },
                    )
                },
                checkIns = checkins.groupBy({ it.habit_id }, { it.day }),
                moodHistory = moods.mapNotNull { row ->
                    runCatching { row.day to MoodMode.valueOf(row.mode) }.getOrNull()
                }.toMap(),
                habitLogs = habitLogs.map { row ->
                    HabitLogEntry(
                        id = row.id,
                        habitId = row.habit_id,
                        dayKey = row.day,
                        mode = row.mode?.let { name ->
                            runCatching { HabitTrackingMode.valueOf(name) }.getOrNull()
                        } ?: HabitTrackingMode.CHECK,
                        title = row.title,
                        note = row.note,
                        count = row.count,
                        unit = row.unit,
                        durationSeconds = row.duration_seconds,
                        recordedAtEpochMs = row.logged_at_ms,
                        occurrenceKey = row.occurrence_key,
                    )
                },
                activityRecords = activities.map { row ->
                    ActivityRecord(
                        id = row.provider_activity_id,
                        // D1: this was hardcoded "". Every restored activity
                        // arrived honest about its numbers and blind about which
                        // habit it belonged to, so per-habit sourced consistency
                        // read zero on any second device while the totals looked
                        // right. The link rides in raw_data because the table's
                        // own habit_id column is nullable and never written — see
                        // `activityRawData`.
                        habitId = habitIdFromActivityRawData(row.raw_data),
                        source = row.raw_data?.get("source")?.jsonPrimitive?.contentOrNull
                            ?: row.provider,
                        activityType = row.activity_type,
                        timestamp = runCatching {
                            java.time.Instant.parse(row.started_at).toEpochMilli()
                        }.getOrDefault(0L),
                        durationMinutes = row.duration_seconds?.let { it / 60 },
                        distanceMeters = row.distance_meters,
                        steps = row.steps?.toLong(),
                        heartRateAvg = row.heart_rate_avg,
                        heartRateMax = row.heart_rate_max,
                        calories = row.calories_burned?.toInt(),
                        endedAtMs = row.ended_at?.let { parsed ->
                            runCatching { java.time.Instant.parse(parsed).toEpochMilli() }.getOrNull()
                        },
                        activityName = row.activity_name,
                        elevationGainMeters = row.elevation_gain_meters,
                        sleepMinutes = row.raw_data?.get("sleep_minutes")
                            ?.jsonPrimitive?.intOrNull,
                    )
                },
            )
            snapshot to null
        } catch (e: Exception) {
            Log.w(TAG, "Pull failed: ${e.message}")
            null to "Couldn't reach Supabase. Check your connection and try again."
        }
    }

    private suspend inline fun <reified T> select(table: String): List<T>? {
        val uid = sessionUserId ?: return null
        var response = authedGet(table, uid)
        if (response.status == HttpStatusCode.Unauthorized && tryRefreshSession()) {
            response = authedGet(table, uid)
        }
        if (!response.status.isSuccess()) {
            Log.w(TAG, "Select $table failed: ${response.status} ${response.bodyAsText().take(200)}")
            return null
        }
        return response.body()
    }

    /**
     * [select], but a failure returns an empty list instead of failing the
     * caller.
     *
     * Used for the two tracking-payload tables, which are additive to restore:
     * if `habit_logs` doesn't exist yet on this project (migration not run),
     * the user must still get their habits, check-ins and moods back rather
     * than an all-or-nothing restore failure. Losing the logs is recoverable
     * and visible; losing the whole account restore is not.
     */
    private suspend inline fun <reified T> selectOrEmpty(table: String): List<T> =
        try {
            select<T>(table) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Optional select $table failed: ${e.message}")
            emptyList()
        }

    private suspend fun authedGet(table: String, uid: String): HttpResponse =
        client.get("$baseUrl/rest/v1/$table?user_id=eq.$uid&select=*") {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer ${accessToken ?: anonKey}")
        }

    /**
     * Deletes every queued habit id (and its check-ins) from Supabase,
     * scoped to the current user. Ids that fail to delete are kept in the
     * queue so they're retried on the next sync instead of being lost.
     */
    private suspend fun applyPendingDeletions(uid: String): String? {
        val ids = pendingDeletedHabitIds
        if (ids.isEmpty()) return null
        val stillPending = mutableSetOf<String>()
        for (habitId in ids) {
            // Check-ins reference habit_id with no cascading FK guarantee on
            // the client side, so delete them explicitly first.
            val checkinsOk = deleteRow("checkins", "habit_id", habitId, uid)
            val habitOk = deleteRow("habits", "id", habitId, uid)
            if (!checkinsOk || !habitOk) stillPending += habitId
        }
        pendingDeletedHabitIds = stillPending
        // An id that survived a real delete attempt stays queued for the next
        // sync, but it is no longer SILENT. A delete the server keeps rejecting
        // (an RLS policy that does not cover DELETE, a row still referenced
        // elsewhere) would otherwise retry forever while every push reported
        // success — and the deleted habit would reappear on the next restore
        // with the user never told anything had gone wrong.
        return if (stillPending.isEmpty()) null
        else "${stillPending.size} deleted habit(s) could not be removed from the backup \u2014 " +
            "they may come back if you restore on another device."
    }

    /** DELETE /rest/v1/{table}?{column}=eq.{value}&user_id=eq.{uid}. Returns true on success (incl. "nothing to delete"). */
    private suspend fun deleteRow(table: String, column: String, value: String, uid: String): Boolean {
        var response = deleteRequest(table, column, value, uid)
        if (response.status == HttpStatusCode.Unauthorized && tryRefreshSession()) {
            response = deleteRequest(table, column, value, uid)
        }
        if (!response.status.isSuccess()) {
            Log.w(TAG, "Delete $table where $column=$value failed: ${response.status} ${runCatching { response.bodyAsText() }.getOrDefault("").take(300)}")
            return false
        }
        return true
    }

    private suspend fun deleteRequest(table: String, column: String, value: String, uid: String): HttpResponse =
        client.delete("$baseUrl/rest/v1/$table?$column=eq.$value&user_id=eq.$uid") {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer ${accessToken ?: anonKey}")
        }

    private suspend inline fun <reified T> upsert(
        table: String,
        rows: List<T>,
        onConflict: String,
    ): UpsertOutcome {
        if (rows.isEmpty()) return UpsertOutcome.Ok
        // Serialized once, and carried as raw JSON, so a retry after dropping an
        // unsupported field re-sends the very body the server just judged —
        // it cannot drift from it, and there is no second serialization pass.
        var payload: JsonElement = json.encodeToJsonElement(rows)
        var response = upsertRequest(table, payload, onConflict)
        if (response.status == HttpStatusCode.Unauthorized && tryRefreshSession()) {
            response = upsertRequest(table, payload, onConflict)
        }

        // ── A column the live database does not have is NOT a reason to lose
        // the user's data ────────────────────────────────────────────────────
        //
        // This is the failure the app actually hit. `alarm_times` was added to
        // the client's HabitRow AND to a migration file
        // (20260916140000_habit_multiple_alarms_and_occurrences.sql) — but the
        // migration was never applied to the live project, and `encodeDefaults
        // = true` means the field is sent on EVERY push. PostgREST rejected
        // each one with PGRST204. Because the tables were then attempted with a
        // short-circuit, that single rejection also silently discarded
        // check-ins, moods, settings and habit logs on every sync.
        //
        // Retrying without the unsupported field lets the rest of the row land,
        // which is strictly better than losing it. The pull path already treats
        // an absent `alarm_times` as "fall back to reminder_minutes" (see
        // Habit.alarmMinutes), so the habit restores with its first alarm, and
        // the full schedule is restored the moment the migration is applied.
        // The dropped fields are RETURNED to the caller, so the user is told a
        // field was not saved instead of being shown a fake clean success.
        val rejected = mutableListOf<String>()
        var dropAttempts = 0
        while (!response.status.isSuccess() && dropAttempts < MAX_COLUMN_DROPS) {
            val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
            val missing = missingColumn(bodyText) ?: break
            // Null means nothing changed (the field was not in the payload),
            // so retrying would resend an identical body and loop.
            val reduced = payload.dropObjectKeys(listOf(missing)) ?: break
            payload = reduced
            rejected += missing
            dropAttempts++
            Log.w(TAG, "Upsert $table: live schema has no '$missing'; retrying without it")
            response = upsertRequest(table, payload, onConflict)
            if (response.status == HttpStatusCode.Unauthorized && tryRefreshSession()) {
                response = upsertRequest(table, payload, onConflict)
            }
        }

        if (!response.status.isSuccess()) {
            val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
            // The full PostgREST text is still logged for diagnosis, but it no
            // longer reaches the user: the banner now says what happened in
            // plain words and carries only a short label for the affected area.
            Log.w(TAG, "Upsert $table failed: ${response.status} ${bodyText.take(500)}")
            // A table the live project has never had cannot be worked around by
            // sending less data — there is no column to drop, so it is reported
            // as its own case rather than as a generic failure.
            if (isMissingTable(bodyText)) return UpsertOutcome.MissingTable(table)
            return UpsertOutcome.Failed("cloud storage for $table")
        }
        if (rejected.isNotEmpty()) {
            return UpsertOutcome.Degraded(rejected.map { "$table.$it" })
        }
        return UpsertOutcome.Ok
    }

    /**
     * True when the live project has no such TABLE, as opposed to no such column.
     *
     * The distinction is load-bearing: a missing column can be worked around by
     * dropping the field and keeping the rest of the row, whereas a missing table
     * cannot — there is nothing to drop. `habit_logs` and `activity_data` were
     * both in that state on a project whose migrations had not been applied, and
     * a raw `PGRST205` string is not something a user can act on.
     *
     * The [COLUMN_DOES_NOT_EXIST] guard is required, not defensive: PostgREST's
     * 42P01 text (`relation "x" does not exist`) is a SUBSTRING of the 42703
     * column text (`column "c" of relation "x" does not exist`), so without it
     * every missing column would be misreported as a missing table.
     */
    private fun isMissingTable(bodyText: String): Boolean {
        val message = extractPostgrestMessage(bodyText) ?: return false
        if (COULD_NOT_FIND_TABLE.containsMatchIn(message)) return true
        return RELATION_DOES_NOT_EXIST.containsMatchIn(message) &&
            !COLUMN_DOES_NOT_EXIST.containsMatchIn(message)
    }

    /**
     * The column name PostgREST reports as absent from the live schema, or null
     * when the body is not a missing-column error.
     *
     * Two shapes are matched, because the same condition is reported differently
     * depending on PostgREST's version and whether its schema cache is warm:
     *  - `Could not find the 'alarm_times' column of 'habits' in the schema
     *    cache` (PGRST204 — the literal text the user saw on screen)
     *  - `column "alarm_times" of relation "habits" does not exist` (Postgres
     *    42703, raised when the cache is bypassed)
     */
    private fun missingColumn(bodyText: String): String? {
        val message = extractPostgrestMessage(bodyText) ?: return null
        COULD_NOT_FIND_COLUMN.find(message)?.groupValues?.getOrNull(1)?.let { return it }
        COLUMN_DOES_NOT_EXIST.find(message)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    /**
     * A copy of this payload with every occurrence of the keys in [keys]
     * removed, at any depth, or null when nothing was removed.
     *
     * Depth matters: the payload is a JSON ARRAY of row objects, so the field to
     * drop is a key of each element, not of the top-level value. Returning null
     * for a no-op is what stops the retry loop — a key that is not actually
     * present (because the server named a column this payload never sent) would
     * otherwise resend an identical body forever.
     */
    private fun JsonElement.dropObjectKeys(keys: List<String>): JsonElement? {
        val keySet = keys.toSet()
        var removed = false
        fun scrub(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(
                element.entries
                    .filter { entry ->
                        val drop = entry.key in keySet
                        if (drop) removed = true
                        !drop
                    }
                    .associate { it.key to scrub(it.value) },
            )
            is JsonArray -> JsonArray(element.map { scrub(it) })
            else -> element
        }
        val scrubbed = scrub(this)
        return if (removed) scrubbed else null
    }

    /**
     * PostgREST error bodies look like:
     *   {"code":"42703","details":null,"hint":null,"message":"column \"foo\" of relation \"habits\" does not exist"}
     * Pull out just the human-readable `message` (falling back to `hint` or
     * `details`) so the UI can show something actionable instead of raw JSON.
     */
    private fun extractPostgrestMessage(bodyText: String): String? {
        if (bodyText.isBlank()) return null
        return runCatching {
            val obj = Json.parseToJsonElement(bodyText).jsonObject
            obj["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["hint"]?.jsonPrimitive?.contentOrNull
                ?: obj["details"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    /**
     * POSTs an already-serialized JSON payload.
     *
     * Takes raw [JsonElement] (via `TextContent`, so ktor's ContentNegotiation
     * does not re-serialize it) rather than a `List<T>`, because the caller may
     * need to retry with a reduced body after the live schema rejects a field.
     */
    private suspend fun upsertRequest(
        table: String,
        payload: JsonElement,
        onConflict: String,
    ): HttpResponse =
        client.post("$baseUrl/rest/v1/$table?on_conflict=$onConflict") {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer ${accessToken ?: anonKey}")
            header("Prefer", "resolution=merge-duplicates")
            setBody(TextContent(payload.toString(), ContentType.Application.Json))
        }

    /**
     * Files one table's [UpsertOutcome] into the three accumulator lists the
     * push summary is built from, so the call site stays a flat list of upserts.
     */
    private fun UpsertOutcome.collect(
        failures: MutableList<String>,
        missingTables: MutableList<String>,
        droppedFields: MutableList<String>,
    ) {
        when (this) {
            is UpsertOutcome.Ok -> Unit
            is UpsertOutcome.Degraded -> droppedFields += missing
            is UpsertOutcome.MissingTable -> missingTables += table
            is UpsertOutcome.Failed -> failures += message
        }
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EMAIL = "session_email"
        private const val KEY_USER_ID = "session_user_id"
        private const val KEY_PENDING_PUSH = "pending_push"
        private const val KEY_PENDING_DELETES = "pending_deleted_habit_ids"
        private const val KEY_PROVIDER = "auth_provider"
        private const val KEY_LAST_SYNC = "last_sync_at_ms"
        private const val KEY_CONSUMED_AUTH_LINK = "consumed_auth_link"
        private const val TAG = "SupabaseSync"

        /**
         * The two user-facing strings above deliberately do NOT name a migration
         * file or ask the user to run anything. The previous wording said "apply
         * the pending migration in backend/supabase/migrations" — a developer's
         * instruction shown to a person who cannot act on it, and the reported
         * result was literally "I don't know". Who repairs the schema is a
         * developer concern; what the user needs to know is that nothing was
         * lost and no action is required. Kept English-only, like the rest of
         * this file's user-facing strings.
         */

        /**
         * Region bucket for anything unresolvable. 'ZZ' is ISO-3166's own
         * "unknown" value, so it is conventional rather than invented here.
         * Mirrors founding_member_region() in SQL and regionFor() in the edge
         * function — all three must agree.
         */
        private const val UNKNOWN_REGION = "ZZ"
        private const val PULL_ERROR = "Couldn't restore your data. Check your connection and try again."

        /** PostgREST PGRST204: "Could not find the 'x' column of 'y' in the schema cache". */
        private val COULD_NOT_FIND_COLUMN =
            Regex("""Could not find the '([A-Za-z0-9_]+)' column""")

        /**
         * Postgres 42703: `column "x" of relation "y" does not exist`.
         *
         * The trailing `does not exist` is REQUIRED. Without it this pattern also
         * matches other errors that mention a column of a relation — most
         * damagingly the NOT NULL violation (`null value in column "user_id" of
         * relation "habits" violates not-null constraint`, SQLSTATE 23502), which
         * would be read as "user_id is missing" and make the retry loop strip a
         * REQUIRED ownership column from the payload.
         */
        private val COLUMN_DOES_NOT_EXIST =
            Regex("""column "([A-Za-z0-9_]+)" of relation "[^"]+" does not exist""")

        /** PostgREST PGRST205: "Could not find the table 'public.x' in the schema cache". */
        private val COULD_NOT_FIND_TABLE =
            Regex("""Could not find the table '([A-Za-z0-9_.]+)'""")

        /** Postgres 42P01: `relation "public.x" does not exist`. */
        private val RELATION_DOES_NOT_EXIST =
            Regex("""relation "([A-Za-z0-9_.]+)" does not exist""")

        /**
         * Bounds the missing-column retry loop. A schema this far behind is not
         * going to be fixed by sending even less; the remaining error is then
         * reported to the user intact rather than looping.
         */
        private const val MAX_COLUMN_DROPS = 8

        /**
         * `provider` value for activity rows captured on the device rather
         * than pulled from a third-party OAuth connection.
         */
        private const val DEVICE_ACTIVITY_PROVIDER = "health_connect"

        /**
         * Maps an [ActivityRecord.source] onto the `activity_data.provider`
         * CHECK constraint's vocabulary.
         *
         * The constraint accepts only ('strava', 'google_fit',
         * 'huawei_health', 'health_connect'), which does not include the
         * device-side literal the app actually writes (`health_connect_device`),
         * so that one is folded into 'health_connect' — same platform, same
         * store, and the precise origin is preserved in `raw_data.source`.
         * Anything unrecognised falls back to the device provider rather than
         * being rejected by the constraint on upload.
         */
        private fun activityProviderFor(source: String): String = when (source) {
            "strava" -> "strava"
            "google_fit" -> "google_fit"
            "huawei_health" -> "huawei_health"
            "health_connect", "health_connect_device" -> "health_connect"
            "polar" -> DEVICE_ACTIVITY_PROVIDER
            else -> DEVICE_ACTIVITY_PROVIDER
        }
    }
}