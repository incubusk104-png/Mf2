
package com.rork.mindsetframestracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.mindsetframestracker.auth.HuaweiAppUpdateChecker
import com.rork.mindsetframestracker.auth.HuaweiAuthClient
import com.rork.mindsetframestracker.auth.HuaweiServicesConfig
import com.rork.mindsetframestracker.auth.HuaweiSignInResult
import com.rork.mindsetframestracker.data.currentMood
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.LocalAppStrings
import com.rork.mindsetframestracker.ui.navigation.AppNavigation
import com.rork.mindsetframestracker.ui.stringsFor
import com.rork.mindsetframestracker.ui.theme.AppTheme
import java.io.File

/** Hosts the Compose UI. */
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    private var pendingScheduleAfterPermission: (() -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pendingScheduleAfterPermission?.invoke()
        pendingScheduleAfterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            safeOnCreate(savedInstanceState)
        } catch (e: Throwable) {
            logStartupError(e)
            showFallback()
        }
    }

    private fun safeOnCreate(savedInstanceState: Bundle?) {
        // Initialize Huawei AGConnect from the bundled agconnect-services.json
        // so Account Kit is ready before the auth sheet can appear. Safe no-op
        // when the config file isn't bundled yet.
        runCatching { HuaweiServicesConfig.initialize(this) }
        runCatching { HuaweiAppUpdateChecker.checkForUpdate(this) }

        // Consume an auth deep link delivered with the launch intent (cold
        // start from the email web-bridge).
        runCatching { handleAuthIntent(intent) }

        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("MainActivity", "enableEdgeToEdge failed: ${e.message}")
        }

        setContent {
            AppRoot(
                viewModel = appViewModel,
                onNotificationPermissionNeeded = { schedule ->
                    requestNotificationPermission(schedule)
                },
            )
        }
    }

    /** Writes a crash trace to a local file; logcat may not be reachable in the cloud preview. */
    private fun logStartupError(e: Throwable) {
        val message = "${System.currentTimeMillis()} ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}"
        Log.e("MainActivity", message, e)
        try {
            File(cacheDir, "startup_errors.txt").appendText("$message\n\n")
        } catch (_: Exception) {
            // Ignore: file logging is best-effort only.
        }
    }

    /** A plain Android fallback that avoids Compose/Material entirely. */
    private fun showFallback() {
        try {
            val text = TextView(this)
            text.text = "Mindset Frames\nStarting up…"
            text.gravity = android.view.Gravity.CENTER
            text.setTextColor(android.graphics.Color.BLACK)
            text.textSize = 18f
            setContentView(text)
        } catch (e: Throwable) {
            Log.e("MainActivity", "Fallback UI failed: ${e.message}", e)
        }
    }

    /**
     * Warm-start leg of the auth web-bridge: launchMode="singleTask" routes
     * a tapped email link into the existing instance through onNewIntent —
     * no duplicate activities, no login loops.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { handleAuthIntent(intent) }
    }

    /**
     * Forwards an auth-callback deep link to the ViewModel exactly once.
     * The intent's data is cleared afterwards so system re-deliveries
     * (recents relaunch, task re-parenting) can never replay the one-time
     * link; the ViewModel adds a persisted signature guard on top.
     */
    private fun handleAuthIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        val isAuthCallback = uri.host == "auth-callback" &&
            (uri.scheme == "com.mindsetframes.habittracker" ||
                uri.scheme == "com.rork.mindsetframestracker")
        if (isAuthCallback) {
            appViewModel.handleAuthDeepLink(uri)
            intent.data = null
            return
        }
        // Strava OAuth return leg: mindsetframes://strava-callback?code=...
        val isStravaCallback = uri.scheme == "mindsetframes" && uri.host == "strava-callback"
        if (isStravaCallback) {
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")
            when {
                !code.isNullOrBlank() -> appViewModel.handleStravaAuthCode(code)
                error != null -> appViewModel.onStravaConnectFailed(
                    if (error == "access_denied") "Strava access was declined."
                    else "Strava connection failed: $error",
                )
            }
            intent.data = null
        }
    }

    /**
     * Handles the return leg of the Huawei Account Kit sign-in intent.
     * The result is parsed and forwarded to the ViewModel.
     */
    @Deprecated("Deprecated in API 33+", replaceWith = ReplaceWith("registerForActivityResult"))
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == HuaweiAuthClient.SIGN_IN_REQUEST_CODE) {
            when (val result = HuaweiAuthClient.parseResult(requestCode, resultCode, data)) {
                is HuaweiSignInResult.Success -> {
                    appViewModel.signInWithHuawei(result.idToken, result.email, result.displayName)
                }
                is HuaweiSignInResult.Cancelled -> {
                    // User dismissed the Huawei sign-in dialog — no action needed.
                }
                is HuaweiSignInResult.Error -> {
                    // Keep the auth sheet open and show the failure inline so the
                    // user can retry or fall back to email sign-in.
                    appViewModel.onHuaweiSignInFailed(result.message)
                }
            }
        } else if (requestCode == com.rork.mindsetframestracker.billing.SubscriptionBilling.SUBSCRIPTION_REQUEST_CODE) {
            com.rork.mindsetframestracker.billing.SubscriptionBilling.handlePurchaseResult(
                this,
                appViewModel.pendingSubscriptionProductId,
                data,
            ) { outcome ->
                appViewModel.onSubscriptionPurchaseResult(outcome)
            }
        } else if (requestCode == com.rork.mindsetframestracker.integrations.HuaweiHealthKitClient.HEALTH_AUTH_REQUEST_CODE) {
            val granted = com.rork.mindsetframestracker.integrations.HuaweiHealthKitClient.parseAuthResult(this, data)
            appViewModel.onHealthKitAuthResult(granted)
        } else if (requestCode == com.rork.mindsetframestracker.billing.TipBilling.PURCHASE_REQUEST_CODE) {
            com.rork.mindsetframestracker.billing.TipBilling.handlePurchaseResult(this, data) { outcome ->
                when (outcome) {
                    is com.rork.mindsetframestracker.billing.TipPurchaseResult.Success -> {
                        appViewModel.onTipPurchaseResult("Thank you for the tip! 💜")
                    }
                    is com.rork.mindsetframestracker.billing.TipPurchaseResult.Cancelled -> {
                        appViewModel.onTipPurchaseResult(null)
                    }
                    is com.rork.mindsetframestracker.billing.TipPurchaseResult.Error -> {
                        appViewModel.onTipPurchaseResult(outcome.message)
                    }
                }
            }
        } else if (requestCode == com.rork.mindsetframestracker.billing.SubscriptionBilling.ENV_READY_REQUEST_CODE) {
            // IAP environment resolution completed (e.g. user signed in to
            // Huawei ID). The next purchase attempt will succeed now.
            if (resultCode == RESULT_OK) {
                android.util.Log.i("MainActivity", "IAP environment is now ready after user action")
            } else {
                android.util.Log.w("MainActivity", "IAP environment resolution was cancelled/failed")
            }
        }
    }

    private fun requestNotificationPermission(scheduleIfGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scheduleIfGranted()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scheduleIfGranted()
        } else {
            pendingScheduleAfterPermission = scheduleIfGranted
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AppRoot(
    viewModel: AppViewModel,
    onNotificationPermissionNeeded: (scheduleIfGranted: () -> Unit) -> Unit,
) {
    val data by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(data.settings.onboardingDone) {
        if (data.settings.onboardingDone) {
            onNotificationPermissionNeeded { viewModel.scheduleNotification() }
        }
    }

    AppTheme(
        themeMode = data.settings.themeMode,
        moodMode = data.currentMood(),
        accentPack = data.settings.accentPack,
        reducedMotion = data.settings.reducedMotion,
    ) {
        CompositionLocalProvider(
            LocalAppStrings provides stringsFor(data.settings.language),
        ) {
            AppNavigation(viewModel = viewModel)
        }
    }
}
