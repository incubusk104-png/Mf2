package com.rork.mindsetframestracker.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.huawei.agconnect.AGConnectInstance
import com.huawei.agconnect.AGConnectOptionsBuilder
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads and validates the Huawei AGConnect configuration file
 * (agconnect-services.json) bundled in the APK assets, then initializes the
 * AGConnect SDK with it so HUAWEI Account Kit can authenticate.
 *
 * Setup: download agconnect-services.json from AppGallery Connect
 * (Project settings > General information > App information) and place it at
 * android/app/agconnect-services.json — the standard Huawei location. The
 * Gradle build copies it into assets automatically (see app/build.gradle.kts).
 *
 * When the file is missing or incomplete, Huawei sign-in reports a friendly
 * "not configured" message and the app keeps working with email sign-in only —
 * initialization never crashes the app.
 *
 * ── Diagnosing "the Huawei sign-in dialog flashes open and closes itself" ──
 * When agconnect-services.json is valid AND HMS Core is installed, this is
 * almost always a SHA-256 signing-certificate mismatch: AppGallery Connect
 * only lets the sign-in flow complete for certificate fingerprints you've
 * registered against this app (Project settings > General information >
 * App information > SHA-256 certificate fingerprint). If the APK you're
 * testing was signed with a keystore whose fingerprint ISN'T registered
 * there, HMS Core's own "Sign in with HUAWEI ID" activity opens, gets
 * rejected server-side, and finishes itself with RESULT_CANCELED — with no
 * HMS status code at all, so the app-side code below can't tell that apart
 * from a genuine user-cancel. There is no code fix for this: it's a
 * one-time console setup step.
 *
 * To get the fingerprint that actually needs registering, run from the
 * android/ directory:
 *     ./gradlew signingReport
 * and copy the SHA-256 line for the variant you're testing (debug vs
 * release use DIFFERENT keystores/fingerprints — both need to be added in
 * AppGallery Connect if you test both). [logSigningCertDiagnostics] below
 * writes the same fingerprint into a small on-device file as a convenience
 * (R8 strips all android.util.Log calls in release builds — see the
 * proguard rule — so logcat alone won't show this in a release build).
 */
object HuaweiServicesConfig {

    private const val TAG = "HuaweiServicesConfig"
    private const val CONFIG_ASSET = "agconnect-services.json"
    private const val DIAGNOSTICS_FILE = "huawei_diagnostics.txt"

    @Volatile
    private var initialized = false

    @Volatile
    private var configuredAppId: String? = null

    /** Human-readable reason the last [initialize] attempt failed, if any — surfaced in
     * the UI because R8 strips Log calls in release builds, making logcat useless here. */
    @Volatile
    var lastError: String? = null
        private set

    /** True when a valid agconnect-services.json was found and AGConnect initialized. */
    val isConfigured: Boolean
        get() = initialized && !configuredAppId.isNullOrBlank()

    /**
     * Reads agconnect-services.json from assets, validates the client block,
     * and initializes AGConnect. Idempotent and failure-safe: errors are
     * logged and leave the app fully functional without Huawei sign-in.
     */
    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        val raw = runCatching {
            appContext.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            lastError = "No $CONFIG_ASSET bundled in the APK"
            Log.i(TAG, "No $CONFIG_ASSET bundled — Huawei sign-in stays disabled until it's added")
            return
        }

        val client = runCatching {
            Json { ignoreUnknownKeys = true }
                .parseToJsonElement(raw).jsonObject["client"]?.jsonObject
        }.getOrNull()
        val appId = client?.get("app_id")?.jsonPrimitive?.content?.trim().orEmpty()
        val clientId = client?.get("client_id")?.jsonPrimitive?.content?.trim().orEmpty()
        val packageName = client?.get("package_name")?.jsonPrimitive?.content?.trim().orEmpty()

        if (appId.isBlank() || clientId.isBlank()) {
            lastError = "$CONFIG_ASSET is missing client/app_id or client/client_id"
            Log.w(TAG, "$CONFIG_ASSET is missing client/app_id or client/client_id — Huawei sign-in disabled")
            return
        }
        if (packageName.isNotBlank() && packageName != appContext.packageName) {
            Log.w(
                TAG,
                "$CONFIG_ASSET package_name ($packageName) doesn't match ${appContext.packageName} — " +
                    "Huawei sign-in will fail until the AppGallery Connect app config matches",
            )
        }

        runCatching {
            AGConnectInstance.initialize(
                appContext,
                AGConnectOptionsBuilder().setInputStream(appContext.assets.open(CONFIG_ASSET)),
            )
        }.onSuccess {
            initialized = true
            configuredAppId = appId
            lastError = null
            Log.i(TAG, "AGConnect initialized (app_id=$appId)")
        }.onFailure {
            lastError = "${it::class.simpleName}: ${it.message}"
            Log.w(TAG, "AGConnect initialization failed: ${it.message}")
        }

        // Always write a diagnostics snapshot — including the signing
        // certificate fingerprint that must match AppGallery Connect — so
        // an "opens then instantly closes" report can actually be debugged
        // even in a release build where Log is stripped. See the file
        // header for how to pull the SHA-256 that needs registering.
        runCatching {
            val fingerprints = signingCertSha256(appContext)
            logDiagnostic(
                appContext,
                buildString {
                    appendLine("── HuaweiServicesConfig.initialize() ──")
                    appendLine("agconnect app_id: $appId")
                    appendLine("agconnect client_id: $clientId")
                    appendLine("agconnect package_name: $packageName")
                    appendLine("actual applicationId: ${appContext.packageName}")
                    appendLine("isConfigured after init: $isConfigured")
                    appendLine("lastError: $lastError")
                    appendLine(
                        "signing cert SHA-256 (must be registered in AppGallery " +
                            "Connect > Project settings > App information): " +
                            fingerprints.joinToString().ifBlank { "<could not read>" },
                    )
                },
            )
        }
    }

    /**
     * SHA-256 fingerprint(s) of the certificate(s) this APK was actually
     * signed with, formatted the same way AppGallery Connect displays them
     * (colon-separated hex pairs). Usually a single entry; more than one
     * only appears for APK Signature Scheme v3 key-rotation setups.
     *
     * The same values are also printed by running `./gradlew signingReport`
     * in the android/ directory — that's the more reliable way to check
     * BEFORE a build even runs, since debug and release builds use
     * different keystores/fingerprints and both need to be registered if
     * you test both.
     */
    fun signingCertSha256(context: Context): List<String> {
        return runCatching {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo
                if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory ?: emptyArray()
                }
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures ?: emptyArray()
            }
            signatures.map { sig ->
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                digest.joinToString(":") { b -> "%02X".format(b) }
            }
        }.onFailure {
            Log.w(TAG, "Could not read signing certificate: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * Best-effort append to a small on-device diagnostics file (app cache
     * dir). Survives release R8 log-stripping, unlike android.util.Log.
     * Pull it with (on a debuggable build, or a rooted/adb-root device):
     *   adb shell run-as com.mindsetframes.habittracker \
     *     cat cache/huawei_diagnostics.txt
     * Capped to ~50 KB so it can never grow unbounded across many retries.
     */
    fun logDiagnostic(context: Context, message: String) {
        runCatching {
            val file = File(context.applicationContext.cacheDir, DIAGNOSTICS_FILE)
            val entry = "${System.currentTimeMillis()} $message\n\n"
            val existing = if (file.exists()) file.readText() else ""
            val combined = (existing + entry).takeLast(50_000)
            file.writeText(combined)
        }
    }
}
