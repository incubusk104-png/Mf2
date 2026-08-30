# Mindset Frames — R8/ProGuard rules.
# Release builds run R8 with shrinking and obfuscation enabled, but R8 method
# inlining is disabled for kotlinx.serialization because it can produce generated
# serializer methods with >64 registers, which ART rejects with VerifyError at
# cold start (see app/build.gradle.kts).

# ── Disable R8 optimization entirely for serialization safety ─────────
-dontoptimize
-optimizationpasses 0
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions, LocalVariableTable, LocalVariableTypeTable, MethodParameters
-dontnote kotlinx.serialization.AnnotationsKt

# kotlinx.serialization — keep the runtime, JSON classes, and all serializers
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keep class kotlinx.serialization.json.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclasseswithmembers class kotlinx.serialization.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
-keepclassmembers class * extends kotlinx.serialization.KSerializer { *; }

# App serializers — keep the generated $$serializer classes and the metadata
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keep,includedescriptorclasses class com.rork.mindsetframestracker.**$$serializer { *; }
-keepclassmembers class com.rork.mindsetframestracker.** { *** Companion; }
-keepclasseswithmembers class com.rork.mindsetframestracker.** { kotlinx.serialization.KSerializer serializer(...); }
-keep class com.rork.mindsetframestracker.data.** { *; }
-keepclassmembers class com.rork.mindsetframestracker.data.** { *; }
-keep class com.rork.mindsetframestracker.util.** { *; }
-keepclassmembers class com.rork.mindsetframestracker.util.** { *; }

# Keep the @Serializable annotations themselves so the runtime can discover
# serializers even when classes are obfuscated.
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }

# Ktor / coroutines
-dontwarn kotlinx.coroutines.**
-dontwarn io.ktor.**
-keep class io.ktor.client.engine.android.** { *; }
-keep class kotlinx.coroutines.android.** { *; }

# AndroidViewModel subclasses are constructed reflectively by the default
# ViewModelProvider factory — keep the (Application) constructor.
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# WorkManager instantiates workers reflectively — keep their constructors.
-keep class * extends androidx.work.CoroutineWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Release log stripping ─────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# ── AndroidX Health Connect ─────────────────────────────────────────────
# R8 strips the ActivityResultContract returned by
# PermissionController.createRequestPermissionResultContract(). Without
# these rules the permission launcher silently fails in release builds
# because the contract class is renamed/removed by minification.
-keep class androidx.health.connect.client.** { *; }
-keep class androidx.health.connect.client.permission.** { *; }
-keep class androidx.health.connect.client.records.** { *; }
-keep class androidx.health.connect.client.impl.** { *; }
-keep class androidx.health.connect.client.aggregate.** { *; }
-keep class androidx.health.connect.client.request.** { *; }
-keep class androidx.health.connect.client.time.** { *; }
# Health Connect IPC transport layer — the client library communicates
# with the Health Connect provider APK via this package. If R8 strips
# or renames these classes the IPC calls fail silently at runtime.
-keep class androidx.health.platform.client.** { *; }
-keep class androidx.health.** { *; }

# The permission-result contract is an ActivityResultContract subclass.
# Keep all ActivityResultContract implementations so the Activity Result
# API can resolve them reflectively at runtime.
-keep class * extends androidx.activity.result.contract.ActivityResultContract { *; }

# Our own Health Connect wrapper — sealed interface subtypes must survive
# obfuscation so when() branches resolve correctly at runtime.
-keep class com.rork.mindsetframestracker.integrations.HealthConnectStatus { *; }
-keep class com.rork.mindsetframestracker.integrations.HealthConnectStatus$* { *; }
-keep class com.rork.mindsetframestracker.integrations.MindsetHealthConnectClient { *; }

# ── HMS Core / Account Kit / AGConnect ─────────────────────────────────
-keep class com.huawei.hms.** { *; }
-keep interface com.huawei.hms.** { *; }
-keep class com.huawei.hwid.** { *; }
-keep class com.huawei.agconnect.** { *; }
-keep class com.huawei.hmf.** { *; }
-dontwarn com.huawei.hms.**
-dontwarn com.huawei.hwid.**
-dontwarn com.huawei.agconnect.**
-dontwarn com.huawei.hmf.**

# ── HMS AppGallery update-check (appservice / updatesdk) ───────────────
-keep class com.huawei.updatesdk.** { *; }
-keep interface com.huawei.updatesdk.** { *; }
-dontwarn com.huawei.updatesdk.**
-keep class com.huawei.android.hms.** { *; }
-dontwarn com.huawei.android.hms.**

# ── R8 missing-class fix: optional Huawei classes not in our classpath ──
# appservice pulls in markethomecountrysdk + serviceverifykit, which
# reference these at runtime only on certain Huawei device configs. Not
# on our compile classpath and not needed — tell R8 not to fail on them.
-dontwarn com.huawei.android.app.**
-dontwarn com.huawei.appgallery.**

# ── R8 missing-class fix: optional JPEG2000 codec used by PdfBox-Android ──
# JPXFilter references com.gemalto.jp2.JP2Decoder only for JPEG2000-encoded
# images inside PDFs. We don't ship the gemalto jp2 codec (not needed for
# standard PDF text/image handling) — tell R8 not to fail on the missing ref.
-dontwarn com.gemalto.jp2.**
