import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// NOTE: these must be explicit imports. In a Gradle Kotlin DSL script the bare
// identifier `java` resolves to the JavaPluginExtension on the project, NOT to
// the `java` package — so fully-qualified references like `java.io.File`,
// `java.util.Base64` and `java.security.MessageDigest` fail to compile with
// "Unresolved reference: io/util/security".
import java.io.File
import java.security.MessageDigest
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Build-time configuration (Supabase + Huawei Account Kit).
// ---------------------------------------------------------------------------
val rorkConfigFile = file("src/main/java/com/rork/mindsetframes/Config.kt")

fun rorkConfigValue(key: String): String {
    if (!rorkConfigFile.exists()) return ""
    val text = rorkConfigFile.readText()
    val fromConst = Regex("const val $key = \"([^\"]*)\"").find(text)?.groupValues?.get(1)
    val fromMap = Regex("\"$key\" to \"([^\"]*)\"").find(text)?.groupValues?.get(1)
    return (fromConst ?: fromMap)?.trim().orEmpty()
}

fun gradlePropertyValue(name: String): String =
    providers.gradleProperty(name).orNull?.trim().orEmpty()

fun resolveRorkValue(privateName: String, publicName: String, propertyName: String): String {
    return sequenceOf(System.getenv(privateName), System.getenv(publicName))
        .mapNotNull { it?.trim() }
        .firstOrNull { it.isNotBlank() }
        ?: rorkConfigValue(publicName).ifBlank { gradlePropertyValue(propertyName) }
}

// ---------------------------------------------------------------------------
// Huawei agconnect-services.json support.
//
// NOTE: previously excluded com.huawei.hms:stats and com.huawei.hms:device
// globally here (likely to resolve an unrelated duplicate-class error at
// some point). Removed — Account Kit's sign-in flow touches Huawei's
// device-identification classes internally for its own risk checks even
// though app code never calls them directly, so excluding that module
// leaves the class present at compile time (project builds fine) but
// absent at runtime, which surfaces as NoClassDefFoundError the moment
// AccountAuthManager tries to use it — i.e. exactly on tapping the Huawei
// sign-in button. If a genuine duplicate-class build error reappears,
// exclude the conflicting module from the SPECIFIC dependency that pulls
// the extra copy in (via `implementation(...) { exclude(...) }` on just
// that one `implementation` line), not globally via `configurations.all`.
// ---------------------------------------------------------------------------

// ── Huawei app identity for HMS Core ─────────────────────────────────────────
// HMS Core runs in its own process and identifies the calling app from THIS
// APK's manifest meta-data (`com.huawei.hms.client.appid` — see
// AndroidManifest.xml), not from the AGConnect instance created in-process in
// HuaweiServicesConfig. With the AGConnect Gradle plugin applied that meta-data
// is generated automatically; this project applies no such plugin, so it is
// declared by hand and fed from here. Without it every Huawei IAP call fails
// with ORDER_STATE_IAP_NOT_ACTIVATED (60002) even though
// agconnect-services.json is valid and Huawei sign-in works.
//
// The values are read out of whatever AGC config this build resolves (see the
// resolution block below), so the manifest can never drift from
// client.app_id / client.cp_id (the first occurrence of each key is the
// "client" block).
//
// NOTE: there is deliberately NO hardcoded app_id / cp_id fallback any more.
// A previous revision fell back to the literals "118642709" /
// "30063000033888672", which meant a build with NO config still declared a real
// Huawei app identity in its manifest: HMS Core accepted the app id, the app
// looked configured, and the true failure (no agconnect-services.json in the
// APK assets) surfaced only later as a vague "sign-in isn't set up yet" — or as
// Huawei IAP error 60002. A blank value is the honest answer, and when it is
// blank this build now says so loudly (see the warning below).
//
// ── How agconnect-services.json reaches the build ──────────────────────────
// The real file carries live credentials (client_secret, api_key) and is
// gitignored — see SECURITY.md and HUAWEI_SIGNIN_SETUP.md. It is resolved from
// the first source that supplies one, in this order:
//
//   1. android/app/agconnect-services.json        (local working tree)
//   2. AGCONNECT_SERVICES_JSON          env var, raw JSON    (CI / scripts)
//   3. AGCONNECT_SERVICES_JSON_BASE64   env var, base64      (CI secrets)
//   4. -Pagconnect.servicesJson=<json>  Gradle property
//
// Only presence and the non-secret identifiers (app_id / cp_id) are ever
// logged — never a secret value.
val agconnectSource: Pair<String, String> = run {
    val fromFile = runCatching {
        // Read through a plain java.io.File resolved from the project dir, so
        // this is independent of how Gradle decorates its own file accessors.
        File(layout.projectDirectory.asFile, "agconnect-services.json")
            .takeIf { it.isFile }?.readText()
    }.getOrNull()
    val fromEnvRaw = System.getenv("AGCONNECT_SERVICES_JSON")
    val fromEnvBase64 = System.getenv("AGCONNECT_SERVICES_JSON_BASE64")
        ?.let { encoded ->
            runCatching {
                String(Base64.getMimeDecoder().decode(encoded.trim()))
            }.getOrNull()
        }
    val fromProperty = providers.gradleProperty("agconnect.servicesJson").orNull

    val candidates = listOf(
        "local android/app/agconnect-services.json" to fromFile,
        "AGCONNECT_SERVICES_JSON env" to fromEnvRaw,
        "AGCONNECT_SERVICES_JSON_BASE64 env" to fromEnvBase64,
        "agconnect.servicesJson property" to fromProperty,
    )
    val hit = candidates.firstOrNull { !it.second.isNullOrBlank() }
    (hit?.first ?: "none") to hit?.second?.trim().orEmpty()
}
val agconnectConfigSource = agconnectSource.first
val agconnectConfigResolved = agconnectSource.second

val agconnectConfigText = agconnectConfigResolved
val huaweiAgcAppId = Regex("\"app_id\"\\s*:\\s*\"([^\"]+)\"")
    .find(agconnectConfigText)?.groupValues?.get(1)?.trim().orEmpty()
val huaweiAgcCpId = Regex("\"cp_id\"\\s*:\\s*\"([^\"]+)\"")
    .find(agconnectConfigText)?.groupValues?.get(1)?.trim().orEmpty()

if (huaweiAgcAppId.isBlank()) {
    logger.lifecycle(
        "\n!!! HUAWEI SIGN-IN WILL BE DISABLED IN THIS BUILD.\n" +
            "    No agconnect-services.json could be resolved — looked at a local file, " +
            "AGCONNECT_SERVICES_JSON, AGCONNECT_SERVICES_JSON_BASE64 and the " +
            "agconnect.servicesJson property.\n" +
            "    The APK still compiles and runs, but 'Sign in with HUAWEI ID' will say the " +
            "build has no Huawei config, and every Huawei IAP call will fail.\n" +
            "    FIX: put the real file at android/app/agconnect-services.json, or set the " +
            "AGCONNECT_SERVICES_JSON_BASE64 repository secret — see HUAWEI_SIGNIN_SETUP.md.\n",
    )
} else {
    logger.lifecycle(
        "Huawei AGC config resolved from $agconnectConfigSource " +
            "(app_id=$huaweiAgcAppId, cp_id=$huaweiAgcCpId)",
    )
}

/**
 * Irreversible SHA-256 of the resolved AGC config, used only as a Gradle
 * up-to-date input so the asset task re-runs when the config changes. A real
 * digest rather than [String.hashCode] because this value can end up in Gradle
 * logs and build scans — the config itself carries `client_secret` / `api_key`
 * and must never be logged or fingerprinted reversibly.
 */
fun agconnectFingerprint(json: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(json.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

val agconnectGeneratedAssets = layout.buildDirectory.dir("generated/agconnect/assets")

// Materialises the resolved config into the assets dir that sourceSets.main
// pulls in. Written from the RESOLVED text rather than copied from a file
// path, so every source above lands in the APK identically. The previous
// implementation copied only android/app/agconnect-services.json, which is
// precisely why a CI build that had the config injected as a secret still
// shipped an APK containing no Huawei config at all.
val copyAgconnectServices = tasks.register("copyAgconnectServices") {
    val json = agconnectConfigResolved
    val outDir = agconnectGeneratedAssets
    inputs.property("agconnectConfigPresent", json.isNotBlank())
    // A SHA-256 (not String.hashCode) so this change-detection fingerprint is
    // safe to appear in Gradle logs and build scans: it is irreversible, and
    // the raw config — which carries client_secret / api_key — never is.
    if (json.isNotBlank()) {
        inputs.property("agconnectConfigFingerprint", agconnectFingerprint(json))
    }
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        val target = File(dir, "agconnect-services.json")
        if (json.isNotBlank()) {
            target.writeText(json)
        } else if (target.exists()) {
            // Never leave a stale copy from an earlier build behind: a leftover
            // file would make the APK claim to be configured when it is not.
            target.delete()
        }
    }
}

android {
    namespace = "com.rork.mindsetframestracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mindsetframes.habittracker"
        minSdk = 24
        targetSdk = 36
        // UPDATE RELEASE: must be strictly higher than the versionCode live on
        // AppGallery — required both for the update to install over the
        // existing app AND for IAP sandbox testing (sandbox activates only
        // when the test build's versionCode exceeds the released one).
        versionCode = 22
        versionName = "1.1.1"

        // Backs the com.huawei.hms.client.appid / .cpid meta-data in
        // AndroidManifest.xml — see the "Huawei app identity" block above.
        manifestPlaceholders["huaweiAppId"] = huaweiAgcAppId
        manifestPlaceholders["huaweiCpId"] = huaweiAgcCpId

        val supabaseUrl = resolveRorkValue("SUPABASE_URL", "EXPO_PUBLIC_SUPABASE_URL", "mindset.supabaseUrl")
        val supabaseAnonKey = resolveRorkValue("SUPABASE_ANON_KEY", "EXPO_PUBLIC_SUPABASE_ANON_KEY", "mindset.supabaseAnonKey")
        // Strava OAuth client id — the PUBLIC numeric id from strava.com/settings/api
        // (the client secret lives ONLY in the strava-token-exchange Edge Function).
        val stravaClientId = resolveRorkValue("STRAVA_CLIENT_ID", "EXPO_PUBLIC_STRAVA_CLIENT_ID", "mindset.stravaClientId")

        // Polar AccessLink OAuth client id — the PUBLIC numeric id only.
        // The client SECRET is deliberately not read here: Polar token
        // exchange goes through the polar-token-exchange Edge Function, which
        // holds it server-side. (This previously also resolved
        // POLAR_CLIENT_SECRET_KEY and baked it into BuildConfig, which meant
        // the secret shipped inside the APK.)
        val polarClientId = resolveRorkValue("POLAR_CLIENT_ID", "EXPO_PUBLIC_POLAR_CLIENT_ID", "mindset.polarClientId")

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "STRAVA_CLIENT_ID", "\"$stravaClientId\"")
        buildConfigField("String", "POLAR_CLIENT_ID", "\"$polarClientId\"")
        // Whether THIS build actually bundled a Huawei AGC config, and where it
        // came from. Lets the app tell "this APK was compiled without
        // agconnect-services.json" (a build/setup problem) apart from "a config
        // was bundled but AGConnect couldn't initialise on this device" — the
        // two used to collapse into one vague message, which is exactly what
        // made a packaging gap look like an app bug.
        buildConfigField("boolean", "HUAWEI_AGC_CONFIG_BUNDLED", agconnectConfigResolved.isNotBlank().toString())
        buildConfigField("String", "HUAWEI_AGC_CONFIG_SOURCE", "\"$agconnectConfigSource\"")
        // NOTE: there is deliberately NO POLAR_CLIENT_SECRET BuildConfig field.
        // All Polar token exchange goes through the polar-token-exchange Edge
        // Function, which holds the secret server-side. A buildConfigField here
        // would bake the secret into the APK, where anyone can read it out of
        // the dex — see PolarClient.exchangeCodeForTokens().

        println(
            "Rork config — supabase: ${if (supabaseUrl.isBlank()) "missing (sync hidden)" else "resolved"}" +
            ", polar: ${if (polarClientId.isBlank()) "missing" else "resolved"}"
        )
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(agconnectGeneratedAssets)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.koin.androidx.compose)
    implementation(libs.androidx.browser)

    // Huawei Account Kit
    implementation(libs.huawei.hwid)

    // Huawei AppGallery Connect Core
    implementation(libs.huawei.agconnect.core)

    // Huawei App Update (JosApps / AppUpdateClient live here)
    implementation(libs.huawei.update)

    // HMS Core modules — pinned to match libs.huawei.hwid (6.12.0.300) so
    // Account Kit's compiled references resolve against the SAME base
    // module version it was built against. A mismatched base version here
    // causes NoSuchMethodError/NoClassDefFoundError at the exact moment
    // AccountAuthManager.getService(...) runs — which is an Error, not an
    // Exception, so it is NOT caught by "catch (e: Exception)" anywhere in
    // HuaweiAuthClient.kt and force-stops the app instead of returning a
    // friendly error message.
    //
    // IMPORTANT: com.huawei.hms:base and com.huawei.hms:iap do NOT publish
    // a 6.14.x release (only hwid and appservice do) — pinning them to
    // 6.14.0.300 fails to resolve entirely. base:6.12.0.300 is a verified,
    // mutually-compatible release with hwid:6.12.0.300 (Huawei's own POM
    // metadata for hwid:6.12.0.300 declares base >= 6.12.0.300).
    //
    // iap does NOT share hwid/base's version line at all — it's an
    // independently-versioned kit that jumped straight from 6.13.x to
    // 6.16.x, so iap:6.12.0.300 was never published either. 6.13.0.300 is
    // the closest confirmed-real iap release to this hwid/base vintage.
    implementation("com.huawei.hms:base:6.12.0.300")
    implementation("com.huawei.hms:iap:6.13.0.300")

    // AndroidX Health Connect
    implementation(libs.androidx.health.connect.client)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.pdfbox.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

tasks.matching {
    it.name.matches(Regex("merge.*Assets")) || it.name.contains("Lint", ignoreCase = true)
}.configureEach {
    dependsOn(copyAgconnectServices)
}

// ── Final sanity check on the resolved AGC config ──────────────────────────
//
// The config's `client.package_name` must equal this app's `applicationId`.
// When it does not, everything still compiles and the APK still bundles the
// config, but Account Kit rejects sign-in server-side — the picker opens and
// closes itself, with no HMS status code, which is indistinguishable from the
// "fingerprint not registered" case and sends you chasing the wrong problem
// (see HuaweiServicesConfig.parseResult).
//
// Checked in afterEvaluate because `android.defaultConfig.applicationId` is only
// resolved once the android {} block above has been evaluated. Warned rather
// than failed: a mismatch is a console/file error the developer fixes by
// re-downloading the config for the right AGC project — not something a build
// should refuse to produce.
afterEvaluate {
    val configPackageName = Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"")
        .find(agconnectConfigText)?.groupValues?.get(1)?.trim().orEmpty()
    val appId = android.defaultConfig.applicationId
    if (configPackageName.isNotBlank() && appId != null && configPackageName != appId) {
        logger.lifecycle(
            "\n!!! HUAWEI AGC CONFIG PACKAGE NAME MISMATCH.\n" +
                "    The resolved agconnect-services.json declares package_name=$configPackageName\n" +
                "    but this app's applicationId is $appId.\n" +
                "    The APK will build and bundle the config, but HUAWEI ID sign-in will be\n" +
                "    rejected by Account Kit (the sign-in screen opens then closes itself) and\n" +
                "    no HMS status code is reported.\n" +
                "    FIX: download agconnect-services.json from the AppGallery Connect project\n" +
                "    whose package name is $appId — see HUAWEI_SIGNIN_SETUP.md §6.\n",
        )
    }
}
