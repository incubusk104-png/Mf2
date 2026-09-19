package com.rork.mindsetframestracker.ui

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.util.Patterns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rork.mindsetframestracker.BuildConfig
import com.rork.mindsetframestracker.auth.HuaweiAuthClient
import com.rork.mindsetframestracker.billing.Entitlements
import com.rork.mindsetframestracker.billing.RestoreResult
import com.rork.mindsetframestracker.billing.SubscriptionBilling
import com.rork.mindsetframestracker.billing.SubscriptionResult
import com.rork.mindsetframestracker.billing.SubscriptionTier
import com.rork.mindsetframestracker.integrations.HabitTrackerLinks
import com.rork.mindsetframestracker.integrations.providersLeftUnboundAfter
import com.rork.mindsetframestracker.integrations.StravaAuthClient
import com.rork.mindsetframestracker.integrations.StravaTokens
import com.rork.mindsetframestracker.integrations.TrackerConnections
import com.rork.mindsetframestracker.integrations.TrackerProvider
import com.rork.mindsetframestracker.ui.components.stravaActivityTypeFor
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.mergeHabitsPreferringLocal
import com.rork.mindsetframestracker.data.CloudBackupWorker
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.BadgeTier
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.HabitCategory
import com.rork.mindsetframestracker.data.HabitRecommender
import com.rork.mindsetframestracker.data.HabitSuggestion
import com.rork.mindsetframestracker.data.HabitLogEntry
import com.rork.mindsetframestracker.data.HabitTrackingMode
import com.rork.mindsetframestracker.data.alarmMinutes
import com.rork.mindsetframestracker.data.hasAnsweredOccurrence
import com.rork.mindsetframestracker.data.occurrenceKeyFor
import com.rork.mindsetframestracker.data.withAlarmTimes
import com.rork.mindsetframestracker.data.withAlarmMessage
import com.rork.mindsetframestracker.data.isScreenTimeHabit
import com.rork.mindsetframestracker.data.screenTimeSummary
import com.rork.mindsetframestracker.data.ScreenTimeLimitInput
import com.rork.mindsetframestracker.data.MAX_FREE_HABITS
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.data.hasFeatureAccess
import com.rork.mindsetframestracker.data.regionalLanguageFor
import com.rork.mindsetframestracker.data.universallyFreeLanguages
import com.rork.mindsetframestracker.data.SupabaseSync
import com.rork.mindsetframestracker.data.ThemeMode
import com.rork.mindsetframestracker.notifications.CheckInNotifier
import com.rork.mindsetframestracker.notifications.HabitAlarmScheduler
import com.rork.mindsetframestracker.notifications.NotificationScheduler
import com.rork.mindsetframestracker.notifications.StreakAlertNotifier
import com.rork.mindsetframestracker.notifications.WeeklyRecapNotifier
import com.rork.mindsetframestracker.ui.avatar.AvatarCatalog
import com.rork.mindsetframestracker.util.LocalizationManager
import com.rork.mindsetframestracker.util.isBatteryLow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Max habit name length — mirrors the Supabase column constraint (schema check <= 60). */
const val MAX_HABIT_NAME_LENGTH = 60

/** Minimum password length enforced client-side at sign-up. */
const val MIN_PASSWORD_LENGTH = 8

/**
 * UI state for the optional cloud backup & sync (user's own Supabase project).
 * Available only when Supabase credentials are configured at build time.
 */
data class SyncUiState(
    val available: Boolean = false,
    /** True when Huawei Account Kit sign-in is available (HMS Core present). */
    val huaweiAvailable: Boolean = false,
    val email: String? = null,
    /** Auth provider of the active session — "huawei" or "email". */
    val provider: String? = null,
    /** Epoch millis of the last successful cloud backup (0 = never). */
    val lastSyncAtMs: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    /**
     * True when the last backup landed but could not store everything — a table
     * or column the live project does not have. Shown as a warning rather than
     * an error (the data that could be saved was saved), but it must never look
     * like a clean success.
     */
    val isPartial: Boolean = false,
    /** One-shot flag: sign-up hit "email already registered" — UI should
     * switch to the Sign In tab, then call [AppViewModel.consumeSuggestSignIn]. */
    val suggestSignIn: Boolean = false,
    /** True when the user arrived via a password reset link and must set a new password. */
    val showSetNewPasswordSheet: Boolean = false,
)

/** Minimum gap between manual syncs, to avoid spamming Supabase with duplicate pushes. */
private const val SYNC_COOLDOWN_MS = 15_000L

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: MindsetRepository? = runCatching {
        MindsetRepository(application)
    }.onFailure {
        if (BuildConfig.DEBUG) Log.e("AppViewModel", "Repository init failed: ${it.message}", it)
    }.getOrNull()

    private val notificationScheduler = runCatching {
        NotificationScheduler(application)
    }.onFailure {
        if (BuildConfig.DEBUG) Log.e("AppViewModel", "NotificationScheduler init failed: ${it.message}", it)
    }.getOrNull() ?: NotificationScheduler(application)

    private val supabaseSync = runCatching {
        SupabaseSync(application)
    }.onFailure {
        if (BuildConfig.DEBUG) Log.e("AppViewModel", "SupabaseSync init failed: ${it.message}", it)
    }.getOrNull() ?: SupabaseSync(application)

    private val _state = MutableStateFlow(repository?.load() ?: AppData())
    val state: StateFlow<AppData> = _state.asStateFlow()

    /**
     * Re-reads the persisted blob and replaces in-memory state with it.
     *
     * [_state] is only ever loaded once at construction time above, but the
     * alarm/snooze BroadcastReceivers write straight to [MindsetRepository]
     * (they have no AppViewModel instance to call into — the app process
     * may not even be running when an alarm fires). If this activity/
     * ViewModel was already alive in the background when that happened,
     * its in-memory copy would otherwise silently go stale — e.g. a habit
     * marked done by a ringing alarm wouldn't show as done until the app
     * was force-restarted. Called from MainActivity on every ON_RESUME.
     */
    fun reloadFromDisk() {
        val fresh = repository?.load() ?: return
        if (fresh != _state.value) _state.value = fresh
    }

    init {
        runCatching {
            LocalizationManager.init(application)
            LocalizationManager.contentFor(_state.value.settings.language)
            LocalizationManager.loadStringJson(_state.value.settings.language.code)
        }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Localization init failed: ${it.message}", it)
        }
        runCatching { ensureRegionalLanguage() }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Regional language resolve failed: ${it.message}", it)
        }
        runCatching { retryPendingSync() }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Retry pending sync failed: ${it.message}", it)
        }
        runCatching { alignDailyBackup() }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Daily backup alignment failed: ${it.message}", it)
        }
        runCatching { syncCompanionReminders() }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Companion reminder sync failed: ${it.message}", it)
        }
        runCatching { refreshCompanionUnlocks() }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Companion unlock refresh failed: ${it.message}", it)
        }
        // Silent entitlement sync with Huawei IAP: restores premium after an
        // app update / reinstall / device change, and picks up sandbox
        // renewals. Unavailable (no HMS / not signed in) keeps current state.
        runCatching { restoreSubscriptionSilently() }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Subscription restore failed: ${it.message}", it)
        }
        // Consume any orphaned tip purchases left from previous sessions
        // (e.g. process killed between purchase and consume call).
        runCatching {
            com.rork.mindsetframestracker.billing.TipBilling.consumeUnfinishedPurchases(application)
        }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Tip consume cleanup failed: ${it.message}", it)
        }
        // Screen-time habits: settle yesterday + refresh today's live status
        // from UsageStats on every app start. No-op without Usage Access.
        runCatching { evaluateScreenTimeHabits() }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Screen-time evaluation failed: ${it.message}", it)
        }
        // Self-heal the built-in reminders on every cold launch. A device
        // reboot is already covered by BootReceiver, but an OEM battery
        // manager "force stopping" the app (MIUI/EMUI/ColorOS are the usual
        // culprits) silently cancels every AlarmManager alarm without
        // sending BOOT_COMPLETED — the only way those come back is the user
        // opening the app again, so make that moment repair everything
        // instead of relying on the user to notice and re-toggle a setting
        // that no longer exists in the UI. No-op (skipped) before
        // onboarding finishes, and a no-op if notification permission
        // hasn't been granted yet.
        if (_state.value.settings.onboardingDone) {
            runCatching { scheduleNotification() }.onFailure {
                if (BuildConfig.DEBUG) Log.e("AppViewModel", "Startup reminder re-arm failed: ${it.message}", it)
            }
            // BUG FIX: the self-heal above only ever covered the four
            // "built-in" reminders (daily / streak / weekly / evening) —
            // individual HABIT alarms (e.g. a "walk" reminder at 8:45 PM)
            // were never re-armed here. They only came back on an actual
            // device reboot (BootReceiver), so the very common "OEM battery
            // manager force-stopped the app in the background, silently
            // wiping every AlarmManager alarm, no BOOT_COMPLETED sent" case
            // left habit alarms dead until the user happened to reopen the
            // habit's own alarm picker. Re-arming them here — on every cold
            // launch, same as the built-in reminders — closes that gap.
            runCatching { rearmHabitAlarms() }.onFailure {
                if (BuildConfig.DEBUG) Log.e("AppViewModel", "Startup habit-alarm re-arm failed: ${it.message}", it)
            }
        }
    }

    /**
     * Re-arms every per-habit alarm against the current [Habit.reminderMinutes] /
     * [Habit.repeatDaysMask] state via [HabitAlarmScheduler.rescheduleAll]. Safe
     * to call anytime — habits with no reminder set are skipped, and rescheduling
     * an already-armed alarm just replaces it with an identical one (no double-fire).
     */
    private fun rearmHabitAlarms() {
        // Filtered on the resolved list, not on `reminderMinutes != null`: the
        // legacy field only holds a habit's FIRST time, so filtering on it would
        // leave a habit's 12:00 and 18:00 alarms un-armed after a restart —
        // the morning one would ring and the rest would silently not.
        val habitsWithReminders = _state.value.habits.filter { it.alarmMinutes.isNotEmpty() }
        if (habitsWithReminders.isNotEmpty()) {
            HabitAlarmScheduler.rescheduleAll(getApplication(), habitsWithReminders)
        }
    }

    // ── Premium subscription (Huawei IAP) ─────────────────────────────

    /**
     * The product the user is currently buying — SubscriptionBilling launches
     * the payment sheet through the classic startActivityForResult path, so
     * MainActivity.onActivityResult needs this to attribute the result.
     */
    var pendingSubscriptionProductId: String = ""
        private set

    private val _subscriptionMessage = MutableStateFlow<String?>(null)
    val subscriptionMessage: StateFlow<String?> = _subscriptionMessage.asStateFlow()

    fun consumeSubscriptionMessage() {
        _subscriptionMessage.value = null
    }

    /** Remember which product the in-flight purchase sheet belongs to. */
    fun onSubscriptionPurchaseStarted(productId: String) {
        pendingSubscriptionProductId = productId
    }

    /** Called from MainActivity.onActivityResult for SUBSCRIPTION_REQUEST_CODE. */
    fun onSubscriptionPurchaseResult(result: SubscriptionResult) {
        pendingSubscriptionProductId = ""
        when (result) {
            is SubscriptionResult.Success -> {
                // The entitlement is granted here and ONLY here for a purchase:
                // Success is produced solely from ORDER_STATE_SUCCESS or
                // ORDER_PRODUCT_OWNED, i.e. the store says the user owns it.
                // Cancelled and Error never reach this branch, so a failed or
                // abandoned payment leaves isPremium and the plan tier untouched.
                grantSubscription(result.productId)
                _subscriptionMessage.value = "Premium unlocked \u2014 welcome aboard! \uD83C\uDF89"
                // A founding-tier purchase also consumes one of this region's
                // founding slots. The server re-verifies the signed purchase
                // against Huawei's Order Service before it will consume one, so
                // the payload is passed through rather than a bare plan id.
                //
                // ORDER_PRODUCT_OWNED can arrive with an EMPTY purchase payload
                // (a re-tap of a button for something already owned). That is
                // not evidence of a new payment, so no claim is attempted at
                // all \u2014 attempting one would either be refused by the server or,
                // worse, burn a slot on a purchase that was never made.
                if (Entitlements.tierForProductId(result.productId) == SubscriptionTier.FOUNDING) {
                    if (result.purchaseData.isBlank()) {
                        Log.i(
                            "AppViewModel",
                            "Founding purchase reported owned with no signed payload \u2014 " +
                                "not claiming a slot (no payment evidence).",
                        )
                    } else {
                        // plan_id is the entitlement plan id; the server also
                        // reads the product from the payload Huawei signed, so a
                        // client that lied about the plan cannot slip a
                        // non-founding product past the gate.
                        recordFoundingMemberClaim(
                            planId = result.productId,
                            productId = result.productId,
                            purchaseData = result.purchaseData,
                            signature = result.signature,
                        )
                    }
                }
            }
            is SubscriptionResult.Cancelled -> {
                // Silent \u2014 the user closed the payment sheet. Nothing is granted
                // and no slot is claimed.
            }
            is SubscriptionResult.Error -> {
                _subscriptionMessage.value = result.message
            }
        }
    }

    /** "Restore purchase" — explicit user action from the premium sheet. */
    fun restoreSubscription() {
        viewModelScope.launch {
            when (val restored = SubscriptionBilling.queryActiveSubscription(getApplication())) {
                is RestoreResult.Active -> {
                    grantSubscription(restored.productId)
                    _subscriptionMessage.value = "Premium restored."
                }
                is RestoreResult.NotSubscribed ->
                    _subscriptionMessage.value = "No active subscription found for this Huawei ID."
                is RestoreResult.Unavailable ->
                    _subscriptionMessage.value = "Couldn't reach AppGallery billing. Check your Huawei ID sign-in."
            }
        }
    }

    /** Startup sync: only ever changes state on a definitive store answer. */
    private fun restoreSubscriptionSilently() {
        viewModelScope.launch {
            runCatching { SubscriptionBilling.checkSandbox(getApplication()) }
            when (val restored = SubscriptionBilling.queryActiveSubscription(getApplication())) {
                is RestoreResult.Active -> grantSubscription(restored.productId)
                is RestoreResult.NotSubscribed -> {
                    // Revoke only entitlements that were granted from a store
                    // purchase — a legacy/manual premium flag (no product id)
                    // is never touched by the silent check.
                    val settings = _state.value.settings
                    if (settings.isPremium && settings.subscriptionProductId != null) {
                        update {
                            it.copy(
                                settings = it.settings.copy(
                                    isPremium = false,
                                    subscriptionProductId = null,
                                ),
                            )
                        }
                    }
                }
                is RestoreResult.Unavailable -> Unit // keep current entitlement
            }
        }
    }

    private fun grantSubscription(productId: String) {
        update {
            it.copy(
                settings = it.settings.copy(
                    isPremium = true,
                    subscriptionProductId = productId.ifBlank { it.settings.subscriptionProductId },
                ),
            )
        }
    }

    // ── Strava connection ─────────────────────────────────────────

    private val _stravaMessage = MutableStateFlow<String?>(null)
    val stravaMessage: StateFlow<String?> = _stravaMessage.asStateFlow()

    fun consumeStravaMessage() {
        _stravaMessage.value = null
    }

    fun isStravaConnected(): Boolean = !_state.value.settings.stravaRefreshToken.isNullOrBlank()

    /** Deep-link return leg (MainActivity) — exchanges the code server-side. */
    fun handleStravaAuthCode(code: String) {
        viewModelScope.launch {
            StravaAuthClient.exchangeCodeForToken(code)
                .onSuccess { tokens ->
                    saveStravaTokens(tokens)
                    _stravaMessage.value = "Strava account authenticated and connected. You can enable auto-sync in Settings > Activity sync."
                }
                .onFailure {
                    _stravaMessage.value = "Strava connection failed. Please try again."
                    if (BuildConfig.DEBUG) Log.e("AppViewModel", "Strava code exchange failed", it)
                }
        }
    }

    fun onStravaConnectFailed(message: String) {
        _stravaMessage.value = message
    }

    /**
     * Opens the Strava OAuth consent page. Resolves the PUBLIC client id
     * at runtime when it wasn't baked into this build (Edge Function
     * discovery) — so "isn't configured for this build" can only happen
     * when the server truly has no Strava credentials either.
     *
     * Call ONLY after the user accepted the privacy consent dialog.
     */
    fun connectStrava() {
        viewModelScope.launch {
            val clientId = StravaAuthClient.resolveClientId()
            if (clientId.isNullOrBlank()) {
                _stravaMessage.value = "Strava isn't configured for this build yet."
                return@launch
            }
            runCatching {
                val intent = StravaAuthClient.buildAuthIntent(clientId)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            }.onFailure {
                _stravaMessage.value = "No browser available to open Strava."
            }
        }
    }

    /**
     * Opens the Polar Flow OAuth consent page. Resolves the PUBLIC client id
     * at runtime when it wasn't baked into this build (Edge Function
     * discovery). Call ONLY after the user accepted the privacy consent
     * dialog.
     */
    fun connectPolar() {
        viewModelScope.launch {
            val clientId = com.rork.mindsetframestracker.integrations.PolarClient.resolveClientId()
            if (clientId.isNullOrBlank()) {
                _stravaMessage.value = "Polar isn't configured for this build yet."
                return@launch
            }
            runCatching {
                val intent = com.rork.mindsetframestracker.integrations.PolarClient
                    .buildAuthIntent(clientId)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            }.onFailure {
                _stravaMessage.value = "No browser available to open Polar."
            }
        }
    }

    /** Handles the Polar OAuth callback code — exchanges it for tokens and registers the user. */
    fun handlePolarAuthCode(code: String) {
        _stravaMessage.value = "Connecting your Polar account…"
        viewModelScope.launch {
            val tokens = com.rork.mindsetframestracker.integrations.PolarClient
                .exchangeCodeForTokens(code)
            if (tokens == null) {
                _stravaMessage.value =
                    "Polar connection failed — couldn't exchange the sign-in code. " +
                        "Check your connection and try again."
                return@launch
            }
            if (tokens.userId == null) {
                _stravaMessage.value =
                    "Polar connection failed — no user id returned. Try again."
                return@launch
            }
            // Register user with Polar AccessLink (required before ANY data
            // call works — an unregistered user gets 403 on every endpoint).
            val registered = com.rork.mindsetframestracker.integrations.PolarClient
                .registerUser(tokens.accessToken)
            if (!registered) {
                _stravaMessage.value =
                    "Polar connected, but AccessLink registration failed. " +
                        "Try disconnecting and connecting again."
                return@launch
            }
            onPolarTokensReceived(tokens)
        }
    }

    fun disconnectStrava() {
        update {
            it.copy(
                settings = it.settings.copy(
                    stravaAccessToken = null,
                    stravaRefreshToken = null,
                    stravaExpiresAt = 0,
                ),
            )
        }
        _stravaMessage.value = "Strava disconnected."
    }

    /**
     * Pulls recent Strava activities into [habitId]. Refreshes the access
     * token through the Edge Function first when it is about to expire.
     *
     * ## The link gate
     *
     * Import is refused unless [habitId] owns the Strava link. This is the whole
     * fix for the misattribution defect: `fetchRecentActivities` asks Strava for
     * the **athlete's** last ten activities with no habit filter, so the payload
     * is account-wide. Called for every sport habit — which is what the old sweep
     * did — one workout was appended under each habit's own id
     * (`strava_<activityId>`, so the rows were distinct and nothing deduped
     * them) and the weekly rollup counted it once per habit. The second habit
     * normally lost the race (blank activity type, expired token), which is
     * exactly why the first habit to fail left the next one to absorb the whole
     * account report.
     */
    fun syncStravaActivities(habitId: String, activityType: String) {
        val settings = _state.value.settings
        val refresh = settings.stravaRefreshToken
        if (refresh.isNullOrBlank()) {
            _stravaMessage.value = "Connect Strava first."
            return
        }
        if (!isTrackerLinked(habitId, TrackerProvider.STRAVA)) {
            _stravaMessage.value =
                "Strava isn't linked to this habit — open the habit and link it there."
            return
        }
        viewModelScope.launch {
            val current = StravaTokens(
                accessToken = settings.stravaAccessToken.orEmpty(),
                refreshToken = refresh,
                expiresAt = settings.stravaExpiresAt,
            )
            val fresh = StravaAuthClient.refreshTokenIfNeeded(current).getOrElse {
                _stravaMessage.value = "Strava session expired — please reconnect."
                return@launch
            }
            if (fresh != current) saveStravaTokens(fresh)
            StravaAuthClient.fetchRecentActivities(getApplication(), fresh.accessToken, habitId, activityType)
                .onSuccess { count ->
                    update { it.copy(settings = it.settings.copy(stravaLastSyncMs = System.currentTimeMillis())) }
                    _stravaMessage.value =
                        if (count > 0) "Imported $count Strava activities." else "No new Strava activities yet."
                }
                .onFailure { _stravaMessage.value = "Couldn't fetch Strava activities. Try again later." }
        }
    }

    /**
     * Links [provider] to [habitId], taking it from whichever habit held it.
     *
     * This is the per-habit connect the request asks for: the link belongs to the
     * habit, so the user opens their Walk habit and links Strava there, rather
     * than finding a global switch in a separate place.
     *
     * Returns false when [habitId] no longer exists (a dialog left open across a
     * delete), in which case nothing is written — silently linking to a vanished
     * habit would create the orphaned binding the whole model exists to prevent.
     */
    fun linkTrackerForHabit(habitId: String, provider: TrackerProvider): Boolean {
        val before = _state.value.habits
        val result = HabitTrackerLinks.bindFor(before, habitId, provider)
        if (!result.applied) return false
        update { it.copy(habits = result.habits) }
        val name = before.firstOrNull { it.id == habitId }?.name ?: "this habit"
        // A transfer is reported rather than performed silently: the habit that
        // lost the link stops receiving data, and the user needs to know that
        // happened because they caused it from a different screen.
        result.takenFrom?.let { previous ->
            _stravaMessage.value =
                "${provider.label} now feeds $name. It was linked to ${previous.name} and has been moved."
        }
        return true
    }

    /**
     * Unlinks [provider] from [habitId] only.
     *
     * Scoped to the one habit on purpose. "This habit no longer uses Strava" and
     * "my Strava account is no longer connected" are different intentions, and
     * treating the first as the second is the class of bug that deleted habits
     * when a screen-time limit was edited. Disconnecting the account clears the
     * tokens and is a separate action.
     */
    fun unlinkTrackerForHabit(habitId: String, provider: TrackerProvider) {
        val habits = HabitTrackerLinks.unbindFor(_state.value.habits, habitId, provider)
        if (habits == _state.value.habits) return
        update { it.copy(habits = habits) }
        // Says outright that the account survived, because the old row read
        // "Disconnect" and a user who wanted only to stop tracking one habit had
        // every reason to fear it would unlink Strava everywhere.
        _stravaMessage.value =
            "${provider.label} unlinked from this habit. Your ${provider.label} account stays connected."
    }

    /**
     * True when [provider] is linked to [habitId] — the gate every import path
     * must pass before it writes a record.
     *
     * Read from the live state on each call rather than captured, so a sweep in
     * flight cannot import on a link the user has just removed.
     */
    private fun isTrackerLinked(habitId: String, provider: TrackerProvider): Boolean =
        HabitTrackerLinks.of(_state.value.habits).allows(habitId, provider)

    private fun saveStravaTokens(tokens: StravaTokens) {
        update {
            it.copy(
                settings = it.settings.copy(
                    stravaAccessToken = tokens.accessToken,
                    stravaRefreshToken = tokens.refreshToken,
                    stravaExpiresAt = tokens.expiresAt,
                ),
            )
        }
    }

    // ── Polar connection ────────────────────────────────────────────

    fun isPolarConnected(): Boolean = !_state.value.settings.polarAccessToken.isNullOrBlank()

    /** Called after Polar OAuth callback delivers tokens. */
    fun onPolarTokensReceived(tokens: com.rork.mindsetframestracker.integrations.PolarTokens) {
        update {
            it.copy(settings = it.settings.copy(
                polarAccessToken = tokens.accessToken,
                polarUserId = tokens.userId ?: 0,
            ))
        }
        _stravaMessage.value = "Polar account authenticated and connected. You can enable auto-sync in Settings > Activity sync."
    }

    /** Syncs today's Polar steps onto [habitId]. */
    fun syncPolarToHabit(habitId: String, activityType: String) {
        val settings = _state.value.settings
        val token = settings.polarAccessToken
        if (token.isNullOrBlank()) {
            _stravaMessage.value = "Connect Polar first (Settings > Activity sync)."
            return
        }
        if (!isTrackerLinked(habitId, TrackerProvider.POLAR)) {
            _stravaMessage.value =
                "Polar isn't linked to this habit — open the habit and link it there."
            return
        }
        if (settings.polarUserId == 0L) {
            // Legacy connection made before the user id was captured — the
            // transaction endpoints need it, so ask for a quick reconnect.
            _stravaMessage.value =
                "Please reconnect Polar (Settings > Activity sync) to finish upgrading the integration."
            return
        }
        viewModelScope.launch {
            val ok = com.rork.mindsetframestracker.integrations.PolarClient
                .syncTodayToHabit(getApplication(), token, settings.polarUserId, habitId, activityType)
            if (ok) {
                update { it.copy(settings = it.settings.copy(polarLastSyncMs = System.currentTimeMillis())) }
            }
            _stravaMessage.value =
                if (ok) "Today's steps synced from Polar."
                else "No new step data from Polar yet — sync your Polar device with Polar Flow first, then try again."
        }
    }

    // ── Screen-time habits (UsageStats) ──────────────────────────────

    /**
     * Evaluates every screen-time habit against today's measured app usage:
     * a habit auto-completes for today while the monitored app's foreground
     * time is at or under its budget, and un-completes when the limit is
     * blown. Yesterday is also settled (its full-day usage is final).
     *
     * Called on app start / resume and after a screen-time habit is added.
     * No-op when the Usage Access permission is missing.
     *
     * Measurement is a **single batched event scan** for every monitored
     * package at once, rather than one query per habit per day. That matters
     * for two reasons: `UsageStatsManager.queryEvents` is the expensive call
     * here, and the per-day attribution the UI shows must come from the same
     * numbers the check-in is decided on — otherwise the limit row and the
     * habit's own verdict could disagree.
     */
    fun evaluateScreenTimeHabits() {
        val app = getApplication<Application>()
        val monitor = com.rork.mindsetframestracker.integrations.ScreenTimeMonitor
        if (!monitor.hasPermission(app)) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val data = _state.value
            val screenHabits = data.habits.filter {
                it.monitoredPackage != null && it.screenTimeLimitMinutes != null
            }
            if (screenHabits.isEmpty()) return@launch

            val todayKey = Dates.todayKey()
            val yesterdayKey = Dates.key(java.time.LocalDate.now().minusDays(1))
            val todayStart = monitor.dayStartMillis(0)
            val yesterdayStart = monitor.dayStartMillis(-1)

            // One scan covering yesterday..now answers for every habit.
            val usage = monitor.dailyUsageMinutes(
                app,
                screenHabits.mapNotNull { it.monitoredPackage }.distinct(),
                yesterdayStart,
                System.currentTimeMillis(),
            ) ?: return@launch

            var changed = false
            var updatedCheckIns = data.checkIns

            for (habit in screenHabits) {
                val pkg = habit.monitoredPackage ?: continue
                val limit = habit.screenTimeLimitMinutes ?: continue
                val byDay = usage[pkg] ?: emptyMap()

                // Today: live status — done while under the limit.
                val usedToday = byDay[todayStart]
                if (usedToday != null) {
                    val underLimit = usedToday <= limit
                    val days = updatedCheckIns[habit.id].orEmpty().toMutableSet()
                    val isChecked = todayKey in days
                    if (underLimit && !isChecked) {
                        days.add(todayKey); changed = true
                    } else if (!underLimit && isChecked) {
                        days.remove(todayKey); changed = true
                    }
                    if (underLimit || isChecked) {
                        updatedCheckIns = updatedCheckIns + (habit.id to days.toList())
                    }
                }

                // Yesterday: final settlement (only ever marks success — a
                // blown day just stays unchecked).
                val usedYesterday = byDay[yesterdayStart]
                if (usedYesterday != null && usedYesterday <= limit) {
                    val days = updatedCheckIns[habit.id].orEmpty().toMutableSet()
                    if (yesterdayKey !in days) {
                        days.add(yesterdayKey); changed = true
                        updatedCheckIns = updatedCheckIns + (habit.id to days.toList())
                    }
                }
            }

            if (changed) {
                val finalCheckIns = updatedCheckIns
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    update { it.copy(checkIns = finalCheckIns) }
                    refreshCompanionUnlocks()
                    queueSync()
                }
            }
        }
    }

    /**
     * Reconciles the user's chosen screen-time limits with the habits that
     * currently exist: creates a habit for each newly-limited app, updates the
     * limit on apps already monitored, and removes the habit for any app the
     * user cleared.
     *
     * Reconciliation (rather than create-only) is what lets the manager be a
     * single editable list: the user can change a limit or drop an app without
     * a separate remove flow, and re-saving the same set is a no-op.
     *
     * Returns the number of apps now limited, so the caller can tell the user
     * what actually happened.
     */
    fun applyScreenTimeLimits(
        limits: List<com.rork.mindsetframestracker.data.ScreenTimeLimitInput>,
        /**
         * The packages the picker was SEEDED with when it opened.
         *
         * A screen-time habit is only ever dropped when its package was in this
         * set and the user has since cleared it. Anything else — a limit that
         * had not loaded yet, a habit that arrived from a cloud pull while the
         * sheet was open — can therefore never be read as a removal, which is
         * the path that deleted habits the user never touched.
         *
         * The default lives in `planScreenTimeLimits` and resolves to every
         * screen-time habit currently present, so an omitted argument means "we
         * were not told what was shown" rather than "nothing may be removed" —
         * the latter silently disabled every removal. Passing null keeps that
         * resolution in the one place the rule is defined.
         */
        removablePackages: Set<String>? = null,
    ): Int {
        // The reconciliation is pure and lives in
        // [com.rork.mindsetframestracker.data.planScreenTimeLimits], so the rule
        // "a screen-time save never deletes a habit it was not shown" is a
        // property a unit test asserts rather than a promise about this
        // `update { }` block.
        val plan = com.rork.mindsetframestracker.data.planScreenTimeLimits(
            current = _state.value,
            limits = limits,
            // Null means "we were not told what the user was shown", which
            // resolves to every limit that currently exists — not to "nothing
            // may be removed". The old `emptySet()` default silently disabled
            // every removal for any caller that omitted the argument.
            removablePackages = removablePackages
                ?: _state.value.habits
                    .filter { it.isScreenTimeHabit }
                    .mapNotNull { it.monitoredPackage }
                    .toSet(),
        )

        update { data ->
            data.copy(habits = plan.habits, checkIns = plan.checkIns)
        }

        // Push the deletions server-side too, so a removed limit does not
        // reappear after a restore on another device. pushSnapshot only
        // upserts, so a locally-removed habit would otherwise survive in
        // Supabase and come back on the next pull. The ids come from the plan,
        // because the server deletion queue is keyed by HABIT id (not package).
        plan.removed.forEach { supabaseSync.queueHabitDeletion(it.id) }

        queueSync()
        evaluateScreenTimeHabits()
        return _state.value.habits.count { it.isScreenTimeHabit }
    }


    fun disconnectPolar() {
        update {
            it.copy(settings = it.settings.copy(
                polarAccessToken = null,
                polarUserId = 0,
                polarLastSyncMs = 0,
            ))
        }
        _stravaMessage.value = "Polar disconnected."
    }

    // ── Health Connect (Google) connection ───────────────────────────

    /**
     * One-shot flag: when set to true, AppNavigation picks it up and
     * launches the Health Connect permission dialog. After the result
     * comes back, [onHealthConnectPermissionResult] is called.
     */
    private val _healthConnectPermissionRequested = MutableStateFlow(false)
    val healthConnectPermissionRequested: StateFlow<Boolean> =
        _healthConnectPermissionRequested.asStateFlow()

    /**
     * Request the Health Connect permission dialog. The user MUST grant
     * permissions before we mark Health Connect as connected.
     *
     * Pre-checks that the Health Connect SDK is actually installed /
     * available on the device before requesting permissions. If HC
     * isn't available the user gets an error message instead of a
     * silently-ignored permission launch.
     */
    fun requestHealthConnectPermissions() {
        val status = com.rork.mindsetframestracker.integrations
            .MindsetHealthConnectClient.checkStatus(getApplication())
        when (status) {
            is com.rork.mindsetframestracker.integrations.HealthConnectStatus.NotInstalled -> {
                _stravaMessage.value =
                    "Health Connect is not available on this device. " +
                    "Please install the Health Connect app from the Google Play Store or your device's app store, then try again."
            }
            is com.rork.mindsetframestracker.integrations.HealthConnectStatus.UpdateRequired -> {
                _stravaMessage.value =
                    "Health Connect needs to be updated before it can be used. " +
                    "Please update the Health Connect app in your app store, then try again."
            }
            else -> {
                viewModelScope.launch {
                    // If every permission is ALREADY granted, Health Connect
                    // will not show a dialog at all and the result contract
                    // reports an empty set — which used to read as "not
                    // granted". Detect that case up front and just connect.
                    val alreadyGranted = com.rork.mindsetframestracker.integrations
                        .MindsetHealthConnectClient.hasAllPermissions(getApplication())
                    if (alreadyGranted) {
                        update { it.copy(settings = it.settings.copy(healthConnectConnected = true)) }
                        _stravaMessage.value = "Health Connect connected — permissions granted."
                    } else {
                        // SDK is available — request the runtime permissions.
                        _healthConnectPermissionRequested.value = true
                    }
                }
            }
        }
    }

    /** Called once the permission launcher has fired. */
    fun consumeHealthConnectPermissionRequest() {
        _healthConnectPermissionRequested.value = false
    }

    /**
     * Called from the permission-result callback. Only marks Health
     * Connect as connected when the required permissions were actually
     * granted; shows an error message otherwise.
     *
     * The callback payload alone is NOT trusted: on several devices /
     * Health Connect versions the result contract returns an EMPTY set
     * even after the user tapped "Allow" (and always returns empty when
     * the permissions were already granted on a previous attempt). The
     * authoritative source is PermissionController.getGrantedPermissions(),
     * so we re-query it before deciding.
     */
    fun onHealthConnectPermissionResult(granted: Set<String>) {
        viewModelScope.launch {
            val allGrantedInCallback = com.rork.mindsetframestracker.integrations
                .MindsetHealthConnectClient.requiredPermissions.all { it in granted }
            val actuallyGranted = allGrantedInCallback ||
                com.rork.mindsetframestracker.integrations
                    .MindsetHealthConnectClient.hasAllPermissions(getApplication())
            if (actuallyGranted) {
                update { it.copy(settings = it.settings.copy(healthConnectConnected = true)) }
                _stravaMessage.value = "Health Connect connected — permissions granted."
            } else {
                // Do NOT mark as connected — permissions are genuinely missing.
                update { it.copy(settings = it.settings.copy(healthConnectConnected = false)) }
                _stravaMessage.value =
                    "Health Connect permissions were not granted. Open the Health Connect app " +
                        "> App permissions > Mindset Frames and allow Steps and Sleep, or tap " +
                        "Connect to try again."
            }
        }
    }

    fun setHealthConnectConnected(connected: Boolean) {
        update { it.copy(settings = it.settings.copy(healthConnectConnected = connected)) }
        _stravaMessage.value = if (connected) "Health Connect connected." else "Health Connect disconnected."
    }

    /**
     * Verifies that Health Connect permissions are still valid before syncing.
     * If permissions were revoked since the last session, marks as disconnected.
     */
    private suspend fun verifyHealthConnectPermissions(): Boolean {
        val hasPerms = com.rork.mindsetframestracker.integrations
            .MindsetHealthConnectClient.hasAllPermissions(getApplication())
        if (!hasPerms && _state.value.settings.healthConnectConnected) {
            update { it.copy(settings = it.settings.copy(healthConnectConnected = false)) }
            _stravaMessage.value = "Health Connect permissions were revoked. Please reconnect."
        }
        return hasPerms
    }

    /** Syncs today's Health Connect steps onto [habitId]. */
    fun syncHealthConnectToHabit(habitId: String, activityType: String) {
        if (!_state.value.settings.healthConnectConnected) {
            _stravaMessage.value = "Connect Health Connect first (Settings > Activity sync)."
            return
        }
        if (!isTrackerLinked(habitId, TrackerProvider.HEALTH_CONNECT)) {
            _stravaMessage.value =
                "Health Connect isn't linked to this habit — open the habit and link it there."
            return
        }
        viewModelScope.launch {
            // Verify permissions are still valid before attempting a sync
            if (!verifyHealthConnectPermissions()) return@launch
            // A sleep habit reads last night's sleep, not today's movement: the
            // two are different questions and a sleep habit had no way to get
            // its number at all, since the sleep reader was never called. Every
            // other habit takes today's measured activity.
            val isSleepHabit = activityType == com.rork.mindsetframestracker.integrations.ActivityMonitor
                .SLEEP_ACTIVITY_TYPE
            val ok = if (isSleepHabit) {
                com.rork.mindsetframestracker.integrations.ActivityMonitor
                    .captureSleepForHabit(getApplication(), habitId, activityType) != null
            } else {
                com.rork.mindsetframestracker.integrations.MindsetHealthConnectClient
                    .syncTodayToHabit(getApplication(), habitId, activityType)
            }
            if (ok) {
                update { it.copy(settings = it.settings.copy(healthConnectLastSyncMs = System.currentTimeMillis())) }
            }
            // Named for what was actually imported, and phrased so a partial
            // import (steps but no heart rate, say) is not misreported as a
            // full success of every metric.
            _stravaMessage.value = when {
                ok && isSleepHabit -> "Last night's sleep synced from Health Connect."
                ok -> "Today's activity synced from Health Connect."
                else -> "No new data from Health Connect yet — open Health Connect and check permissions."
            }
        }
    }

    fun disconnectHealthConnect() {
        update {
            it.copy(settings = it.settings.copy(
                healthConnectConnected = false,
                healthConnectLastSyncMs = 0,
            ))
        }
        _stravaMessage.value = "Health Connect disconnected."
    }

    // ── Integration auto-sync toggles ───────────────────────────────

    fun setPolarAutoSync(enabled: Boolean) {
        update { it.copy(settings = it.settings.copy(polarAutoSync = enabled)) }
    }

    fun setHealthConnectAutoSync(enabled: Boolean) {
        update { it.copy(settings = it.settings.copy(healthConnectAutoSync = enabled)) }
    }

    fun setStravaAutoSync(enabled: Boolean) {
        update { it.copy(settings = it.settings.copy(stravaAutoSync = enabled)) }
    }

    /**
     * Auto-sync trigger: called once after app start to silently pull
     * latest data from all connected integrations that have auto-sync on.
     *
     * Each integration is only synced when:
     *  1. The user has completed the authentication/authorisation flow
     *     (not just "marked as connected" — actual tokens or permissions).
     *  2. The user has explicitly enabled auto-sync for that integration.
     *  3. There is at least one fitness habit to sync into.
     */
    fun runAutoSync() {
        val s = _state.value.settings
        // Each habit is swept ONLY by the providers actually linked to it.
        //
        // The previous sweep ran every connected provider against every
        // icon-compatible habit, and that is the defect: Strava's payload is the
        // athlete's last ten activities with NO habit filter, so running it once
        // per sport habit filed one account-wide report under each habit's own
        // id — the ids are `strava_<activityId>`, distinct rows, nothing deduped,
        // so the weekly rollup counted the same workout once per habit. Polar is
        // worse: its daily step roll-up belongs to no workout at all, and it was
        // written onto every sport habit.
        //
        // An unbound habit is now skipped entirely. That is the correct outcome,
        // not a regression — there is no tracker data that belongs to it, and
        // guessing was how the data ended up on the wrong habit.
        val links = HabitTrackerLinks.of(_state.value.habits)
        val linked = links.linkedHabits(_state.value.habits)
        if (linked.isEmpty()) return

        var syncedAny = false
        linked.forEach { (habit, providers) ->
            val iconId = habit.iconId ?: return@forEach
            // Polar — only with a real OAuth token AND auto-sync enabled.
            if (TrackerProvider.POLAR in providers && isPolarConnected() && s.polarAutoSync) {
                // The provider's OWN vocabulary, resolved from the icon. Passing
                // the raw iconId here is what put an unplaceable activity type on
                // every Health Connect record.
                syncPolarToHabit(habit.id, stravaActivityTypeFor(iconId))
                syncedAny = true
            }
            // Health Connect — permissions re-verified inside the sync call,
            // because they can be revoked from the Health Connect app at any time
            if (TrackerProvider.HEALTH_CONNECT in providers &&
                s.healthConnectConnected &&
                s.healthConnectAutoSync
            ) {
                syncHealthConnectToHabit(habit.id, iconId)
                syncedAny = true
            }
            // Strava — only with a refresh token AND auto-sync enabled
            if (TrackerProvider.STRAVA in providers &&
                !s.stravaRefreshToken.isNullOrBlank() &&
                s.stravaAutoSync
            ) {
                syncStravaActivities(habit.id, stravaActivityTypeFor(iconId))
                syncedAny = true
            }
        }
        // One summary for the whole sweep, replacing the last per-habit message.
        // Without this the user is told "Imported 4 Strava activities." about
        // whichever habit happened to sync last, which reads as though only that
        // one habit was updated.
        if (syncedAny) {
            _stravaMessage.value =
                "Checked ${linked.size} linked activity habit(s) against your trackers."
        }
    }

    // ── Companion Studio task unlocks ──────────────────────────

    private val _newCompanionUnlocks = MutableStateFlow<List<String>>(emptyList())

    val newCompanionUnlocks: StateFlow<List<String>> = _newCompanionUnlocks.asStateFlow()

    fun refreshCompanionUnlocks() {
        val data = _state.value
        val newly = AvatarCatalog.taskUnlockableIds(data)
            .filter { it !in data.settings.companionUnlocks }
        if (newly.isEmpty()) return
        update { d ->
            d.copy(
                settings = d.settings.copy(
                    companionUnlocks = d.settings.companionUnlocks + newly,
                )
            )
        }
        _newCompanionUnlocks.value = (_newCompanionUnlocks.value + newly).distinct()
    }

    fun consumeCompanionUnlocks() {
        _newCompanionUnlocks.value = emptyList()
    }

    private val syncCompanionReminders: () -> Unit = {}

    private fun retryPendingSync() {
        if (supabaseSync.isConfigured && supabaseSync.isSignedIn && supabaseSync.hasPendingPush) {
            queueSync()
        }
    }

    private fun alignDailyBackup() {
        if (supabaseSync.isConfigured && supabaseSync.isSignedIn) {
            CloudBackupWorker.schedule(getApplication())
        } else {
            CloudBackupWorker.cancel(getApplication())
        }
    }

    private val _syncState = MutableStateFlow(
        SyncUiState(
            available = supabaseSync.isConfigured,
            huaweiAvailable = supabaseSync.isConfigured &&
                HuaweiAuthClient.isHmsAvailable(application),
            email = supabaseSync.sessionEmail,
            provider = supabaseSync.sessionProvider,
            lastSyncAtMs = supabaseSync.lastSyncAtMs,
        )
    )
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    // ── Tip purchase result (delivered via MainActivity.onActivityResult,
    // since Huawei IAP's resolution must launch through the classic
    // startActivityForResult path — see TipBilling.kt) ────────────────────

    private val _tipMessage = MutableStateFlow<String?>(null)
    val tipMessage: StateFlow<String?> = _tipMessage.asStateFlow()

    /**
     * The tip product the user is currently buying. TipBilling launches the
     * payment sheet through the classic startActivityForResult path, so
     * MainActivity.onActivityResult needs this to attribute the result — and
     * to retry the purchase after an IAP environment resolution (e.g. the
     * user signs in to Huawei ID from the isEnvReady prompt).
     */
    var pendingTipProductId: String = ""
        private set

    /** Remember which product the in-flight tip purchase belongs to. */
    fun onTipPurchaseStarted(productId: String) {
        pendingTipProductId = productId
    }

    /**
     * Retries a subscription purchase after the IAP environment became ready
     * (the user completed the sign-in / HMS Core resolution launched by
     * [com.rork.mindsetframestracker.billing.SubscriptionBilling.checkEnvironment]).
     *
     * This mirrors [retryPendingTipPurchase], which already existed for tips. The
     * subscription path was missing it: its ENV_READY branch only logged the
     * outcome, so a user who signed in to Huawei ID from the isEnvReady prompt
     * was returned to the app with nothing happening — they had to find the plan
     * again and tap it a second time. No-op when there is no pending product.
     */
    fun retryPendingSubscriptionPurchase(activity: android.app.Activity) {
        val productId = pendingSubscriptionProductId
        if (productId.isBlank()) return
        pendingSubscriptionProductId = ""
        // Surface failures through the same channel the sheet uses, since the
        // sheet may already be gone by the time the resolution returns.
        _subscriptionMessage.value = null
        com.rork.mindsetframestracker.billing.SubscriptionBilling.purchase(
            activity = activity,
            productId = productId,
            onError = { message -> _subscriptionMessage.value = message },
        )
    }

    /**
     * Retries the tip purchase after the IAP environment became ready (the
     * user completed the sign-in / HMS Core resolution launched by
     * [com.rork.mindsetframestracker.billing.TipBilling.checkEnvironment]).
     * No-op when there is no pending product.
     */
    fun retryPendingTipPurchase(activity: android.app.Activity) {
        val productId = pendingTipProductId
        if (productId.isBlank()) return
        pendingTipProductId = ""
        com.rork.mindsetframestracker.billing.TipBilling.purchase(
            activity = activity,
            productId = productId,
            onError = { message -> onTipPurchaseResult(message) },
        )
    }

    /** Called from MainActivity.onActivityResult with a user-facing result message, or null for a silent cancel. */
    fun onTipPurchaseResult(message: String?) {
        _tipMessage.value = message
    }

    /**
     * Fire-and-forget server-side record of a successful tip: the signed
     * purchase payload is sent to the tip-purchase Edge Function, which
     * verifies it with Huawei's Order Service and stores it in
     * tip_purchases. A failure here never affects the user-facing flow —
     * Huawei already completed the payment on-device.
     */
    fun recordTipPurchase(purchaseData: String, signature: String?) {
        if (purchaseData.isBlank()) return
        viewModelScope.launch {
            runCatching { supabaseSync.recordTipPurchase(purchaseData, signature) }
                .onFailure {
                    if (BuildConfig.DEBUG) Log.w("AppViewModel", "Tip record failed: ${it.message}")
                }
        }
    }

    /**
     * Reads the server-side founding-member eligibility for this install.
     * Called from the premium sheet when it opens. The sheet fails closed, so
     * any error here simply hides the Founding Member card.
     */
    suspend fun checkFoundingMemberEligibility(): com.rork.mindsetframestracker.data.SupabaseSync.FoundingEligibility =
        supabaseSync.checkFoundingMemberEligibility()

    /**
     * Server-side record of a founding-member claim, from a successful
     * founding-tier purchase.
     *
     * Unlike a tip this is not fire-and-forget in spirit: the server verifies
     * the signed purchase with Huawei before consuming a slot, so the outcome
     * is only known once it answers. The user has already been granted the
     * entitlement locally (Huawei completed the payment), so a refusal here
     * never revokes it \u2014 but it must be visible, because it means the founding
     * slot was not recorded and support may need to reconcile it.
     */
    fun recordFoundingMemberClaim(
        planId: String,
        productId: String,
        purchaseData: String,
        signature: String?,
    ) {
        viewModelScope.launch {
            val charged = runCatching {
                supabaseSync.recordFoundingMemberClaim(planId, productId, purchaseData, signature)
            }.getOrElse { e ->
                if (BuildConfig.DEBUG) Log.w("AppViewModel", "Founding claim record failed: ${e.message}")
                false
            }
            if (!charged) {
                // Distinguish "we could not verify/record it" from silence. The
                // entitlement stands; the slot bookkeeping needs a look.
                _subscriptionMessage.value =
                    "Premium unlocked \u2014 welcome aboard! \uD83C\uDF89\n" +
                        "We couldn't register your founding slot just yet; it will be " +
                        "reconciled automatically, or contact support if it doesn't appear."
            }
        }
    }

    /** Call after the message has been shown once, so it doesn't reappear on rotation/recomposition. */
    fun consumeTipMessage() {
        _tipMessage.value = null
    }

    // ── Save-your-data prompt ────────────────────────────────

    private val _showAuthPrompt = MutableStateFlow(false)
    val showAuthPrompt: StateFlow<Boolean> = _showAuthPrompt.asStateFlow()

    fun maybeShowAuthPrompt() {
        if (_state.value.settings.authPromptDone) return
        if (!supabaseSync.isConfigured || supabaseSync.isSignedIn) return
        if (!_state.value.settings.onboardingDone) return
        markAuthPromptDone()
        _showAuthPrompt.value = true
    }

    fun openAuthPrompt() {
        if (!supabaseSync.isConfigured || supabaseSync.isSignedIn) return
        markAuthPromptDone()
        _showAuthPrompt.value = true
    }

    private fun markAuthPromptDone() {
        if (!_state.value.settings.authPromptDone) {
            update { it.copy(settings = it.settings.copy(authPromptDone = true)) }
        }
    }

    fun dismissAuthPrompt() {
        _showAuthPrompt.value = false
    }

    fun setNewPassword(newPassword: String) {
        if (_syncState.value.busy) return
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            rejectAuth("Use at least $MIN_PASSWORD_LENGTH characters for your new password")
            return
        }
        _syncState.value = _syncState.value.copy(busy = true, message = null, isError = false)
        viewModelScope.launch {
            val error = supabaseSync.updatePassword(newPassword)
            _syncState.value = if (error != null) {
                _syncState.value.copy(busy = false, message = error, isError = true)
            } else {
                _syncState.value.copy(
                    busy = false,
                    showSetNewPasswordSheet = false,
                    message = "Password updated successfully!",
                    isError = false,
                )
            }
        }
    }

    fun dismissSetNewPasswordSheet() {
        _syncState.value = _syncState.value.copy(showSetNewPasswordSheet = false)
    }

    // ── Privacy consent ────────────────────────────────────────

    fun acceptPrivacyConsent() {
        update { it.copy(settings = it.settings.copy(privacyConsentAccepted = true)) }
    }

    fun isPrivacyConsentAccepted(): Boolean = _state.value.settings.privacyConsentAccepted

    private fun update(transform: (AppData) -> AppData) {
        val next = transform(_state.value)
        _state.value = next
        repository?.save(next)
    }

    private fun validateCredentials(email: String, password: String, forSignUp: Boolean): String? {
        val trimmed = email.trim()
        return when {
            trimmed.isEmpty() || password.isEmpty() -> "Enter your email and password"
            !Patterns.EMAIL_ADDRESS.matcher(trimmed).matches() -> "Enter a valid email address"
            forSignUp && password.length < MIN_PASSWORD_LENGTH ->
                "Use at least $MIN_PASSWORD_LENGTH characters for your password"
            else -> null
        }
    }

    private fun rejectAuth(message: String) {
        _syncState.value = _syncState.value.copy(busy = false, message = message, isError = true)
    }

    fun signIn(email: String, password: String) {
        if (_syncState.value.busy) return
        validateCredentials(email, password, forSignUp = false)?.let { rejectAuth(it); return }
        _syncState.value = _syncState.value.copy(busy = true, message = null, isError = false)
        viewModelScope.launch {
            val error = supabaseSync.signIn(email, password)
            if (error != null) {
                // Account exists but the email was never confirmed — resend
                // the confirmation link automatically so the user isn't stuck
                // in a "can't sign in / can't sign up" loop.
                if ("email not confirmed" in error.lowercase()) {
                    val resendError = supabaseSync.resendSignupConfirmation(email)
                    _syncState.value = _syncState.value.copy(
                        busy = false,
                        message = if (resendError == null) {
                            "Your account exists but the email isn't verified yet. " +
                                "We've just sent a fresh confirmation link to ${email.trim()} — " +
                                "tap it, then sign in."
                        } else {
                            "Your account exists but the email isn't verified yet, and we " +
                                "couldn't resend the link right now. Try again in a minute."
                        },
                        isError = resendError != null,
                    )
                    return@launch
                }
                _syncState.value = _syncState.value.copy(
                    busy = false,
                    message = friendlySignInError(error),
                    isError = true,
                )
                return@launch
            }
            onSignedIn("Signed in")
        }
    }

    private fun friendlySignInError(raw: String): String {
        val normalized = raw.lowercase()
        return when {
            "invalid login credentials" in normalized || "invalid email or password" in normalized ->
                "That email or password doesn't match our records. If you haven't created an " +
                        "account yet, tap \"New here? Create an account\" below. Otherwise " +
                        "double-check for typos or use \"Forgot password?\" to reset it."
            "email not confirmed" in normalized ->
                "Please confirm your email first — check your inbox for the verification link we sent."
            else -> raw
        }
    }

    fun signInWithHuawei(idToken: String, email: String?, displayName: String? = null) {
        if (_syncState.value.busy) return
        if (!supabaseSync.isConfigured) {
            rejectAuth("Cloud sync is not configured")
            return
        }
        _syncState.value = _syncState.value.copy(busy = true, message = null, isError = false)
        viewModelScope.launch {
            val error = supabaseSync.signInWithHuawei(idToken, email, displayName)
            if (error != null) {
                _syncState.value = _syncState.value.copy(busy = false, message = error, isError = true)
            } else {
                onSignedIn("Signed in with Huawei ID")
            }
        }
    }

    fun onHuaweiSignInFailed(message: String) {
        rejectAuth(message)
    }

    private fun ensureRegionalLanguage() {
        val settings = _state.value.settings
        if (settings.freeRegionalLanguage != null) return
        val selectedNonEnglish = settings.language.takeIf { it !in universallyFreeLanguages }
        val regional = selectedNonEnglish
            ?: regionalLanguageFor(Locale.getDefault())
            ?: return
        update { it.copy(settings = it.settings.copy(freeRegionalLanguage = regional)) }
        queueSync()
    }

    fun handleAuthDeepLink(uri: Uri) {
        val signature = runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return
        if (!supabaseSync.consumeAuthLink(signature)) return

        val params = authLinkParams(uri)
        val errorCode = params["error_code"] ?: params["error"]
        if (errorCode != null) {
            val friendly = if (errorCode == "otp_expired" || errorCode == "access_denied") {
                "That email link has expired — request a fresh one from the sign-in screen."
            } else {
                "That email link couldn't be verified. Request a new one and try again."
            }
            _syncState.value = _syncState.value.copy(message = friendly, isError = true)
            return
        }

        val refreshToken = params["refresh_token"]
        val isRecovery = params["type"] == "recovery"

        if (!refreshToken.isNullOrBlank()) {
            if (_syncState.value.busy) return
            _syncState.value = _syncState.value.copy(busy = true, message = null, isError = false)
            viewModelScope.launch {
                val error = supabaseSync.signInWithRecoveredToken(refreshToken)
                if (error != null) {
                    _syncState.value = _syncState.value.copy(busy = false, message = error, isError = true)
                } else {
                    if (isRecovery) {
                        _syncState.value = _syncState.value.copy(
                            busy = false,
                            showSetNewPasswordSheet = true,
                            message = "Choose a new password for your account.",
                            isError = false,
                        )
                    } else {
                        onSignedIn("Email verified — you're signed in!")
                    }
                }
            }
            return
        }

        if (params["type"] != null && params["type"] != "manual") {
            _syncState.value = _syncState.value.copy(
                message = "Email verified! Sign in with your password to finish.",
                isError = false,
            )
            if (!supabaseSync.isSignedIn) openAuthPrompt()
        }
    }

    private fun authLinkParams(uri: Uri): Map<String, String> {
        val params = mutableMapOf<String, String>()
        runCatching {
            uri.queryParameterNames.forEach { name ->
                uri.getQueryParameter(name)?.let { params[name] = it }
            }
        }
        uri.fragment.orEmpty().split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator > 0) {
                val key = pair.substring(0, separator)
                val value = runCatching {
                    URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
                }.getOrNull()
                if (value != null) params[key] = value
            }
        }
        return params
    }

    private suspend fun onSignedIn(successMessage: String, showSavePrompt: Boolean = true) {
        restoreFromCloud()
        _syncState.value = _syncState.value.copy(
            busy = false,
            email = supabaseSync.sessionEmail,
            provider = supabaseSync.sessionProvider,
            message = successMessage,
            isError = false,
        )
        if (showSavePrompt && _state.value.settings.onboardingDone) {
            _showAuthPrompt.value = true
        }
        queueSync()
        alignDailyBackup()
    }

    fun signUp(email: String, password: String) {
        if (_syncState.value.busy) return
        validateCredentials(email, password, forSignUp = true)?.let { rejectAuth(it); return }
        _syncState.value = _syncState.value.copy(busy = true, message = null, isError = false, suggestSignIn = false)
        viewModelScope.launch {
            val message = supabaseSync.signUp(email, password)
            val signedIn = supabaseSync.sessionEmail != null
            val alreadyExists = message != null && !signedIn && isAlreadyRegisteredError(message)
            _syncState.value = _syncState.value.copy(
                busy = false,
                email = supabaseSync.sessionEmail,
                provider = supabaseSync.sessionProvider,
                message = when {
                    alreadyExists -> "An account with this email already exists. Please log in instead."
                    else -> message ?: "Account created — you're signed in"
                },
                isError = message != null && !signedIn && !message.startsWith("Account created"),
                suggestSignIn = alreadyExists,
            )
            if (signedIn) {
                queueSync()
                alignDailyBackup()
            }
        }
    }

    private fun isAlreadyRegisteredError(message: String): Boolean {
        val normalized = message.lowercase()
        return "already registered" in normalized || "already exists" in normalized || "user_already_exists" in normalized
    }

    fun consumeSuggestSignIn() {
        _syncState.value = _syncState.value.copy(suggestSignIn = false)
    }

    fun signOut() {
        if (supabaseSync.sessionProvider == "huawei") {
            HuaweiAuthClient.signOut(getApplication())
        }
        supabaseSync.signOut()
        alignDailyBackup()
        update { it.copy(settings = it.settings.copy(authPromptDone = false)) }
        _syncState.value = _syncState.value.copy(
            email = null,
            provider = null,
            lastSyncAtMs = 0L,
            message = "Signed out — your data stays on this device.",
            isError = false,
        )
    }

    fun deleteAccount() {
        if (_syncState.value.busy) return
        if (!supabaseSync.isConfigured || !supabaseSync.isSignedIn) return
        _syncState.value = _syncState.value.copy(busy = true, message = null, isError = false)
        viewModelScope.launch {
            val error = supabaseSync.deleteAccount()
            if (error != null) {
                _syncState.value = _syncState.value.copy(busy = false, message = error, isError = true)
                return@launch
            }
            queuedSyncJob?.cancel()
            alignDailyBackup()
            markAuthPromptDone()
            _syncState.value = _syncState.value.copy(
                busy = false,
                email = null,
                provider = null,
                lastSyncAtMs = 0L,
                message = "Account deleted — your cloud data is permanently erased. " +
                    "Your habits stay on this device.",
                isError = false,
            )
        }
    }

    fun clearSyncMessage() {
        if (_syncState.value.message != null) {
            _syncState.value = _syncState.value.copy(message = null)
        }
    }

    fun sendPasswordReset(email: String) {
        if (_syncState.value.busy) return
        val trimmed = email.trim()
        if (trimmed.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) {
            rejectAuth("Enter a valid email address")
            return
        }
        _syncState.value = _syncState.value.copy(busy = true, message = null, isError = false)
        viewModelScope.launch {
            val error = supabaseSync.sendPasswordReset(trimmed)
            _syncState.value = _syncState.value.copy(
                busy = false,
                message = error ?: "If an account exists for $trimmed, a reset link is on its way.",
                isError = error != null,
            )
        }
    }

    fun changePassword(currentPassword: String, newPassword: String) {
        if (_syncState.value.busy) return
        val email = _syncState.value.email
        if (_syncState.value.provider != "email" || email.isNullOrBlank()) {
            rejectAuth("Password changes are only available for email accounts.")
            return
        }
        if (currentPassword.isBlank()) {
            rejectAuth("Enter your current password")
            return
        }
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            rejectAuth("Use at least $MIN_PASSWORD_LENGTH characters for your new password")
            return
        }
        if (newPassword == currentPassword) {
            rejectAuth("Your new password must be different from the current one")
            return
        }
        _syncState.value = _syncState.value.copy(busy = true, message = null, isError = false)
        viewModelScope.launch {
            val reauthError = supabaseSync.signIn(email, currentPassword)
            if (reauthError != null) {
                _syncState.value = _syncState.value.copy(
                    busy = false,
                    message = "Current password is incorrect.",
                    isError = true,
                )
                return@launch
            }
            val error = supabaseSync.updatePassword(newPassword)
            _syncState.value = if (error != null) {
                _syncState.value.copy(busy = false, message = error, isError = true)
            } else {
                _syncState.value.copy(busy = false, message = "Password updated.", isError = false)
            }
        }
    }

    private suspend fun restoreFromCloud() {
        val (snapshot, error) = supabaseSync.pullSnapshot()
        if (snapshot == null) {
            if (error != null) {
                _syncState.value = _syncState.value.copy(message = error, isError = true)
            }
            return
        }
        update { data ->
            val localPins = data.habits.filter { it.isPinned }.map { it.id }.toSet()
            // Merged through the ONE named rule, not `distinctBy`. The inline
            // version kept the FIRST occurrence, and this list was built
            // cloud-first — so an unrelated background restore could silently
            // revert a habit the user had just edited, while every other
            // collection in this same function unions with LOCAL winning.
            // `mergeHabitsPreferringLocal` makes that tie-break explicit and
            // consistent; see its doc for why local is the right winner.
            //
            // The local side is `data` (this device's current state) and the
            // incoming side is `snapshot` (the cloud), which is the pairing the
            // old `snapshot.habits + data.habits` ordering inverted.
            val mergedHabits = mergeHabitsPreferringLocal(
                local = data.habits,
                incoming = snapshot.habits,
            ).habits
                .map { habit -> if (habit.id in localPins) habit.copy(isPinned = true) else habit }
            val mergedCheckIns = (data.checkIns.keys + snapshot.checkIns.keys).associateWith { habitId ->
                (data.checkIns[habitId].orEmpty() + snapshot.checkIns[habitId].orEmpty()).distinct()
            }
            // Restored tracking payloads. Union by entry id so re-restoring (or
            // restoring twice) can't duplicate a journal note or double-count
            // glasses of water, and local entries always win on a conflict.
            val mergedLogs = (data.habitLogs + snapshot.habitLogs)
                .associateBy { it.id }
                .values
                .sortedBy { it.recordedAtEpochMs }
            val mergedActivity = (data.activityRecords + snapshot.activityRecords)
                .associateBy { it.id }
                .values
                .sortedBy { it.timestamp }
            data.copy(
                habits = mergedHabits,
                checkIns = mergedCheckIns,
                moodHistory = snapshot.moodHistory + data.moodHistory,
                habitLogs = mergedLogs,
                activityRecords = mergedActivity,
            )
        }
    }

    private var lastSyncAttemptAt = 0L
    private var queuedSyncJob: kotlinx.coroutines.Job? = null
    private val queuedSyncDelayMs = 4_000L

    fun queueSync() {
        if (!supabaseSync.isConfigured || !supabaseSync.isSignedIn) return
        supabaseSync.hasPendingPush = true
        queuedSyncJob?.cancel()
        queuedSyncJob = viewModelScope.launch {
            kotlinx.coroutines.delay(queuedSyncDelayMs)
            while (_syncState.value.busy) {
                kotlinx.coroutines.delay(1_000)
            }
            if (!supabaseSync.isOnline) return@launch
            if (isBatteryLow(getApplication())) return@launch
            _syncState.value = _syncState.value.copy(busy = true)
            val error = supabaseSync.pushSnapshot(_state.value)
            // Same split as syncNow(): a partial push is not a failure, so it
            // clears the pending flag (no point re-sending the same payload) but
            // is surfaced as a warning rather than a red error.
            val partial = error != null && supabaseSync.lastPushPartial
            if (error == null || partial) {
                supabaseSync.hasPendingPush = false
                _syncState.value = _syncState.value.copy(
                    busy = false,
                    message = error,
                    isError = false,
                    isPartial = partial,
                    lastSyncAtMs = supabaseSync.lastSyncAtMs,
                )
            } else {
                _syncState.value = _syncState.value.copy(
                    busy = false,
                    message = error,
                    isError = true,
                    isPartial = false,
                )
            }
        }
    }

    fun retrySync() {
        lastSyncAttemptAt = 0L
        _syncState.value = _syncState.value.copy(message = null, isError = false, isPartial = false)
        syncNow()
    }

    fun syncNow() {
        val now = System.currentTimeMillis()
        val state = _syncState.value
        if (!supabaseSync.isConfigured || state.busy) return
        if (now - lastSyncAttemptAt < SYNC_COOLDOWN_MS) return
        if (!supabaseSync.isOnline) {
            if (supabaseSync.isSignedIn) supabaseSync.hasPendingPush = true
            _syncState.value = state.copy(
                message = "You're offline — your data is saved on this device and will back up when you're back online.",
                isError = false,
            )
            return
        }
        if (isBatteryLow(getApplication())) {
            if (supabaseSync.isSignedIn) supabaseSync.hasPendingPush = true
            _syncState.value = state.copy(
                message = "Low Power — sync is paused below 20% battery to conserve energy. Your data is saved on this device.",
                isError = false,
            )
            return
        }
        lastSyncAttemptAt = now
        _syncState.value = state.copy(busy = true, message = null, isError = false, isPartial = false)
        viewModelScope.launch {
            val error = supabaseSync.pushSnapshot(_state.value)
            // A partial push returns a message but must NOT be treated as a
            // failure: the cooldown reset and hasPendingPush clear. Doing
            // otherwise would retry forever against a schema change the user
            // has to apply, and would hide the caveat behind a red banner.
            val partial = error != null && supabaseSync.lastPushPartial
            if (error != null && !partial) {
                lastSyncAttemptAt = 0L
            } else {
                supabaseSync.hasPendingPush = false
            }
            _syncState.value = _syncState.value.copy(
                busy = false,
                message = error ?: "Backed up just now",
                isError = error != null && !partial,
                isPartial = partial,
                lastSyncAtMs = supabaseSync.lastSyncAtMs,
            )
        }
    }

    fun selectMood(mode: MoodMode) {
        update { it.copy(moodHistory = it.moodHistory + (Dates.todayKey() to mode)) }
        refreshCompanionUnlocks()
    }

    fun toggleHabitToday(habitId: String) {
        var completed = false
        update { data ->
            val today = Dates.todayKey()
            val days = data.checkIns[habitId].orEmpty().toMutableSet()
            // Captured here because the set is mutated in place: `completed`
            // is true only when this tap ADDED today's check-in.
            completed = days.add(today)
            if (!completed) days.remove(today)
            data.copy(checkIns = data.checkIns + (habitId to days.toList()))
        }
        refreshCompanionUnlocks()

        // A tap that COMPLETES a habit is the user telling us they just did it, so
        // capture what the device actually recorded instead of leaving the habit
        // as a bare label. Only on completion — un-checking must not record an
        // activity — and only for habits in the movement set, which the monitor
        // itself decides once it resolves the habit's activity type.
        if (completed) {
            runCatching {
                com.rork.mindsetframestracker.integrations.ActivityMonitor
                    .captureForHabit(getApplication(), habitId)
            }
        }
    }

    /**
     * Marks a habit as done for TODAY only — unlike [toggleHabitToday] this
     * never un-checks an already-completed day, it's purely additive. Used
     * right after the user confirms an alarm for a habit (new or re-edited):
     * setting the alarm now auto-records today's check-in instead of making
     * the user tap the habit a second time.
     */
    fun markHabitDoneToday(habitId: String) {
        update { data ->
            val today = Dates.todayKey()
            val days = data.checkIns[habitId].orEmpty().toMutableSet()
            if (days.add(today)) {
                data.copy(checkIns = data.checkIns + (habitId to days.toList()))
            } else {
                data
            }
        }
        refreshCompanionUnlocks()
        queueSync()
    }

    /**
     * Records what a habit's own tracking tool produced, and (by default)
     * marks the habit done for today.
     *
     * This is the single write path behind every tracking mode, so the shape of
     * the record is decided by [mode] and nothing else. The two side effects
     * are deliberately both here and both optional-by-argument:
     *
     *  - the **[HabitLogEntry]** is the payload — a measured duration, a journal
     *    title + text, a count. It is what makes the habit more than a label.
     *  - the **check-in** is the boolean that streaks, badges and the heatmap
     *    are built on. It is written alongside the entry so a logged habit
     *    counts, which is what the user expects from "I did this".
     *
     * Both were previously the job of a bare tap, which is exactly the problem:
     * a tap cannot express a duration or a sentence. Called with
     * [markDone] = false when the user is recording something without claiming
     * the day (journaling a note without counting it as done).
     */
    fun recordHabitTracking(
        habitId: String,
        mode: HabitTrackingMode,
        title: String? = null,
        note: String? = null,
        durationSeconds: Int? = null,
        count: Int? = null,
        unit: String? = null,
        markDone: Boolean = true,
        /**
         * Which of the habit's alarm times this record answers, when the user is
         * completing a *ring* rather than tapping the habit unprompted.
         *
         * The occurrence key is derived from it, which is what keeps the day's
         * records separate: without it, recording at 18:00 would be
         * indistinguishable from recording at 07:00, and the per-occurrence
         * history the user asked for would collapse back into one entry per day.
         */
        alarmMinutes: Int? = null,
    ) {
        if (habitId.isBlank()) return
        val today = Dates.todayKey()

        // One record per occurrence. Scoped to the occurrence rather than to the
        // day on purpose — an 18:00 walk must still record after a 07:00 one —
        // but a second answer to the SAME ring (a double tap, a re-shown sheet, a
        // re-delivered intent) must not add a second entry that reads as a second
        // walk.
        if (alarmMinutes != null &&
            _state.value.hasAnsweredOccurrence(habitId, today, alarmMinutes)
        ) {
            return
        }

        val entry = HabitLogEntry(
            habitId = habitId,
            dayKey = today,
            mode = mode,
            occurrenceKey = alarmMinutes?.let { occurrenceKeyFor(today, it) },
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            durationSeconds = durationSeconds?.takeIf { it > 0 },
            count = count?.takeIf { it > 0 },
            unit = unit?.trim()?.takeIf { it.isNotEmpty() },
            recordedAtEpochMs = System.currentTimeMillis(),
        )

        update { data ->
            val logged = data.copy(habitLogs = data.habitLogs + entry)
            if (!markDone) return@update logged
            val days = logged.checkIns[habitId].orEmpty().toMutableSet()
            // Additive only: recording a second walk today must not un-check
            // the day the first one already completed.
            if (!days.add(today)) return@update logged
            logged.copy(checkIns = logged.checkIns + (habitId to days.toList()))
        }

        refreshCompanionUnlocks()
        queueSync()

        // Capture what the device recorded, the same way a completing tap does
        // — the monitor itself decides whether this habit is in its movement
        // set, so a journal entry costs nothing here.
        if (markDone && mode != HabitTrackingMode.JOURNAL) {
            runCatching {
                com.rork.mindsetframestracker.integrations.ActivityMonitor
                    .captureForHabit(getApplication(), habitId)
            }
        }
    }

    fun canAddHabit(): Boolean =
        _state.value.settings.hasFeatureAccess() || _state.value.habits.size < MAX_FREE_HABITS

    fun addHabit(name: String): Boolean {
        val trimmed = name.trim().take(MAX_HABIT_NAME_LENGTH)
        if (trimmed.isEmpty()) return false
        update { data ->
            data.copy(
                habits = data.habits + Habit(
                    id = UUID.randomUUID().toString(),
                    name = trimmed,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        queueSync()
        return true
    }

    /**
     * Adds a fully-formed [Habit] (already carrying its iconId + reminder time).
     * Used by the icon grid on the Habits tab: tapping an icon builds the habit
     * and schedules its alarm, then hands it here. Respects the free-tier cap.
     */
    /**
     * Sets/replaces the reminder times on an already-added habit. Used by the
     * "Set up alarm" flow when a habit exists but has no reminder configured
     * (reminderMinutes == null) — as opposed to [addHabitObject], which
     * creates a brand-new habit entirely.
     *
     * ## One time and several times are the same call
     *
     * A habit can ring at several times of day (07:00, 12:00, 18:00), so the
     * single-time overload of this used to be the *only* way to set an alarm and
     * therefore silently discarded the user's other times. Both overloads now
     * funnel into here, which is the one place [Habit.withAlarmTimes] is applied
     * — keeping `alarmTimes` and the legacy `reminderMinutes` in step rather
     * than letting two writers drift apart.
     *
     * Scheduling is callers' business, deliberately: they already hold the
     * [android.content.Context] and re-arm from the updated habit.
     */
    /**
     * Saves a habit's schedule and its motivational reminder line together.
     *
     * The two are written in one call on purpose: they are edited on the same
     * screen and are consumed together at ring time (the message is only ever
     * delivered by one of these alarm times), so a single write means the saved
     * state can never be a schedule from the new edit paired with a message from
     * the old one.
     *
     * [alarmMessage] is normalised by [withAlarmMessage] — blank becomes null,
     * which at ring time resolves to the habit's curated line pack rather than an
     * empty notification body.
     */
    fun setHabitAlarmTimes(
        habitId: String,
        times: List<Int>,
        repeatDaysMask: Int,
        alarmMessage: String? = null,
    ) {
        update { data ->
            data.copy(
                habits = data.habits.map { habit ->
                    if (habit.id == habitId) {
                        habit
                            .withAlarmTimes(times)
                            .copy(repeatDaysMask = repeatDaysMask)
                            .withAlarmMessage(alarmMessage)
                    } else {
                        habit
                    }
                },
            )
        }
        queueSync()
    }

    /**
     * Single-time form, kept because most habits have exactly one alarm and the
     * create flow naturally has one value to hand over. Delegates rather than
     * writing the field, so it cannot disagree with the multi-time path.
     *
     * [alarmMessage] behaves exactly as it does on [setHabitAlarmTimes] —
     * **omitting it clears the habit's custom line** rather than leaving it alone,
     * because that same nullability is what lets the editor express "the user
     * deleted their message". Any caller that only means to move an alarm time
     * must therefore read the existing line off the habit and pass it back.
     */
    fun setHabitReminder(
        habitId: String,
        reminderMinutes: Int?,
        repeatDaysMask: Int,
        alarmMessage: String? = null,
    ) {
        setHabitAlarmTimes(habitId, listOfNotNull(reminderMinutes), repeatDaysMask, alarmMessage)
    }

    /**
     * Adds a habit, optionally with the motivational line its reminders carry.
     *
     * [alarmMessage] is a separate parameter rather than something the caller
     * bakes into [habit] because it must pass through [withAlarmMessage]: that is
     * what turns a blank entry into `null` ("use the curated pack") instead of
     * `""`, which would otherwise take priority over the curated line and post a
     * reminder with an empty body.
     */
    fun addHabitObject(habit: Habit, alarmMessage: String? = null): Boolean {
        if (!canAddHabit()) return false
        val toAdd = habit.withAlarmMessage(alarmMessage)
        update { data ->
            if (data.habits.any { it.id == habit.id }) data
            else data.copy(habits = data.habits + toAdd)
        }
        queueSync()
        return true
    }

    /**
     * Replaces the local data with [merged] after an import of shared habits.
     *
     * Takes the already-merged [AppData] rather than the incoming payload,
     * because the merge itself (id collision resolution, dedupe) lives in
     * [com.rork.mindsetframestracker.data.HabitShareCodec], which is pure and
     * unit-tested. This function's whole job is to persist the result and push
     * it to the cloud - nothing here decides what the merge should contain.
     *
     * Deliberately does **not** go through [canAddHabit]: importing habits the
     * user was sent is not the same act as authoring new ones, and silently
     * refusing an import because the free-tier cap was hit would look like a
     * broken code rather than a paywall.
     */
    fun importSharedData(merged: AppData) {
        // The incoming merge is applied through the SAME id-conflict rule a
        // background restore uses, rather than trusted as a wholesale
        // replacement. Two entry points that both call themselves "merge" must
        // not disagree about which copy of a habit wins.
        update { current ->
            val habits = mergeHabitsPreferringLocal(
                local = current.habits,
                incoming = merged.habits,
            ).habits
            // Non-habit collections are unioned by id with the local entry
            // winning, matching `restoreFromCloud`, so re-importing a code whose
            // history has since grown cannot delete the newer local records.
            val checkIns = merged.checkIns.keys.associateWith { habitId ->
                (current.checkIns[habitId].orEmpty() + merged.checkIns[habitId].orEmpty()).distinct()
            }
            val logs = (current.habitLogs + merged.habitLogs)
                .associateBy { it.id }
                .values
                .sortedBy { it.recordedAtEpochMs }
            val activity = (current.activityRecords + merged.activityRecords)
                .associateBy { it.id }
                .values
                .sortedBy { it.timestamp }
            merged.copy(
                habits = habits,
                checkIns = checkIns,
                habitLogs = logs,
                activityRecords = activity,
            )
        }
        queueSync()
    }

    data class BulkAddResult(
        val added: List<String>,
        /** Names that couldn't be added because the free-tier cap was hit. */
        val blockedByLimit: List<String>,
    )

    /**
     * Adds several habits in one call. Free-tier cap still applies: names
     * beyond the remaining free slots come back in [BulkAddResult.blockedByLimit]
     * so the UI can show an upgrade prompt naming exactly what got skipped,
     * instead of silently dropping them.
     */
    fun addHabits(names: List<String>): BulkAddResult {
        val trimmedNames = names
            .map { it.trim().take(MAX_HABIT_NAME_LENGTH) }
            .filter { it.isNotEmpty() }
        if (trimmedNames.isEmpty()) return BulkAddResult(emptyList(), emptyList())

        val hasFullAccess = _state.value.settings.hasFeatureAccess()
        val currentCount = _state.value.habits.size
        val remainingFreeSlots = (MAX_FREE_HABITS - currentCount).coerceAtLeast(0)

        val toAdd = if (hasFullAccess) trimmedNames else trimmedNames.take(remainingFreeSlots)
        val blocked = if (hasFullAccess) emptyList() else trimmedNames.drop(remainingFreeSlots)

        if (toAdd.isEmpty()) return BulkAddResult(emptyList(), blocked)

        val now = System.currentTimeMillis()
        update { data ->
            data.copy(
                habits = data.habits + toAdd.map { habitName ->
                    Habit(id = UUID.randomUUID().toString(), name = habitName, createdAt = now)
                }
            )
        }
        queueSync()
        return BulkAddResult(added = toAdd, blockedByLimit = blocked)
    }

    /**
     * AI-powered suggestions when signed in and the free-tier proxy responds;
     * falls back to the zero-cost on-device HabitRecommender on any failure
     * (signed out, network, daily quota hit) so the user always gets a list.
     *
     * Now passes the user's current mood and a brief activity summary so
     * Gemini can tailor suggestions to today's state — overwhelmed users
     * get lighter tasks, motivated users get stretch goals.
     */
    suspend fun getSuggestions(contextType: String? = null): List<HabitSuggestion> {
        val data = _state.value
        val habitNames = data.habits.map { it.name }
        val currentMood = data.moodHistory[Dates.todayKey()]?.name?.lowercase()
        val activitySummary = buildActivitySummary(data)
        val remote = supabaseSync.getAiHabitSuggestions(
            habitNames,
            mood = currentMood,
            activitySummary = activitySummary,
            contextType = contextType,
        )
        return if (remote != null && remote.isNotEmpty()) {
            remote.map { HabitSuggestion(name = it.name, category = HabitCategory.HEALTH, reason = it.reason) }
        } else {
            HabitRecommender.suggest(habitNames)
        }
    }

    /**
     * AI-powered daily to-do suggestions — mood-aware, activity-aware
     * actionable items for today. Premium feature (AI_INSIGHTS).
     */
    suspend fun getTodoSuggestions(): List<HabitSuggestion> {
        return getSuggestions(contextType = "todos")
    }

    /** Builds a short activity summary string to give Gemini context. */
    private fun buildActivitySummary(data: AppData): String? {
        val records = data.activityRecords.takeIf { it.isNotEmpty() } ?: return null
        val totalActivities = records.size
        val sources = records.map { it.source }.distinct().joinToString(", ")
        val totalSteps = records.mapNotNull { it.steps }.sum()
        val totalMinutes = records.mapNotNull { it.durationMinutes }.sum()
        return buildString {
            append("$totalActivities activities from $sources")
            if (totalSteps > 0) append(", $totalSteps steps total")
            if (totalMinutes > 0) append(", ${totalMinutes}min total exercise")
        }
    }

    fun renameHabit(habitId: String, name: String) {
        val trimmed = name.trim().take(MAX_HABIT_NAME_LENGTH)
        if (trimmed.isEmpty()) return
        val unchanged = _state.value.habits.firstOrNull { it.id == habitId }?.name == trimmed
        if (unchanged) return
        update { data ->
            data.copy(habits = data.habits.map { if (it.id == habitId) it.copy(name = trimmed) else it })
        }
        queueSync()
    }

    fun togglePinned(habitId: String) {
        update { data ->
            data.copy(
                habits = data.habits.map {
                    if (it.id == habitId) it.copy(isPinned = !it.isPinned) else it
                }
            )
        }
    }

    fun deleteHabit(habitId: String) {
        // Which provider links die with this habit, resolved BEFORE the delete.
        //
        // A binding is a field on the habit, so deleting the habit does delete
        // its bindings — there is no orphaned row to clean up. What is left
        // behind is a *silent* state change: the account-level connection
        // survives, so the provider still reads "Connected" while now serving
        // nothing. Every future sweep skips it and the user's activity simply
        // stops arriving, with no screen anywhere explaining why. Naming it is
        // the whole point — the state is unavoidable, but it need not be
        // invisible.
        val orphaned = providersLeftUnboundAfter(_state.value.habits, habitId)
        update { data ->
            data.copy(
                habits = data.habits.filterNot { it.id == habitId },
                checkIns = data.checkIns - habitId,
            )
        }
        if (orphaned.isNotEmpty()) {
            val names = orphaned.joinToString(", ") { it.label }
            _stravaMessage.value =
                "This habit's $names link was removed with it. $names is still connected " +
                    "to your account — link it to another habit to keep importing."
        }
        // A local delete only removes the habit from _state — pushSnapshot()
        // upserts whatever's currently in _state, it never deletes rows that
        // are no longer present. Without this, a deleted habit stays in
        // Supabase forever and comes back on next pull/restore. Queuing the
        // id here means it survives even if the sync that follows fails or
        // the app is killed before it runs.
        supabaseSync.queueHabitDeletion(habitId)
        queueSync()
    }

    fun restoreHabit(habit: Habit, checkInDays: List<String>) {
        update { data ->
            if (data.habits.any { it.id == habit.id }) data
            else data.copy(
                habits = data.habits + habit,
                checkIns = if (checkInDays.isEmpty()) data.checkIns
                else data.checkIns + (habit.id to checkInDays),
            )
        }
        queueSync()
    }

    fun completeOnboarding(starterHabits: List<String>, mood: MoodMode?) {
        update { data ->
            val newHabits = starterHabits
                .map { it.trim().take(MAX_HABIT_NAME_LENGTH) }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { Habit(UUID.randomUUID().toString(), it, System.currentTimeMillis()) }
            data.copy(
                // By name here rather than id: a bulk add creates fresh ids, so
                // the only sense in which two of them are "the same habit" is the
                // name the user typed. Not routed through `mergeHabitsPreferring
                // Local` because that rule is about id conflicts between devices,
                // which cannot arise from a list built in this one call.
                habits = (data.habits + newHabits).distinctBy { it.name },
                moodHistory = if (mood != null) {
                    data.moodHistory + (Dates.todayKey() to mood)
                } else data.moodHistory,
                settings = data.settings.copy(onboardingDone = true),
            )
        }
        refreshCompanionUnlocks()
        // Arm the built-in reminders (daily / streak / weekly recap) right
        // away for a fresh install. AppRoot's LaunchedEffect also does this
        // once onboardingDone flips to true — this is a belt-and-braces
        // second call in case the notification permission is already
        // granted (e.g. re-onboarding after a data wipe) and nothing ever
        // triggers that Compose effect to actually schedule anything.
        runCatching { scheduleNotification() }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Post-onboarding reminder arm failed: ${it.message}", it)
        }
        runCatching { rearmHabitAlarms() }.onFailure {
            if (BuildConfig.DEBUG) Log.e("AppViewModel", "Post-onboarding habit-alarm arm failed: ${it.message}", it)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        update { it.copy(settings = it.settings.copy(themeMode = mode)) }
    }

    fun setReducedMotion(enabled: Boolean) {
        update { it.copy(settings = it.settings.copy(reducedMotion = enabled)) }
    }

    fun setPresetTime(presetId: String, minutes: Int) {
        update { data ->
            data.copy(
                settings = data.settings.copy(
                    presetTimes = data.settings.presetTimes + (presetId to minutes)
                )
            )
        }
    }

    fun setNotificationMinutes(minutes: Int) {
        update { it.copy(settings = it.settings.copy(notificationMinutes = minutes)) }
        scheduleNotification(minutes)
    }

    /**
     * Arms every "built-in" reminder — daily check-in, evening streak alert,
     * Sunday recap, evening reflection — in one call. There is no longer any
     * per-item enable/disable toggle: these are always on, matching the
     * simplified Settings UI (a single explanatory card, no switches).
     *
     * Called from three places so a mis-armed alarm can't linger silently:
     *  1. [init] — every cold app launch re-syncs all four against
     *     AlarmManager, so even if an OEM force-stop or a device reboot
     *     wiped the previously-scheduled alarms, simply opening the app
     *     again repairs them.
     *  2. [completeOnboarding] — arms them immediately for a fresh install,
     *     instead of waiting on a Compose recomposition to notice.
     *  3. [setNotificationMinutes] — re-arms the daily reminder at its new
     *     time right away.
     */
    fun scheduleNotification(minutes: Int = _state.value.settings.notificationMinutes) {
        if (hasNotificationPermission()) {
            notificationScheduler.scheduleDailyReminder(minutes)
            notificationScheduler.scheduleEveningReflection()
            notificationScheduler.scheduleStreakAlert(_state.value.settings.streakAlertMinutes)
            notificationScheduler.scheduleWeeklyRecap()
        }
    }

    /**
     * Toggles the streak-protection alert on/off. When turning it on we
     * immediately (re)schedule it at its saved time; when turning it off we
     * cancel the pending alarm outright, otherwise it would still fire once
     * even after being switched off.
     */
    fun setStreakAlertEnabled(enabled: Boolean) {
        update { it.copy(settings = it.settings.copy(streakAlertEnabled = enabled)) }
        if (enabled) {
            if (hasNotificationPermission()) {
                notificationScheduler.scheduleStreakAlert(_state.value.settings.streakAlertMinutes)
            }
        } else {
            notificationScheduler.cancelStreakAlert()
        }
    }

    /** Updates the streak alert's daily fire time and reschedules it if enabled. */
    fun setStreakAlertMinutes(minutes: Int) {
        update { it.copy(settings = it.settings.copy(streakAlertMinutes = minutes)) }
        if (_state.value.settings.streakAlertEnabled && hasNotificationPermission()) {
            notificationScheduler.scheduleStreakAlert(minutes)
        }
    }

    /**
     * Toggles the Sunday weekly recap on/off, scheduling or cancelling the
     * alarm to match — same reasoning as [setStreakAlertEnabled].
     */
    fun setWeeklyRecapEnabled(enabled: Boolean) {
        update { it.copy(settings = it.settings.copy(weeklyRecapEnabled = enabled)) }
        if (enabled) {
            if (hasNotificationPermission()) {
                notificationScheduler.scheduleWeeklyRecap()
            }
        } else {
            notificationScheduler.cancelWeeklyRecap()
        }
    }

    fun sendReminderPreview(): Boolean {
        if (!hasNotificationPermission()) return false
        CheckInNotifier.show(getApplication(), preview = true)
        return true
    }

    fun sendStreakAlertPreview(): Boolean {
        if (!hasNotificationPermission()) return false
        StreakAlertNotifier.showIfStreakAtRisk(getApplication(), preview = true)
        return true
    }

    fun sendWeeklyRecapPreview(): Boolean {
        if (!hasNotificationPermission()) return false
        WeeklyRecapNotifier.showRecap(getApplication(), preview = true)
        return true
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    fun setAccentPack(pack: String) {
        update { it.copy(settings = it.settings.copy(accentPack = pack)) }
    }

    fun setLanguage(language: com.rork.mindsetframestracker.data.AppLanguage) {
        update { it.copy(settings = it.settings.copy(language = language)) }
        queueSync()
    }

    fun setAvatar(avatar: com.rork.mindsetframestracker.data.AvatarConfig) {
        update { it.copy(settings = it.settings.copy(avatar = avatar)) }
    }

    fun saveReflection(text: String) {
        val key = Dates.todayKey()
        val line = text.trim().take(160)
        update { data ->
            data.copy(
                reflections = if (line.isEmpty()) data.reflections - key
                else data.reflections + (key to line),
            )
        }
        refreshCompanionUnlocks()
    }

    fun markReviewPromptShown() {}

    fun shouldShowReviewPrompt(streak: Int): Boolean = false

    fun awardBadge(tier: BadgeTier) {
        if (_state.value.settings.earnedBadges.contains(tier)) return
        update { data ->
            data.copy(settings = data.settings.copy(earnedBadges = data.settings.earnedBadges + tier))
        }
        refreshCompanionUnlocks()
    }
}