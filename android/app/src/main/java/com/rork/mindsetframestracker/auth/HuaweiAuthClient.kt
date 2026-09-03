package com.rork.mindsetframestracker.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.huawei.hms.api.ConnectionResult
import com.huawei.hms.api.HuaweiApiAvailability
import com.huawei.hms.common.ApiException
import com.huawei.hms.support.account.AccountAuthManager
import com.huawei.hms.support.account.request.AccountAuthParams
import com.huawei.hms.support.account.request.AccountAuthParamsHelper
import com.huawei.hms.support.account.result.AuthAccount

/**
 * Outcome of a Huawei ID sign-in attempt.
 */
sealed interface HuaweiSignInResult {
    /**
     * Sign-in succeeded — carries the Huawei-signed ID token (verified
     * server-side before any session is issued) plus the email and display
     * name for instant local display.
     */
    data class Success(
        val idToken: String,
        val email: String?,
        val displayName: String?,
    ) : HuaweiSignInResult

    /** The user cancelled the sign-in dialog — not an error. */
    data object Cancelled : HuaweiSignInResult

    /** Sign-in failed with an error message suitable for the UI. */
    data class Error(val message: String) : HuaweiSignInResult
}

/**
 * Native Huawei Account Kit client — HUAWEI ID sign-in for AppGallery
 * distribution, built directly on the HMS `hwid` SDK.
 *
 * Requests only basic profile authorization: the user's Huawei ID (unionId /
 * openId), display name, and email address. No extended scopes (drive,
 * health, etc.) are requested, complying with Huawei AppGallery's
 * minimum-data principles and GDPR requirements.
 *
 * Prerequisites:
 *  - agconnect-services.json from AppGallery Connect bundled with the app
 *    (see [HuaweiServicesConfig]); without it sign-in reports "not configured".
 *  - HMS Core (APK) on the device; on devices without it (or emulators) the
 *    UI falls back to email sign-in with a clear message.
 */
object HuaweiAuthClient {

    private const val TAG = "HuaweiAuthClient"

    /** Request code for the HMS sign-in intent. */
    const val SIGN_IN_REQUEST_CODE = 8888

    /**
     * Wall-clock time [startSignIn] launched the HMS sign-in activity, or
     * null if no attempt is in flight. Used by [parseResult] to tell a
     * genuine user cancel (screen was visible for a while) apart from an
     * instant server-side rejection (screen opens and closes itself in a
     * few hundred ms, before a human could plausibly have seen an account
     * picker and tapped back) — see [FAST_REJECT_THRESHOLD_MS].
     */
    @Volatile
    private var signInLaunchedAtMs: Long? = null

    /**
     * If the sign-in activity returns RESULT_CANCELED faster than this, it
     * almost certainly never showed a real account picker — HMS Account Kit
     * rejected it server-side (most commonly: this build's signing
     * certificate SHA-256 isn't registered for this app in AppGallery
     * Connect, or the Account Kit service toggle hasn't finished
     * propagating yet). A real human cancel — seeing the picker, deciding
     * not to sign in, tapping back — reliably takes over a second.
     */
    private const val FAST_REJECT_THRESHOLD_MS = 1200L

    /** Account Kit status code: the user cancelled the sign-in dialog. */
    private const val STATUS_SIGN_IN_CANCELLED = 2012

    /** Account Kit status code: network error while contacting HMS. */
    private const val STATUS_NETWORK_ERROR = 2005

    /** Account Kit status code: app not authorized in AppGallery Connect. */
    private const val STATUS_SCOPE_UNAUTHORIZED = 2002

    /**
     * True when HMS Core is installed and usable on this device.
     *
     * **The 'code 1' fix**: [HuaweiApiAvailability.isHuaweiMobileServicesAvailable]
     * returns [ConnectionResult.SUCCESS] (0) on native Huawei/Honor devices.
     * On non-Huawei devices that sideloaded HMS Core the call often returns
     * code **1** (`SERVICE_MISSING`), even though HMS Core IS installed and
     * Account Kit works fine — because the check looks for the pre-installed
     * system-level HMS Core package that only ships on Huawei ROMs.
     *
     * The old check (`== SUCCESS`) blocked every sideloaded-HMS device.
     * The new logic:
     *  1. Accept SUCCESS (native Huawei).
     *  2. On any other code, fall back to a **package-manager probe**: if the
     *     HMS Core APK (`com.huawei.hwid` or `com.huawei.hms.core`) is
     *     actually installed, treat the device as HMS-capable and let Account
     *     Kit try. Worst case, the sign-in intent fails gracefully.
     *  3. Only return false when HMS Core truly isn't installed at all.
     */
    fun isHmsAvailable(context: Context): Boolean {
        // Fast path: official API check
        val code = hmsConnectionResult(context)
        if (code == ConnectionResult.SUCCESS) return true

        // Fallback: probe for HMS Core packages directly. Code 1
        // (SERVICE_MISSING) fires on non-Huawei ROMs even when the user
        // sideloaded HMS Core, so a package check is the reliable signal.
        return isHmsCorePackageInstalled(context).also { found ->
            if (found) {
                Log.i(
                    TAG,
                    "HMS availability API returned code $code but HMS Core " +
                        "package IS installed — treating as available",
                )
            }
        }
    }

    /**
     * Same check as [isHmsAvailable] but returns the raw HMS connection
     * result code on failure so the UI/logs can tell "HMS Core missing" apart
     * from "HMS Core installed but outdated/disabled" — collapsing those into
     * one generic message is what made this look like a code bug instead of
     * a device-state issue.
     */
    private fun hmsConnectionResult(context: Context): Int {
        val result = runCatching {
            HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(context)
        }
        result.exceptionOrNull()?.let {
            // The check itself crashed (e.g. a version-mismatched HMS jar) —
            // this is NOT the same as HMS Core being absent, so log it loudly
            // instead of silently reporting SERVICE_MISSING like before.
            Log.e(TAG, "HMS availability check threw: ${it::class.qualifiedName}: ${it.message}", it)
        }
        return result.getOrDefault(ConnectionResult.SERVICE_MISSING)
    }

    /**
     * Checks whether any known HMS Core package is actually installed on
     * the device, regardless of what the HMS availability API reports.
     * This catches the common "code 1 on non-Huawei ROM" false negative.
     */
    private fun isHmsCorePackageInstalled(context: Context): Boolean {
        val hmsCorePackages = listOf(
            "com.huawei.hwid",           // Huawei ID (Account Kit)
            "com.huawei.hms.core",       // HMS Core framework
            "com.huawei.appmarket",      // AppGallery (ships with HMS)
        )
        val pm = context.packageManager
        return hmsCorePackages.any { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }
    }

    /**
     * Basic-profile auth parameters — ID token + email only. The ID token is
     * a Huawei-signed credential that the backend verifies with Huawei's
     * account server before minting a session, so a forged or replayed
     * sign-in can never reach another user's cloud data.
     */
    private fun authParams(): AccountAuthParams =
        AccountAuthParamsHelper(AccountAuthParams.DEFAULT_AUTH_REQUEST_PARAM)
            .setIdToken()
            .setEmail()
            .createParams()

    /**
     * Launches the HUAWEI ID sign-in intent on the given activity. The result
     * is delivered back through [Activity.onActivityResult] with
     * [SIGN_IN_REQUEST_CODE]; call [parseResult] there to extract the outcome.
     *
     * Returns null when the intent launched, or a user-facing error message
     * when sign-in can't start (HMS missing, config absent, etc.) — show it
     * in the auth sheet so the user can fall back to email sign-in.
     */
    fun startSignIn(activity: Activity): String? {
        if (!HuaweiServicesConfig.isConfigured) {
            Log.w(TAG, "Huawei sign-in blocked — agconnect-services.json not configured")
            val reason = HuaweiServicesConfig.lastError
            HuaweiServicesConfig.logDiagnostic(activity, "startSignIn blocked: not configured ($reason)")
            return if (reason != null) {
                "Huawei sign-in isn't set up yet ($reason). Use email sign-in instead."
            } else {
                "Huawei sign-in isn't set up on this build yet. Use email sign-in instead."
            }
        }
        if (!isHmsAvailable(activity)) {
            val code = hmsConnectionResult(activity)
            Log.w(TAG, "Huawei sign-in blocked — HMS availability check returned code $code")
            HuaweiServicesConfig.logDiagnostic(activity, "startSignIn blocked: HMS unavailable (code $code)")
            return when (code) {
                ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ->
                    "HUAWEI Mobile Services needs an update on this device. Update HMS Core from AppGallery, then try again."
                ConnectionResult.SERVICE_DISABLED ->
                    "HUAWEI Mobile Services is disabled on this device. Enable it in system settings, then try again."
                ConnectionResult.SERVICE_INVALID ->
                    "This build's HUAWEI Mobile Services install looks invalid. Reinstall HMS Core from AppGallery."
                else ->
                    "HUAWEI Mobile Services isn't available on this device (code $code). Use email sign-in instead."
            }
        }
        return try {
            val service = AccountAuthManager.getService(activity, authParams())
            signInLaunchedAtMs = System.currentTimeMillis()
            activity.startActivityForResult(service.signInIntent, SIGN_IN_REQUEST_CODE)
            null
        } catch (e: Exception) {
            signInLaunchedAtMs = null
            Log.w(TAG, "Failed to launch Huawei sign-in: ${e.message}")
            "Couldn't open Huawei sign-in. Try again or use email."
        }
    }

    /**
     * Parses the activity result from [startSignIn] into a [HuaweiSignInResult].
     * Call this from [Activity.onActivityResult] when requestCode == [SIGN_IN_REQUEST_CODE].
     */
    fun parseResult(context: Context, requestCode: Int, resultCode: Int, data: Intent?): HuaweiSignInResult {
        if (requestCode != SIGN_IN_REQUEST_CODE) return HuaweiSignInResult.Cancelled
        val launchedAt = signInLaunchedAtMs
        signInLaunchedAtMs = null
        val elapsedMs = launchedAt?.let { System.currentTimeMillis() - it }
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.i(TAG, "Huawei sign-in cancelled or dismissed (resultCode=$resultCode, elapsedMs=$elapsedMs)")
            HuaweiServicesConfig.logDiagnostic(
                context,
                "Huawei sign-in returned resultCode=$resultCode, data=${data == null}, " +
                    "elapsedMs=$elapsedMs.",
            )
            // resultCode != RESULT_OK covers BOTH a genuine user cancel (tapped
            // back / dismissed the account picker) AND the sign-in activity
            // being auto-rejected and closing itself instantly — most often
            // because this build's signing certificate SHA-256 isn't
            // registered for this app in AppGallery Connect, or the Account
            // Kit toggle in AppGallery Connect hasn't finished propagating
            // yet. HMS gives no distinct status code for the second case,
            // but it IS distinguishable by timing: a human has to see the
            // account picker and tap back, which takes over a second; a
            // server-side rejection closes the screen in well under that.
            return if (elapsedMs != null && elapsedMs < FAST_REJECT_THRESHOLD_MS) {
                val fingerprint = HuaweiServicesConfig.signingCertSha256(context)
                    .firstOrNull()
                    ?: "<could not read — check huawei_diagnostics.txt>"
                Log.w(TAG, "Huawei sign-in rejected in ${elapsedMs}ms — treating as config error, not a cancel")
                HuaweiSignInResult.Error(
                    "Huawei sign-in was rejected immediately (no account picker shown). This " +
                        "almost always means one of two things:\n\n" +
                        "1) This build's SHA-256 certificate fingerprint isn't the one registered " +
                        "in AppGallery Connect for this app. This app's build is signed with:\n" +
                        "$fingerprint\n" +
                        "Compare that EXACTLY against Project settings > General information > " +
                        "App information > SHA-256 certificate fingerprint. Debug and release " +
                        "builds use different keystores — make sure you registered the one for " +
                        "the build you're testing.\n\n" +
                        "2) The Account Kit toggle was enabled recently and AppGallery Connect " +
                        "hasn't finished propagating it yet (can take up to ~30 minutes).",
                )
            } else {
                HuaweiSignInResult.Cancelled
            }
        }
        return try {
            val task = AccountAuthManager.parseAuthResultFromIntent(data)
            if (task.isSuccessful) {
                parseAuthAccount(task.result)
            } else {
                val statusCode = (task.exception as? ApiException)?.statusCode
                Log.w(TAG, "Huawei sign-in failed with status $statusCode")
                when (statusCode) {
                    STATUS_SIGN_IN_CANCELLED -> HuaweiSignInResult.Cancelled
                    STATUS_NETWORK_ERROR -> HuaweiSignInResult.Error(
                        "Network error during Huawei sign-in. Check your connection and try again.",
                    )
                    STATUS_SCOPE_UNAUTHORIZED -> HuaweiSignInResult.Error(
                        "This app isn't authorized for Huawei sign-in yet. Verify the app signature and Account Kit settings in AppGallery Connect.",
                    )
                    else -> HuaweiSignInResult.Error(
                        "Huawei sign-in failed${statusCode?.let { " (code $it)" }.orEmpty()}. Try again or use email.",
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Huawei sign-in result parsing failed: ${e.message}")
            HuaweiSignInResult.Error("Could not complete Huawei sign-in. Try again or use email.")
        }
    }

    /**
     * Best-effort HMS session sign-out. The local Supabase session is cleared
     * separately by SupabaseSync — this only revokes the cached HMS state so
     * the next sign-in shows the account picker again.
     */
    fun signOut(context: Context) {
        if (!HuaweiServicesConfig.isConfigured || !isHmsAvailable(context)) return
        runCatching {
            AccountAuthManager.getService(context, authParams()).signOut()
        }.onFailure {
            Log.w(TAG, "Huawei sign-out failed: ${it.message}")
        }
    }

    /** Extracts the verifiable ID token, email, and display name from an [AuthAccount]. */
    private fun parseAuthAccount(account: AuthAccount?): HuaweiSignInResult {
        val idToken = account?.idToken?.takeIf { it.isNotBlank() }
        if (idToken.isNullOrBlank()) {
            Log.w(TAG, "Huawei sign-in returned no ID token")
            return HuaweiSignInResult.Error("No sign-in credential was returned. Try again.")
        }
        return HuaweiSignInResult.Success(
            idToken = idToken,
            email = account.email?.takeIf { it.isNotBlank() },
            displayName = account.displayName?.takeIf { it.isNotBlank() },
        )
    }
}
