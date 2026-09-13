# Alarm / Timer Review & Fixes — Mindset Frames (`mf2_36.zip`)

**Project:** `Mf2-main` — Android app, Kotlin + Jetpack Compose (Material 3), single-module `android/app`, `minSdk 26 / targetSdk 36`, Kotlin 2.3.10, Compose BOM, Huawei HMS (Account Kit, IAP, App Update), Health Connect, WorkManager, Ktor, Coil, kotlinx.serialization.
**Scope:** audit of the alarm/scheduling subsystem, best-execution decision for Android, and a new walk timer / stopwatch with a strictly once-per-event completion popup.

---

## 1. What was already there

The app had a real alarm subsystem, not a toy one:

| Component | Role |
|---|---|
| `notifications/NotificationScheduler.kt` | Daily check-in reminder, streak alert, evening reflection |
| `notifications/HabitAlarmScheduler.kt` | Per-habit reminders at `reminderMinutes`, honouring `repeatDaysMask` |
| `notifications/HabitReminderReceiver.kt` | Fires the reminder notification, re-arms for the next day |
| `notifications/HabitSnoozeReceiver.kt` | "Snooze 5 min" on the reminder |
| `notifications/CheckInReceiver.kt` | Daily/streak/evening notifications |
| `notifications/BootReceiver.kt` | Re-arms everything after reboot |
| `notifications/AlarmRingingActivity.kt` | Full-screen ringing screen |
| `notifications/Alarmdiagnostics.kt` | OEM battery-killer detection |
| `ui/screens/AlarmPermissionPrompt.kt` | Exact-alarm + notification permission UI |
| Manifest | `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `USE_FULL_SCREEN_INTENT` |

So the platform permissions were already well covered. The defects were in **how** the alarms were armed.

---

## 2. Concrete issues found

### BUG 1 — A revoked exact-alarm grant silently deleted the alarm
`HabitAlarmScheduler`, `NotificationScheduler` and `HabitSnoozeReceiver` each had their own copy of:

```kotlin
val canUseExact = Build.VERSION.SDK_INT < S || alarmManager.canScheduleExactAlarms()
runCatching { alarmManager.setAlarmClock(...) }   // or setExactAndAllowWhileIdle(...)
    .onFailure { Log.w(TAG, "...") }
```

`canScheduleExactAlarms()` returns the state at the moment it is read. If the user turned off **Alarms & reminders** *after* the value was read (or between scheduling passes), `setAlarmClock` throws `SecurityException` on API 31+ — and the surrounding `runCatching` swallowed it into one `Log.w` line. The alarm was then **never armed at all**, with every permission screen still looking green. This is the most plausible real-world cause of "the reminder just didn't ring".

### BUG 2 — No `allowWhileIdle` fallback: plain `setExact` is deferred in Doze
The non-exact fallbacks used `setWindow(...)` / bare `setExact(...)`. In Doze these are postponed to the next maintenance window — up to ~15 minutes of silence in precisely the situation that matters most (screen off, phone asleep, "walk at 20:45"). Walking-time deadlines and 5-minute snoozes need to fire, not eventually.

### BUG 3 — The status-bar alarm icon was dead
`HabitAlarmScheduler` passed the **broadcast** `PendingIntent` (targeting `HabitReminderReceiver`) as `AlarmClockInfo`'s show-intent. Android cannot start an Activity from a broadcast `PendingIntent`, so tapping the alarm-clock icon did nothing. `NotificationScheduler` went the other way and passed the broadcast intent to both slots — which also let a second app holding the same `PendingIntent` cancel our alarm (`setAlarmClock` pairs from two apps sharing one `PendingIntent` cancel each other).

### BUG 4 — `SCHEDULE_EXACT_ALARM` was being nagged about when it is irrelevant
The manifest declares `USE_EXACT_ALARM`, which for an alarm/reminder app is **auto-granted and cannot be revoked**. Flipping `SCHEDULE_EXACT_ALARM` off therefore does not stop exact alarms. The permission UI presented a Settings toggle that users could turn off believing they had disabled something, while `AlarmScheduler` would keep using exact alarms.

### BUG 5 — Reboot silently converted custom repeats into "daily"
`BootReceiver`'s JSON fallback reconstructed habits with `Habit(id, name, reminderMinutes)` and dropped `repeatDaysMask`, so a Mon/Wed/Fri reminder came back as daily on any device that took that path.

### GAP 6 — No `RECEIVE_BOOT_COMPLETED`-equivalent for force-stop
Nothing handled the case where an app is force-stopped: Android drops its pending alarms **without** sending `BOOT_COMPLETED`, so a `RTC_WAKEUP` alarm can be lost until the app is opened again. There was no cold-start reconciliation for it.

### GAP 7 — Battery/Doze advice was incomplete
`AlarmDiagnostics` covered OEM kill-lists and the battery-optimisation exemption, but not the App Standby bucket, which can defer alarms by hours on *stock* Android even with exact alarms granted.

### GAP 8 — No timer/stopwatch feature at all
The "walk timer" the brief asks about did not exist; there were only reminder **alarms** for habits, with a `durationSeconds` field on the model that nothing consumed.

---

## 3. Best execution approach on Android (what was chosen and why)

| Need | Chosen primitive | Why |
|---|---|---|
| Fire at an exact instant, screen off, Doze active | `AlarmManager.setAlarmClock()` when `canScheduleExactAlarms()` is true | The only user-visible, Doze-exempt, clock-icon-grade mechanism. Level with the app's "reminder" semantics. |
| Same, but the grant is missing | `setExactAndAllowWhileIdle()` → `setAndAllowWhileIdle()` → `setWindow(…, 15 min)` | **Degrade precision, never delete the alarm.** Every rung still fires without a permission. |
| Never | plain `setExact` | Silently deferred for up to ~15 min inside Doze. |

Plus:
- **`WorkManager` is not used for the deadline.** Its minimum periodic interval and its batching make it wrong for "ring at 20 minutes exactly"; it is already in the project for sync work and stays there.
- **A foreground service mirrors the alarm** and is the safety net if an OEM task-killer force-stops the app and takes the alarm with it. Whichever path notices first wins; the completion gate makes the race harmless.
- **Elapsed time is derived from the wall clock**, never from a ticking counter, so a killed process cannot corrupt the timer.

---

## 4. What was changed

### 4a. New shared alarm core (fixes BUG 1, 2, 3)

**`notifications/AlarmScheduler.kt`** — the single, corrected AlarmManager entry point every alarm now routes through: `canScheduleExact()` re-checked at the call site, the full fallback ladder above, a real **Activity** show-intent namespaced by request code (`SHOW_REQUEST_CODE_OFFSET`) so it can never alias the firing intent, `cancel()` that also calls `PendingIntent.cancel()`, `broadcastIntent(createIfMissing = false)` so cancel paths never fabricate a PendingIntent, `hasAutoGrantedExactAlarm()` (fixes BUG 4), and `standbyBucket()` (fixes GAP 7).

**Refactored onto it:** `NotificationScheduler.kt`, `HabitAlarmScheduler.kt`, `HabitSnoozeReceiver.kt`.
**`BootReceiver.kt`** — now carries `repeatDaysMask` through the JSON fallback (fixes BUG 5).

### 4b. New walk timer / stopwatch (fixes GAP 8)

| File | Purpose |
|---|---|
| `data/Timers.kt` | `ActiveTimer`, `TimerKind` (`WALK_TIMER` / `STOPWATCH`), `TimerStatus`, `TimerCompletionEvent`, `TimerSplit`, duration formatting. Elapsed time derived from `elapsedSeconds + (now − startedAtEpochMs)`. |
| `data/TimerRepository.kt` | Own SharedPreferences file (`mindset_timers`) so a timer write can never corrupt the habit blob. Holds the active run, the **pending event**, and the append-only **handled-event ledger** — the durable once-only guard. |
| `notifications/TimerNotifier.kt` | Alarm-grade completion channel (`USAGE_ALARM`, so it cuts through silent/DND), full-screen intent, plus one-shot quiet follow-up. |
| `notifications/TimerService.kt` | Foreground service, `specialUse` type, live ongoing notification with the countdown; in-process safety net. |
| `notifications/TimerCompletion.kt` | **The single funnel** for every completion, with the atomic one-time gate; also the cold-start reconcile (fixes GAP 6). |
| `notifications/TimerAlarmScheduler.kt` | Arms/cancels the one timer alarm. |
| `notifications/TimerAlarmReceiver.kt` | Fires on the exact deadline. |
| `notifications/TimerReminderReceiver.kt` | One-shot, once-only follow-up nudge. |
| `notifications/TimerController.kt` | Every user mutation (start/pause/resume/+5 min/lap/stop/acknowledge) keeps state + alarm + service in lockstep. |
| `ui/screens/TimerScreen.kt` | The walk timer + stopwatch UI, `TimerCompletionPopup`, `ActiveTimerStrip`, `TimerEntryCard`. |
| `ui/navigation/NavRequests.kt` | One-shot deep-link channel for cold-start notification taps. |

**Modified:** `AndroidManifest.xml` (permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`; registers `TimerService`, `TimerAlarmReceiver`, `TimerReminderReceiver`), `ui/navigation/AppNavigation.kt` (route `"timer"`, `TimerCompletionHost`, deep-link handling), `MainActivity.kt` (`handleTimerIntent`, `EXTRA_OPEN_ROUTE`/`EXTRA_STOP_TIMER`, `ROUTE_TIMER`), `MindsetFramesApplication.kt` (cold-start reconcile), `ui/screens/HomeScreen.kt` (timer card under the header).

---

## 5. The one-time popup — how "exactly once" is guaranteed

Four independent layers, so no single failure can produce a second popup:

1. **Persisted event, not transient state.** A completion writes a `TimerCompletionEvent` to `TimerRepository`. The popup renders *that record*. Rotation, backgrounding, navigation or a process kill can neither lose it nor duplicate it.
2. **Atomic claim.** `TimerRepository.recordCompletion()` is the one door all entry points (alarm receiver, foreground service, in-app tick) go through. The first caller wins; everyone else is told `AlreadyRecorded` and posts nothing.
3. **Consume-on-show.** Showing the dialog calls `TimerController.acknowledgeEvent()`, which appends the event id to an append-only SharedPreferences ledger and clears the pending slot. The same event can never be shown again — in this process or any future one.
4. **Lifecycle-resilient host.** `TimerCompletionHost` sits **above the `NavHost`** (so it appears on any tab) and re-reads the pending record on `ON_RESUME`, which is how a completion that fired with the app in the background still surfaces — while the ledger stops the *next* resume from showing it again.

The event id is `"$runId:$kind:$targetSeconds"`, so extending a finished walk (+5 min) legitimately earns its own single popup later, and the earlier one can never repeat.

For the **stopwatch**, a target is optional: with a goal it alerts once at the goal and keeps counting (a stopwatch that stops itself is not a stopwatch); open-ended it never "completes".

---

## 6. How to test

### 6a. Alarm correctness
1. **Short timer, app foregrounded.** Timer → Walk timer → 10m preset is the shortest; to test in seconds you can temporarily set `MAX_TIMER_TARGET_SECONDS` aside and pick a second-scale target, or simply **pause/resume and use "+5 min" on a near-expired run**. Expect: the popup appears **once**, with the alarm sound.
2. **Backgrounded.** Start a 10-min walk, press Home, lock the screen. Expect: the ongoing notification counts down; at 00:00 the alarm-grade alert fires (audible even in silent/DND) and a full-screen ringing screen appears. Reopen the app → the popup is there **once**.
3. **Force-stopped** (`adb shell am force-stop <pkg>` mid-walk). Reopen. Expect: `MindsetFramesApplication` reconciles, the alarm is re-armed or the completion is recorded, and the popup shows exactly once.
4. **Reboot** (`adb reboot`) mid-walk. Expect: `BootReceiver` re-arms habit reminders; the timer is re-armed on next launch by the cold-start reconcile.
5. **Doze.** `adb shell dumpsys deviceidle force-idle` then let a timer expire. Expect it still rings (alarm-clock path) — this is the case plain `setExact` used to lose by up to 15 minutes.
6. **Permission revocation (the BUG 1 regression test).** Grant exact alarms, start a walk, then revoke **Alarms & reminders** in Settings and let it expire. Old behaviour: silence. New behaviour: the fallback ladder still fires, and precision only degrades.
7. **Snooze (BUG 1 adjacent).** Tap "Snooze 5 min" on a habit reminder with exact alarms revoked → it must still re-fire.
8. **Verify what got armed:** `adb shell dumpsys alarm | grep -A3 <pkg>` — look for `RTC_WAKEUP` entries at your deadline.
9. **OEM battery killers:** use the existing `AlarmDiagnostics` + `AlarmPermissionPrompt` screens and the `standbyBucket` reading.

### 6b. One-time popup
1. Start a walk timer and let it complete → popup appears.
2. **Rotate the device while the popup is up** → it must not re-appear after dismissal.
3. Dismiss → navigate Home → Habits → back to Timer → **no popup**.
4. Dismiss → background → foreground (`ON_RESUME`) → **no popup**.
5. Dismiss → `adb shell am force-stop <pkg>` → relaunch → **no popup**.
6. Complete a *second* timer → **popup appears again** (new event id — this is correct).
7. Let a timer complete with the app in the background → on return the popup appears **once**.
8. `adb shell run-as <pkg> cat shared_prefs/mindset_timers.xml` → the event id is in `handled_event_ids`, absent from `pending_event`.

---

## 7. Notes / follow-ups

- `TimerService` uses `foregroundServiceType="specialUse"` (there is no "timer" FGS type, and `shortService` is capped at 3 minutes, shorter than any preset). The manifest carries the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification required for store review.
- The timer writes to its own preferences file to avoid re-serialising the whole app blob on every tick and to keep the two persistence paths from being able to corrupt each other.
- Not done (out of scope, no code touched): migrating the *recurring habit reminders* to `AlarmManager.setAlarmClock` + `WorkManager` hybrid for OEM-killed devices, and adding a per-habit target-duration picker that pre-fills the walk timer. Both are natural next steps.
