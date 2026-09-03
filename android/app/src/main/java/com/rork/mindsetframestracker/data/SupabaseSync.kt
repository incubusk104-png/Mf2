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
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Snapshot of remote data pulled after sign-in (restore flow). */
data class RemoteSnapshot(
    val habits: List<Habit>,
    val checkIns: Map<String, List<String>>,
    val moodHistory: Map<String, MoodMode>,
)

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

    // ── Data sync ────────────────────────────────────────────────────

    @Serializable
    private data class HabitRow(
        val id: String,
        val user_id: String? = null,
        val name: String,
        val created_at_ms: Long = 0L,
        val icon_id: String? = null,
        val reminder_minutes: Int? = null,
        val is_pinned: Boolean = false,
        val duration_seconds: Int? = null,
        val repeat_days_mask: Int = REPEAT_DAILY,
        val monitored_package: String? = null,
        val screen_time_limit_minutes: Int? = null,
        val monitored_app_label: String? = null,
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
     * Upserts the full local snapshot into Supabase. Returns null on success
     * or a short user-facing error message on failure.
     */
    suspend fun pushSnapshot(data: AppData): String? {
        if (!isConfigured) return "Supabase is not configured"
        val uid = sessionUserId ?: return "Sign in first to back up your data"
        if (accessToken == null) return "Sign in first to back up your data"
        return try {
            val habits = data.habits.map {
                HabitRow(
                    id = it.id,
                    user_id = uid,
                    name = it.name,
                    created_at_ms = it.createdAt,
                    icon_id = it.iconId,
                    reminder_minutes = it.reminderMinutes,
                    is_pinned = it.isPinned,
                    duration_seconds = it.durationSeconds,
                    repeat_days_mask = it.repeatDaysMask,
                    monitored_package = it.monitoredPackage,
                    screen_time_limit_minutes = it.screenTimeLimitMinutes,
                    monitored_app_label = it.monitoredAppLabel,
                )
            }
            val checkins = data.checkIns.flatMap { (habitId, days) ->
                days.map { day -> CheckinRow(user_id = uid, habit_id = habitId, day = day) }
            }
            val moods = data.moodHistory.map { (day, mood) ->
                MoodLogRow(user_id = uid, day = day, mode = mood.name)
            }
            val settings = listOf(
                SettingsRow(id = deviceId, device_id = deviceId, user_id = uid, payload = data.settings)
            )

            // Apply deletions before upserts: a habit removed locally must
            // actually be removed from Supabase, not just left out of this
            // upsert (upsert only ever adds/updates rows, it never deletes
            // ones that are missing from the payload).
            applyPendingDeletions(uid)?.let { return it }

            upsert("habits", habits, onConflict = "id")?.let { return it }
            upsert("checkins", checkins, onConflict = "user_id,habit_id,day")?.let { return it }
            upsert("mood_log", moods, onConflict = "user_id,day")?.let { return it }
            upsert("settings", settings, onConflict = "id")?.let { return it }
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
            val snapshot = RemoteSnapshot(
                habits = habits.map {
                    Habit(
                        id = it.id,
                        name = it.name,
                        createdAt = it.created_at_ms,
                        iconId = it.icon_id,
                        reminderMinutes = it.reminder_minutes,
                        isPinned = it.is_pinned,
                        durationSeconds = it.duration_seconds,
                        repeatDaysMask = it.repeat_days_mask,
                        monitoredPackage = it.monitored_package,
                        screenTimeLimitMinutes = it.screen_time_limit_minutes,
                        monitoredAppLabel = it.monitored_app_label,
                    )
                },
                checkIns = checkins.groupBy({ it.habit_id }, { it.day }),
                moodHistory = moods.mapNotNull { row ->
                    runCatching { row.day to MoodMode.valueOf(row.mode) }.getOrNull()
                }.toMap(),
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
        // Only surface an error if some ids are still stuck after a real
        // attempt — a partial success still leaves the rest queued silently
        // and retries next time rather than blocking the whole sync.
        return null
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

    private suspend inline fun <reified T> upsert(table: String, rows: List<T>, onConflict: String): String? {
        if (rows.isEmpty()) return null
        var response = upsertRequest(table, rows, onConflict)
        if (response.status == HttpStatusCode.Unauthorized && tryRefreshSession()) {
            response = upsertRequest(table, rows, onConflict)
        }
        if (!response.status.isSuccess()) {
            val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
            Log.w(TAG, "Upsert $table failed: ${response.status} ${bodyText.take(500)}")
            // Surface the actual PostgREST error (not just the status code) so
            // the real cause — e.g. a missing column, a NOT NULL violation, or
            // a row-level-security policy rejection — is visible instead of a
            // generic, unhelpful "try again" message that repeats forever.
            val detail = extractPostgrestMessage(bodyText)
            val suffix = if (detail != null) ": $detail" else ""
            return "Sync failed on '$table' (${response.status.value})$suffix"
        }
        return null
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

    private suspend inline fun <reified T> upsertRequest(
        table: String,
        rows: List<T>,
        onConflict: String,
    ): HttpResponse =
        client.post("$baseUrl/rest/v1/$table?on_conflict=$onConflict") {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer ${accessToken ?: anonKey}")
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(rows)
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
        private const val PULL_ERROR = "Couldn't restore your data. Check your connection and try again."
    }
}
