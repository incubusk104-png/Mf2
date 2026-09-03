import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

val agconnectGeneratedAssets = layout.buildDirectory.dir("generated/agconnect/assets")
val copyAgconnectServices = tasks.register<Copy>("copyAgconnectServices") {
    from(layout.projectDirectory.file("agconnect-services.json"))
    into(agconnectGeneratedAssets)
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
        versionCode = 21
        versionName = "1.1.0"

        val supabaseUrl = resolveRorkValue("SUPABASE_URL", "EXPO_PUBLIC_SUPABASE_URL", "mindset.supabaseUrl")
        val supabaseAnonKey = resolveRorkValue("SUPABASE_ANON_KEY", "EXPO_PUBLIC_SUPABASE_ANON_KEY", "mindset.supabaseAnonKey")
        // Strava OAuth client id — the PUBLIC numeric id from strava.com/settings/api
        // (the client secret lives ONLY in the strava-token-exchange Edge Function).
        val stravaClientId = resolveRorkValue("STRAVA_CLIENT_ID", "EXPO_PUBLIC_STRAVA_CLIENT_ID", "mindset.stravaClientId")

        // Polar AccessLink OAuth credentials — both are needed client-side because
        // Polar's token exchange uses HTTP Basic auth (client_id:client_secret).
        // Unlike Strava (which proxies through an Edge Function), Polar's token
        // endpoint is called directly from the app.
        val polarClientId = resolveRorkValue("POLAR_CLIENT_ID", "EXPO_PUBLIC_POLAR_CLIENT_ID", "mindset.polarClientId")
        val polarClientSecret = resolveRorkValue("POLAR_CLIENT_SECRET_KEY", "EXPO_PUBLIC_POLAR_CLIENT_SECRET", "mindset.polarClientSecret")

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "STRAVA_CLIENT_ID", "\"$stravaClientId\"")
        buildConfigField("String", "POLAR_CLIENT_ID", "\"$polarClientId\"")
        buildConfigField("String", "POLAR_CLIENT_SECRET", "\"$polarClientSecret\"")

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
