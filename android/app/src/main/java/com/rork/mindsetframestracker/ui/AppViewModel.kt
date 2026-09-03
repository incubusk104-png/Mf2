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
import com.rork.mindsetframestracker.billing.RestoreResult
import com.rork.mindsetframestracker.billing.SubscriptionBilling
import com.rork.mindsetframestracker.billing.SubscriptionResult
import com.rork.mindsetframestracker.integrations.StravaAuthClient
import com.rork.mindsetframestracker.integrations.StravaTokens
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.CloudBackupWorker
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.data.BadgeTier
import com.rork.mindsetframestracker.data.Habit
import com.rork.mindsetframestracker.data.HabitCategory
import com.rork.mindsetframestracker.data.HabitRecommender
import com.rork.mindsetframestracker.data.HabitSuggestion
import com.rork.mindsetframestracker.data.MAX_FREE_HABITS
import com.rork.mindsetframestracker.data.MindsetRepository
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.data.hasFeatureAccess
import com.rork.mindsetframestracker.data.regionalLanguageFor
import com.rork.mindsetframestracker.data.universallyFreeLanguages
import com.rork.mindsetframestracker.data.SupabaseSync
import com.rork.mindsetframestracker.data.ThemeMode
import com.rork.mindsetframestracker.notifications.CheckInNotifier
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
                grantSubscription(result.productId)
                _subscriptionMessage.value = "Premium unlocked — welcome aboard! \uD83C\uDF89"
            }
            is SubscriptionResult.Cancelled -> {
                // Silent — the user closed the payment sheet.
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
     */
    fun syncStravaActivities(habitId: String, activityType: String) {
        val settings = _state.value.settings
        val refresh = settings.stravaRefreshToken
        if (refresh.isNullOrBlank()) {
            _stravaMessage.value = "Connect Strava first."
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
            var changed = false
            var updatedCheckIns = data.checkIns

            for (habit in screenHabits) {
                val pkg = habit.monitoredPackage ?: continue
                val limit = habit.screenTimeLimitMinutes ?: continue

                // Today: live status — done while under the limit.
                monitor.usedMinutesToday(app, pkg)?.let { used ->
                    val underLimit = used <= limit
                    val days = updatedCheckIns[habit.id].orEmpty().toMutableSet()
                    val isChecked = todayKey in days
                    if (underLimit && !isChecked) {
                        days.add(todayKey); changed = true
                    } else if (!underLimit && isChecked) {
                        days.remove(todayKey); changed = true
                    }
                    updatedCheckIns = updatedCheckIns + (habit.id to days.toList())
                }

                // Yesterday: final settlement (only ever marks success — a
                // blown day just stays unchecked).
                monitor.usedMinutesYesterday(app, pkg)?.let { used ->
                    if (used <= limit) {
                        val days = updatedCheckIns[habit.id].orEmpty().toMutableSet()
                        if (yesterdayKey !in days) {
                            days.add(yesterdayKey); changed = true
                            updatedCheckIns = updatedCheckIns + (habit.id to days.toList())
                        }
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
        viewModelScope.launch {
            // Verify permissions are still valid before attempting a sync
            if (!verifyHealthConnectPermissions()) return@launch
            val ok = com.rork.mindsetframestracker.integrations.MindsetHealthConnectClient
                .syncTodayToHabit(getApplication(), habitId, activityType)
            if (ok) {
                update { it.copy(settings = it.settings.copy(healthConnectLastSyncMs = System.currentTimeMillis())) }
            }
            _stravaMessage.value =
                if (ok) "Today's steps synced from Health Connect."
                else "No step data from Health Connect yet — open Health Connect and check permissions."
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
        val habits = _state.value.habits
        val firstFitnessHabit = habits.firstOrNull { habit ->
            habit.iconId != null && com.rork.mindsetframestracker.integrations.PolarClient
                .isActivitySupported(habit.iconId!!)
        }
        // Auto-sync Polar — only when OAuth token is present AND auto-sync enabled
        if (isPolarConnected() && s.polarAutoSync && firstFitnessHabit != null) {
            syncPolarToHabit(firstFitnessHabit.id, firstFitnessHabit.iconId ?: "walking")
        }
        // Auto-sync Health Connect — only when permissions are verified AND auto-sync enabled
        if (s.healthConnectConnected && s.healthConnectAutoSync && firstFitnessHabit != null) {
            viewModelScope.launch {
                // Re-verify permissions haven't been revoked since last session
                if (verifyHealthConnectPermissions()) {
                    syncHealthConnectToHabit(firstFitnessHabit.id, firstFitnessHabit.iconId ?: "walking")
                }
            }
        }
        // Auto-sync Strava — only when OAuth refresh token is present AND auto-sync enabled
        if (!s.stravaRefreshToken.isNullOrBlank() && s.stravaAutoSync && firstFitnessHabit != null) {
            syncStravaActivities(firstFitnessHabit.id, firstFitnessHabit.iconId ?: "running")
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
            val mergedHabits = (snapshot.habits + data.habits)
                .distinctBy { it.id }
                .map { habit -> if (habit.id in localPins) habit.copy(isPinned = true) else habit }
            val mergedCheckIns = (data.checkIns.keys + snapshot.checkIns.keys).associateWith { habitId ->
                (data.checkIns[habitId].orEmpty() + snapshot.checkIns[habitId].orEmpty()).distinct()
            }
            data.copy(
                habits = mergedHabits,
                checkIns = mergedCheckIns,
                moodHistory = snapshot.moodHistory + data.moodHistory,
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
            if (error == null) {
                supabaseSync.hasPendingPush = false
                _syncState.value = _syncState.value.copy(
                    busy = false,
                    lastSyncAtMs = supabaseSync.lastSyncAtMs,
                )
            } else {
                _syncState.value = _syncState.value.copy(
                    busy = false,
                    message = error,
                    isError = true,
                )
            }
        }
    }

    fun retrySync() {
        lastSyncAttemptAt = 0L
        _syncState.value = _syncState.value.copy(message = null, isError = false)
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
        _syncState.value = state.copy(busy = true, message = null, isError = false)
        viewModelScope.launch {
            val error = supabaseSync.pushSnapshot(_state.value)
            if (error != null) {
                lastSyncAttemptAt = 0L
            } else {
                supabaseSync.hasPendingPush = false
            }
            _syncState.value = _syncState.value.copy(
                busy = false,
                message = error ?: "Backed up just now",
                isError = error != null,
                lastSyncAtMs = supabaseSync.lastSyncAtMs,
            )
        }
    }

    fun selectMood(mode: MoodMode) {
        update { it.copy(moodHistory = it.moodHistory + (Dates.todayKey() to mode)) }
        refreshCompanionUnlocks()
    }

    fun toggleHabitToday(habitId: String) {
        update { data ->
            val today = Dates.todayKey()
            val days = data.checkIns[habitId].orEmpty().toMutableSet()
            if (!days.add(today)) days.remove(today)
            data.copy(checkIns = data.checkIns + (habitId to days.toList()))
        }
        refreshCompanionUnlocks()
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
     * Sets/replaces the reminder time on an already-added habit. Used by the
     * "Set up alarm" flow when a habit exists but has no reminder configured
     * (reminderMinutes == null) — as opposed to [addHabitObject], which
     * creates a brand-new habit entirely.
     */
    fun setHabitReminder(habitId: String, reminderMinutes: Int, repeatDaysMask: Int) {
        update { data ->
            data.copy(
                habits = data.habits.map { habit ->
                    if (habit.id == habitId) {
                        habit.copy(reminderMinutes = reminderMinutes, repeatDaysMask = repeatDaysMask)
                    } else {
                        habit
                    }
                },
            )
        }
        queueSync()
    }

    fun addHabitObject(habit: Habit): Boolean {
        if (!canAddHabit()) return false
        update { data ->
            if (data.habits.any { it.id == habit.id }) data
            else data.copy(habits = data.habits + habit)
        }
        queueSync()
        return true
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
        update { data ->
            data.copy(
                habits = data.habits.filterNot { it.id == habitId },
                checkIns = data.checkIns - habitId,
            )
        }
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
                habits = (data.habits + newHabits).distinctBy { it.name },
                moodHistory = if (mood != null) {
                    data.moodHistory + (Dates.todayKey() to mood)
                } else data.moodHistory,
                settings = data.settings.copy(onboardingDone = true),
            )
        }
        refreshCompanionUnlocks()
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

    fun scheduleNotification(minutes: Int = _state.value.settings.notificationMinutes) {
        if (hasNotificationPermission()) {
            notificationScheduler.scheduleDailyReminder(minutes)
            notificationScheduler.scheduleEveningReflection()
        }
        syncStreakAlertAlarm()
        syncWeeklyRecapAlarm()
    }

    fun setStreakAlertEnabled(enabled: Boolean) {
        update { it.copy(settings = it.settings.copy(streakAlertEnabled = enabled)) }
        syncStreakAlertAlarm()
    }

    fun setStreakAlertMinutes(minutes: Int) {
        update { it.copy(settings = it.settings.copy(streakAlertMinutes = minutes)) }
        syncStreakAlertAlarm()
    }

    private fun syncStreakAlertAlarm() {
        val settings = _state.value.settings
        if (settings.streakAlertEnabled && hasNotificationPermission()) {
            notificationScheduler.scheduleStreakAlert(settings.streakAlertMinutes)
        } else {
            notificationScheduler.cancelStreakAlert()
        }
    }

    fun setWeeklyRecapEnabled(enabled: Boolean) {
        update { it.copy(settings = it.settings.copy(weeklyRecapEnabled = enabled)) }
        syncWeeklyRecapAlarm()
    }

    private fun syncWeeklyRecapAlarm() {
        if (_state.value.settings.weeklyRecapEnabled && hasNotificationPermission()) {
            notificationScheduler.scheduleWeeklyRecap()
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
