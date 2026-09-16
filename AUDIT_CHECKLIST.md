# Mindset Frames — Full App Clean-up, Record-Capture & Configuration Audit

**Audited at commit:** `a8bdfc7` (branch `main`)
**Method:** every item below was verified by opening the real file and reading the real call path. Where an earlier run's claim is corrected, it is called out explicitly. Line numbers are from `a8bdfc7`.
**Rule applied:** nothing is deleted unless a repo-wide search proves zero live callers.

---

## Category (a) — Fully implemented and verified

| # | Feature | Evidence | Notes |
|---|---|---|---|
| a1 | Habit check-ins (streaks, badges, heatmap) | `data/Models.kt:328` `isCheckedOn`; writers `ui/AppViewModel.kt:1738`; read by `WeeklyScreen.kt:270`, `HomeScreen`, `InsightsScreen.kt:246` | Core loop complete |
| a2 | Habit logs (duration / journal / count payload) | Written `data/MindsetRepository.kt:194`, `ui/AppViewModel.kt:1751`, `notifications/TimerCompletion.kt:146`; read via `HabitTracking.kt:197-292`; surfaced `WeeklyScreen.kt:270,312,331` | `habitLogsFor`/`habitCountOn`/`todayCount`/`habitTotalsOver` all have live callers |
| a3 | Weekly screen reads real data | `WeeklyScreen.kt:270` `data.isHabitDoneOn(...)`, `:312` `data.habitLogs.any {...}`, `:331` log entries | Reads check-ins **and** logs (not just check-ins) |
| a4 | Insights screen reads real data | `InsightsScreen.kt:242-292` — `activityRecords` → `buildActivityDayIndex`, `rangeActivity`, `sourceTotals`; `moodHistory` → `MoodStat` | Reads activity + mood + check-ins |
| a5 | Strava integration | `integrations/StravaAuthClient.kt:197` `repo.saveActivityRecord(record)`; gated `:238` `Entitlements.hasAccess(tier, Feature.STRAVA)` | Persists; tier-gated |
| a6 | Health Connect integration | `integrations/HealthConnectClient.kt:134` → `ActivityMonitor.captureBlocking` → `saveActivityRecord` (`ActivityMonitor.kt:235,285`) | Persists via the shared monitor |
| a7 | Polar integration | `integrations/PolarClient.kt:427` `MindsetRepository(context).saveActivityRecord(record)` | Persists |
| a8 | Screen-time monitor (UsageStatsManager) | `integrations/ScreenTimeMonitor.kt`; permission already declared `AndroidManifest.xml:80` | Uses the platform API, **not** the excluded `com.huawei.hms:stats` |
| a9 | Tier / entitlement gating | `billing/Entitlements.kt`; consumers `ActivitySourcePicker.kt:248`, `StravaAuthClient.kt:238`, `SettingsScreen.kt:510`, `HabitsScreen.kt:112` | Gating is genuinely wired |
| a10 | Founding-member verified-purchase gate | `backend/supabase/migrations/20260916100000_founding_claim_requires_verified_purchase.sql` | Server-side verification, fail-closed |
| a11 | Account deletion covers tracking tables | `backend/supabase/migrations/20260916093000_delete_user_covers_tracking_tables.sql` — explicit `delete from public.activity_data`, `activity_connections`, `habit_logs` (catalog-guarded), `checkins`, `habits`, `mood_log`, `settings` | Compliance-complete |
| a12 | Sync push covers habit logs; pull covers logs + activities | `data/SupabaseSync.kt:1070-1078` upserts `habits`,`checkins`,`mood_log`,`settings`,`habit_logs`; `:1106-1107` pulls `habit_logs`, `activity_data` | Push and pull both present |
| a13 | Local deletion sync (pending deletes) | `SupabaseSync.kt:132,1064-1065,1237-1251` `KEY_PENDING_DELETES` + `DELETE /rest/v1/{table}` | A locally removed habit is really removed server-side |
| a14 | Alarm/notification scheduling + permission path | `notifications/AlarmScheduler.kt:105` `USE_EXACT_ALARM` check, `:223`; `MainActivity.kt:306-312` `POST_NOTIFICATIONS` request | Declared permission is actually requested and consulted |
| a15 | Battery-optimization exemption | `AndroidManifest.xml:65`; requested `AlarmPermissionPrompt.kt:805` `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; read `:116-142`, `AlarmDelivery.kt:170`, `HabitCheckInNotifier.kt:432` | Declared permission is genuinely used |
| a16 | i18n string table integrity | 503 keys referenced from `ui/AppStrings.kt`; **0** missing from `assets/strings/en.json` | `AppStrings.s()` returns `""` for a missing key — none are missing |

## Category (b) — Partially implemented / half-wired

| # | Item | Anchor | What is missing | Action taken |
|---|---|---|---|---|
| b1 | `presetTimes` quick-pick times | Written `ui/AppViewModel.kt:2044-2050` (`setPresetTime`); field `data/Models.kt:188`; string key `ui/AppStrings.kt:262` `settingsQuickPick` | **No reader anywhere.** `setPresetTime` has **zero** callers; `settingsQuickPick` is never displayed. The value can be persisted but never influences the quick-pick times. Harmless (no data loss — the field defaults to `emptyMap()` and is not synced), so this is **known debt, not a bug** | Documented here (b1). Not wired in this pass to keep the change set focused and avoid shipping untested UI |
| b2 | `AppGalleryLink` | `ui/components/PremiumSheet.kt:75-95` | Intentional pre-launch state: `APP_GALLERY_APP_ID = ""` → `hasListing == false` → no external link is shown. Documented `TODO` at `:72`. **Not a defect** | Left as-is (by design) |
| b3 | PremiumSheet "coming soon" copy | `ui/components/PremiumSheet.kt:69` | Same pre-launch intent as b2 | Left as-is (by design) |
| b4 | Web demo data | `web/src/pages/ActivityDashboard.tsx:57-129` (`DEMO_CONNECTIONS`, `DEMO_SUMMARY`, `DEMO_ACTIVITIES`, `DEMO_ALARMS`, `DEMO_REPORT`), `web/src/pages/ShareReport.tsx:46` ("Demo report data") | The `consistency-report` share endpoint has **no consumer**; the `/activity` and `/share/:token` routes render hardcoded demo values | Documented. Out of scope for a Kotlin build/push pass — no Kotlin change can fix it, and it needs the endpoint contract |

## Category (c) — Not implemented at all / dead code

| # | Item | Anchor | Verdict | Action |
|---|---|---|---|---|
| c1 | `ActivityInsightSheet` composable | `ui/components/ActivityInsightSheet.kt:54` (pre-removal) | **DEAD — zero callers.** Repo-wide search (all file types, excluding build dirs) returned only the declaration itself. The sibling `ActivityReportSheet` in the same file **IS** alive (`SettingsScreen.kt:1213`) | **Removed.** Its private helpers were deliberately **kept** — they are all used by the living `ActivityReportSheet` |
| c2 | `AppViewModel.setHabitTracking` | `ui/AppViewModel.kt:1782` (pre-removal) | **DEAD — zero callers.** It was the only writer of the user-facing `trackingMode` / `trackingTargetSeconds` / `trackingTargetCount` / `trackingUnit` override fields; with no caller, those fields are now write-only-by-nobody | **Removed** (plus its now-unused `habitLogsOn` import) |
| c3 | `AppViewModel.todayTrackingLogsFor` | `ui/AppViewModel.kt:1806` (pre-removal) | **DEAD — zero callers.** Duplicates the live `AppData.habitLogsOn` extension (`HabitTracking.kt:215`) that callers use directly | **Removed** |
| c4 | `AppViewModel.recentTrackingLogsFor` | `ui/AppViewModel.kt:1810` (pre-removal) | **DEAD — zero callers.** Call sites use `AppData.habitLogsFor(...).take(3)` directly (`AppNavigation.kt:283`, `HomeScreen.kt:228`) | **Removed** (plus its now-unused `habitLogsFor` import) |
| c5 | `MIN_TIMER_TARGET_SECONDS` | `data/Timers.kt:40` (pre-removal) | **DEAD — zero readers.** Its sibling `MAX_TIMER_TARGET_SECONDS` *is* used (`Timers.kt:166`), and no input path clamps against the minimum | **Removed** |
| c6 | `Huawei Health Kit` | `HUAWEI_HEALTH_KIT_SETUP.md` | **Still not implemented** — zero Health Kit code in the source tree; the doc describes a feature that does not exist | Documented (e4). Not built — would require new dependencies and OAuth, outside the stated constraints |
| c7 | `presetTimes` write-only field | `data/Models.kt:188`; `ui/AppViewModel.kt:2044` | **Half-wired, not dead** — see b1. The field is read by nothing; `setPresetTime` has no caller | Kept (documented as debt) |
| c8 | `ActivityMonitor.captureBlocking` | `integrations/ActivityMonitor.kt:130` | **ALIVE** — callers at `ActivityMonitor.kt:113,317` and `HealthConnectClient.kt:134`. The earlier "0 callers" claim was **wrong** | Kept |
| c9 | `requestHealthConnectPermissions` / `connectPolar` / `syncStravaActivities` / `ActivitySourcePickerSheet` / `PlanSlotPlaceholder` | `AppViewModel.kt:770,407,473`; `ActivitySourcePicker.kt:213`; `PlanCards.kt:300` | **ALL ALIVE** with live callers (`HabitsScreen.kt:429,440,469,483`; `SettingsScreen.kt:465,497`; `PremiumSheet.kt:464`) | Kept |
| c10 | `ActivityReportSheet`, `ExpressionSpec`, `PetSpec`, `MoodTheme`, `MoodMotion`, `HeatDay`, `HeatmapDay`, `AppTypography`, `HUAWEI_RED`, `isSportActivityIcon`, `Particle`, `GRAVITY`, `streakMilestones`, `LOW_BATTERY_THRESHOLD`, `WEEK_COUNT`, `ActivitySourceOption`, `STRAVA_TRACKABLE_IDS` | various | **ALL ALIVE** — each has a real second reference in its own file (a `private` helper used by the file's public composable, or a constant read by one). Removing them would break their file | Kept — verified before sparing |

### Correction to an earlier audit
A previous run reported **"`ActivityInsightSheet` and `ActivityReportSheet` are alive."** That was **half wrong**: `ActivityReportSheet` is alive (`SettingsScreen.kt:1213`), but `ActivityInsightSheet` is **dead** — no caller in any file type. This audit corrects it (see c1).

## Category (d) — Configuration items that work

| # | Item | Evidence |
|---|---|---|
| d1 | Manifest permissions all backed by real code | `POST_NOTIFICATIONS` (`MainActivity.kt:306-312`), `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` (`AlarmScheduler.kt:105,223`), `USE_FULL_SCREEN_INTENT` (`AlarmRingService.kt:349`, `AlarmRingingActivity.kt:177`), `PACKAGE_USAGE_STATS` (`ScreenTimeMonitor.kt:21`), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`AlarmPermissionPrompt.kt:805`), `VIBRATE`, `RECEIVE_BOOT_COMPLETED`, Health Connect `READ_*` |
| d2 | Package-visibility `<queries>` correct for Android 11+ | `AndroidManifest.xml:97-121` — HMS Core, AppGallery, Huawei Health, Health Connect package + rationale intent |
| d3 | `com.huawei.hms.client.appid` declared | Present in manifest; derived from `agconnect-services.json` via `manifestPlaceholders` so it cannot drift |
| d4 | No orphaned permissions | `ACTIVITY_RECOGNITION` is **not** declared (no Health Connect record-level permission needed — confirmed by search); no declared permission lacks a consumer |
| d5 | Gradle / R8 config sane | `app/build.gradle.kts`; `proguard-rules.pro` present (79 lines — serialization, HMS, IAP, Compose) |
| d6 | No test source sets (informational) | `android/app/src/{test,androidTest}` absent → "tests pass" has nothing to run; CI's `assembleRelease` is the only available signal |

## Category (e) — Broken or unverified configuration

| # | Item | Anchor | Status |
|---|---|---|---|
| e1 | Local Gradle build | `android/gradle.properties` tuned for 8 GB vs a **2 GiB** cgroup ceiling (`memory.max` = 2147483648) | **Environment-blocked** — OOM-kills the Kotlin daemon. CI is the real gate |
| e2 | i18n leaks — hardcoded English literals | ~104 `Text("…")` literals bypassing `AppStrings`; worst: `SettingsScreen.kt` (28), `HabitsScreen.kt` (14), `TimerScreen.kt` (11), `AlarmPermissionPrompt.kt` (6), `ScreenTimeHabitSheet.kt` (7) | **Real, documented.** A user on a non-English locale still sees English on these screens. Fixing all ~104 requires adding the same number of keys to 26 language files — a large, mechanical, untestable-here change, so it is reported rather than half-done |
| e3 | Web demo data | `ActivityDashboard.tsx:57-129`, `ShareReport.tsx:46` | **Real, documented** (see b4) — needs the `consistency-report` contract, no Kotlin fix |
| e4 | Huawei Health Kit doc overstates | `HUAWEI_HEALTH_KIT_SETUP.md` | **Real, documented** (see c6) — zero Health Kit code exists |
| e5 | Runtime behaviour on a real device | — | **Unverified** — see the "Cannot be tested here" section |

---

## Verification method for the removals

Before deleting `ActivityInsightSheet`, a repo-wide search across **every** file type (excluding `.git`, `build`, `node_modules`) was re-run and returned exactly one hit — its own declaration. Its private helpers were removed only when the post-delete search confirmed nothing else referenced them.
