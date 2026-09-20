# Verify connect-global

- base (main): `7e56c3fb3cf29eb0df5df362d2318decdf4a4487`
- patch sha256: `ebf91ca6c226f69eb3932880d4248da770de6a40b23e67f525a4de3e260689fb`
- verified commit: `4eb8430562a133b047fef4dc50be25b0e8ccef4d`
- unit tests: **success**
- assembleDebug: **success**

## unit tests
```
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/integrations/ScreenTimeMonitor.kt:480:35 'static field MOVE_TO_BACKGROUND: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/integrations/ScreenTimeMonitor.kt:531:35 'static field MOVE_TO_FOREGROUND: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/integrations/ScreenTimeMonitor.kt:535:35 'static field MOVE_TO_BACKGROUND: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:510:21 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:643:17 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:644:17 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:947:21 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:948:21 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:949:21 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:950:21 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:1107:17 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:1108:17 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:1200:17 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:1202:17 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:1207:17 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/avatar/CompanionAvatar.kt:1209:17 'fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float): Unit' is deprecated. Use quadraticTo() for consistency with cubicTo().
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/components/ActivitySourcePicker.kt:299:67 'val Icons.Filled.DirectionsRun: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.DirectionsRun.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/components/AuthPromptSheet.kt:537:57 'val LocalClipboardManager: ProvidableCompositionLocal<ClipboardManager>' is deprecated. Use LocalClipboard instead which supports suspend functions.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/components/MinimizedHabitChip.kt:129:59 Unnecessary safe call on a non-null receiver of type 'ActiveTimer'.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/components/MinimizedHabitChip.kt:184:33 Condition is always 'true'.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/components/ScreenTimeHabitSheet.kt:561:46 Unnecessary safe call on a non-null receiver of type 'ScreenTimeMonitor.LimitStatus'.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/components/ScreenTimeHabitSheet.kt:701:67 Unnecessary non-null assertion (!!) on a non-null receiver of type 'Int'.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/components/ShareHabitsSheet.kt:174:34 Redundant creation of Json format. Creating instances for each usage can be slow.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/screens/AlarmPermissionPrompt.kt:410:26 'val LocalLifecycleOwner: ProvidableCompositionLocal<LifecycleOwner>' is deprecated. Moved to lifecycle-runtime-compose library in androidx.lifecycle.compose package.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/screens/HomeScreen.kt:746:36 'fun rememberSwipeToDismissBoxState(initialValue: SwipeToDismissBoxValue = ..., confirmValueChange: (SwipeToDismissBoxValue) -> Boolean = ..., positionalThreshold: (Float) -> Float = ...): SwipeToDismissBoxState' is deprecated. confirmValueChange is deprecated without replacement. Rather than relying on a callback to veto state changes, the anchor set should not include disallowed anchors. See androidx.compose.foundation.samples.AnchoredDraggableDynamicAnchorsSample for an example of using dynamic anchors over confirmValueChange.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/screens/SettingsScreen.kt:1756:42 'val Icons.Outlined.Logout: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Outlined.Logout.
w: file:///home/runner/work/Mf2/Mf2/android/app/src/main/java/com/rork/mindsetframestracker/ui/screens/SettingsScreen.kt:1825:50 'val Icons.Outlined.Logout: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Outlined.Logout.

> Task :app:processDebugJavaRes
> Task :app:compileDebugJavaWithJavac
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 29s
26 actionable tasks: 11 executed, 15 from cache
Configuration cache entry stored.
```
## assembleDebug
```
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:mergeExtDexDebug
> Task :app:compressDebugAssets
> Task :app:mergeLibDexDebug
> Task :app:mergeDebugJavaResource
> Task :app:validateSigningDebug
> Task :app:writeDebugAppMetadata
> Task :app:writeDebugSigningConfigVersions
> Task :app:mergeDebugJniLibFolders
> Task :app:dexBuilderDebug
> Task :app:mergeProjectDexDebug
> Task :app:mergeDebugNativeLibs

> Task :app:stripDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so. Run with --info option to learn more.

> Task :app:packageDebug
> Task :app:assembleDebug
> Task :app:createDebugApkListingFileRedirect

BUILD SUCCESSFUL in 2m 17s
40 actionable tasks: 20 executed, 20 up-to-date
Configuration cache entry stored.
```
## static assertions
```
ok (dialog path): android/app/src/main/java/com/rork/mindsetframestracker/ui/components/HabitTrackerConnectHost.kt
ok (dialog path): android/app/src/main/java/com/rork/mindsetframestracker/ui/components/TrackerConnectSheet.kt
fail=0
```
