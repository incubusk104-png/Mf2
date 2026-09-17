package com.rork.mindsetframestracker.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.huawei.agconnect.AGConnectInstance
import com.huawei.agconnect.AGConnectOptionsBuilder
import com.rork.mindsetframestracker.BuildConfig
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

    /** Manifest meta-data key HMS Core reads to identify the calling app. */
    private const val HMS_APPID_META = "com.huawei.hms.client.appid"

    @Volatile
    private var initialized = false

    @Volatile
    private var configuredAppId: String? = null

    /** `client.package_name` from the bundled config, or null when none was loaded. */
    @Volatile
    private var configuredPackageName: String? = null

    /** Human-readable reason the last [initialize] attempt failed, if any — surfaced in
     * the UI because R8 strips Log calls in release builds, making logcat useless here. */
    @Volatile
    var lastError: String? = null
        private set

    /** True when a valid agconnect-services.json was found and AGConnect initialized. */
    val isConfigured: Boolean
        get() = initialized && !configuredAppId.isNullOrBlank()

    /**
     * The HMS Core app identity declared in AndroidManifest.xml as
     * `com.huawei.hms.client.appid`, normalised to the `appid=<id>` form
     * ("" when the meta-data is missing).
     *
     * HMS Core runs in its OWN process, so it identifies the calling app from
     * this manifest entry — not from the AGConnect instance [initialize] builds
     * in-process. The AGConnect Gradle plugin injects the entry automatically;
     * this project applies no such plugin, so it is declared by hand (fed from
     * agconnect-services.json in app/build.gradle.kts). When it is blank every
     * Huawei IAP call fails with ORDER_STATE_IAP_NOT_ACTIVATED (60002) while
     * Huawei sign-in — which resolves its app id by another path — keeps
     * working, so the omission is invisible from symptoms alone.
     */
    fun manifestHmsAppId(context: Context): String {
        return runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
            when (val raw = info.metaData?.getString(HMS_APPID_META)) {
                null -> ""
                "" -> ""
                else -> if (raw.startsWith("appid=")) raw else "appid=$raw"
            }
        }.onFailure {
            Log.w(TAG, "Could not read $HMS_APPID_META from the manifest: ${it.message}")
        }.getOrDefault("")
    }

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
            // Distinguish the two very different causes of "no config": this APK
            // was BUILT without one (a packaging/setup problem — fix it in the
            // build, see HUAWEI_SIGNIN_SETUP.md) versus one was supposed to be
            // bundled and didn't reach the APK assets (a packaging bug).
            // BuildConfig.HUAWEI_AGC_CONFIG_BUNDLED is set by build.gradle.kts
            // from the same resolution step that produces the bundled asset, so
            // the two can never disagree at runtime.
            val bundledAtBuildTime = BuildConfig.HUAWEI_AGC_CONFIG_BUNDLED
            lastError = if (bundledAtBuildTime) {
                "$CONFIG_ASSET was bundled for this build but is missing from the APK assets"
            } else {
                "this build was compiled without a Huawei AGC config ($CONFIG_ASSET)"
            }
            Log.i(
                TAG,
                "No $CONFIG_ASSET bundled — Huawei sign-in stays disabled until one is " +
                    "provided to the build (lastError=$lastError)",
            )
            logDiagnostic(
                appContext,
                "initialize(): no AGC config bundled. " +
                    "HUAWEI_AGC_CONFIG_BUNDLED=$bundledAtBuildTime, " +
                    "build source=${BuildConfig.HUAWEI_AGC_CONFIG_SOURCE}",
            )
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
            configuredPackageName = packageName
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
                    appendLine(
                        "manifest $HMS_APPID_META: " +
                            manifestHmsAppId(appContext).ifBlank {
                                "<MISSING — every Huawei IAP call will fail with 60002>"
                            },
                    )
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
     * Detects a `package_name` mismatch between the bundled AGC config and this
     * app's real applicationId — the one config problem that produces NO error
     * code and NO log, so it looks exactly like an unregistered signing
     * certificate.
     *
     * Account Kit validates the config's `package_name` server-side. When the
     * two disagree (typically: agconnect-services.json downloaded for a
     * different AppGallery Connect app than the one this build targets) the
     * sign-in screen opens and instantly closes itself with RESULT_CANCELED and
     * no HMS status code, which [HuaweiAuthClient.parseResult] can only guess
     * at. Checking here turns that guess into a named cause.
     *
     * @return a user-facing message when the two disagree, or null when they
     *   match (or the config carries no package_name to compare against).
     */
    fun checkAgcConfigPackageMatches(context: Context): String? {
        val declared = configuredPackageName?.takeIf { it.isNotBlank() } ?: return null
        val actual = context.packageName
        if (declared == actual) return null

        logDiagnostic(
            context,
            "startSignIn blocked: AGC config package_name ($declared) != applicationId ($actual)",
        )
        val fingerprint = signingCertSha256(context)
            .firstOrNull()
            ?: "<could not read — check huawei_diagnostics.txt>"
        return buildString {
            append(
                "Huawei sign-in can't work with this build's configuration: the bundled " +
                    "agconnect-services.json is for a different app.\n\n",
            )
            append("agconnect-services.json declares package_name: $declared\n")
            append("This build's applicationId:                 $actual\n\n")
            append(
                "Account Kit checks that package name and rejects the request before any " +
                    "account picker appears, so this looks identical to a signing-certificate " +
                    "problem. Download agconnect-services.json from the AppGallery Connect " +
                    "project whose package name is $actual and rebuild — see " +
                    "HUAWEI_SIGNIN_SETUP.md.\n\n",
            )
            append("This build's SHA-256 signing fingerprint (register it in AGC too):\n$fingerprint")
        }
    }

    /**
     * The user-facing explanation shown when Huawei sign-in can't even start
     * because no usable AGC config is present.
     *
     * Kept here rather than inline in [HuaweiAuthClient] so the on-device
     * diagnostics file, the log, and the UI all describe the same cause in the
     * same words — and so the message can name the actual next step instead of
     * restating the symptom.
     *
     * @param reason [lastError], or null when [initialize] never ran.
     */
    fun notConfiguredMessage(reason: String?): String {
        val cause = if (BuildConfig.HUAWEI_AGC_CONFIG_BUNDLED) {
            "the Huawei config bundled with this build couldn't be loaded"
        } else {
            "this app build was compiled without a Huawei config (agconnect-services.json)"
        }
        return buildString {
            append("Huawei sign-in isn't available — $cause.")
            if (!reason.isNullOrBlank()) append("\n\nDetails: $reason")
            append(
                "\n\nThis is a build/setup step, not a device problem. To enable it, provide " +
                    "agconnect-services.json at build time (a local android/app/ file, or the " +
                    "AGCONNECT_SERVICES_JSON_BASE64 repository secret) and rebuild — see " +
                    "HUAWEI_SIGNIN_SETUP.md. Then register this build's SHA-256 fingerprint in " +
                    "AppGallery Connect.\n\nEmail sign-in backs up exactly the same data — " +
                    "use it meanwhile.",
            )
        }
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

    /**
     * Reads back everything [logDiagnostic] has recorded so far, newest
     * entries last. Lets the in-app diagnostics viewer show this without
     * requiring adb — most testers debugging a phone-only build have no
     * way to run `adb shell run-as ... cat cache/huawei_diagnostics.txt`.
     * Returns a friendly placeholder instead of throwing when the file
     * doesn't exist yet (e.g. sign-in was never attempted).
     */
    fun readDiagnostics(context: Context): String {
        return runCatching {
            val file = File(context.applicationContext.cacheDir, DIAGNOSTICS_FILE)
            if (file.exists()) file.readText() else "No diagnostics recorded yet — try Huawei sign-in once, then check again."
        }.getOrDefault("Could not read diagnostics file.")
    }
}
