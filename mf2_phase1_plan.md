# Mf2 Phase 1 Plan

Phase 1 analysis output for the Mf2 Android app, base commit `358b759` on `main`.

Two changes, one release:

- **Change A** — free-tier habit limit: **5 active habits**, archived habits
  excluded, paused habits counted. Blocked at creation with an upgrade prompt and
  an "x of 5" indicator. Habits that already exist are **never** deleted, locked
  or hidden when the cap is hit or when a subscription lapses.
- **Change B** — alarm auto-reset: schedule the next occurrence immediately in
  the receiver, raise a habit dialog with **Done / Snooze / Skip** via a
  full-screen intent, re-arm on boot / app update / time change / timezone
  change, handle exact-alarm and full-screen-intent permission fallbacks,
  keep occurrence handling idempotent, skip the dialog when the habit is already
  done, and log missed alarms.

## Status of this branch

Branch `feat/mf2-phase1-ui` carries the **UI layer only**. The remaining work is
scheduled as follows and is *not* in this commit.

| Area | Owner | State |
| --- | --- | --- |
| UI layer (alarm dialog, "x of 5" indicator, limit-reached state) | UI Designer | **implemented here** |
| Security review of the pushed changes | Application Security Engineer | pending |
| Platform fixes (re-arm on time/timezone change, permission fallbacks, idempotency) | Mobile App Builder | pending |
| Model layer (`isArchived` / `isActive`, `Entitlements.canAddHabit`, receiver re-arms first, MISSED persistence) + build verification | Senior Developer | pending |

The finish condition for the phase is the palette-consistent, security-reviewed
implementation on `main` with the build passing.

## What the UI layer delivers

All colour comes from `ui/theme/Palette.kt` (`Mf2Palette`) — no inline `Color(0x…)`
literals on any surface added here.

### Change B — the lock-screen habit dialog

- `ui/components/AlarmHabitLockDialog.kt` — the dialog itself, and the
  `AlarmHabitLockAction` enum (`DONE`, `SNOOZE`, `SKIP`).
  - **Done** is the filled primary action in the palette's accent pair; **Snooze**
    is the hairline-outlined variant; **Skip** is deliberately the quietest of the
    three but always present, so a user can say they did *not* do the habit
    instead of their history filling with completions that never happened.
  - Every action is at least 56dp tall — this screen is answered by a half-awake
    thumb on a locked phone, so the WCAG 2.5.5 44dp floor is lifted.
  - Headings use the ivory heading token (12.2:1 → AAA); body copy uses the muted
    token (5.4:1–7.7:1 → AA).
- `notifications/HabitAlarmDialogActivity.kt` — the full-screen-intent host.
  `showWhenLocked` + `turnScreenOn` at API 27+, and the deprecated window-flag
  path below it. It resolves the habit's own artwork, motivating line and
  done-today state in **one** IO pass after the first frame, so the alarm's cold
  start gains no blocking work.
- `notifications/AlarmRingActionReceiver.kt` — the single place the three answers
  become state. Done writes the occurrence record and today's check-in through
  `HabitAlarmRecords` (idempotent per `(habit, day, alarm time)`); Snooze
  delegates to the existing `HabitSnoozeReceiver` so there is one snooze
  implementation in the app; Skip records a dismissal and writes no result.
  Done and Skip re-arm the next occurrence.
- `notifications/HabitCheckInNotifier.kt` — the habit full-screen intent now
  targets the dialog activity, and targets `AlarmRingingActivity` instead when
  today's check-in is already complete (the plan's "skip the dialog if the habit
  is already done"), decided before the intent is attached so the device is never
  woken for a question with no answer. When `canUseFullScreenIntent()` is not
  granted, no full-screen intent is attached and the ring still sounds through
  `AlarmRingService` — unchanged.
- `AndroidManifest.xml` — the activity and receiver, both `exported="false"`.

### Change A — the "x of 5" indicator and the limit-reached state

- `ui/components/HabitLimitState.kt` — `habitLimitCaption()` (the one phrasing of
  "x of 5 active habits", so the header and the limit prompt cannot disagree),
  `ActiveHabitIndicator()` (the pill in the Habits header) and
  `LimitReachedPaywallState()` (the full prompt raised on a blocked creation).
- The limit prompt **names the habit** when it is named, shows the same counter
  the header showed, and states the no-data-loss promise in the same dialog as
  the ask — the moment a user is most likely to fear for the habits they already
  have.
- `ui/screens/HabitsScreen.kt` — the header counter is now `ActiveHabitIndicator`,
  and a blocked creation opens the limit state (which can open the existing
  `PremiumSheet`) instead of a transient snackbar. The bulk screen-time path and
  the add-habit dialog keep their existing `canAddHabit()` gate.

## Still open, by owner

**Application Security Engineer**

- Permission abuse: the new activity takes over the lock screen and wakes the
  display; confirm the surface cannot be triggered without a real alarm.
- Exported components: confirm `HabitAlarmDialogActivity` and
  `AlarmRingActionReceiver` staying `exported="false"` is sufficient, and that
  the new action constants cannot be spoofed.
- PendingIntent mutability: the full-screen intent is `FLAG_IMMUTABLE`; confirm
  the same holds for the ring-action dispatch and the notification identity.
- Client-side entitlement bypass: `canAddHabit()` is a client check only —
  assess what a modified client can do to the cap.
- Data loss on downgrade: confirm no path deletes, archives or locks an existing
  habit when the cap is reached or a subscription lapses.

**Mobile App Builder**

- Re-arm on `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` (and boot/update,
  which exist).
- Exact-alarm and full-screen-intent permission fallbacks.
- Idempotent occurrence handling in the receiver path.

**Senior Developer**

- Model layer: `isArchived` / `isActive`, `Entitlements.canAddHabit`.
- The receiver re-arms before anything else it does.
- MISSED persistence, and build verification.

Until `isArchived` exists, `HabitsScreen` counts every stored habit as active —
deliberately the same set `canAddHabit()` counts, so the indicator can never
report a different number from the one the limit enforces.

## Build verification

`gradle`/`kotlinc` and an Android SDK are not available in the sandbox this
branch was prepared in, so the build was **not** run here. Verification is the
Senior Developer's step in the sequence above. Changes were limited to files that
reference only already-existing APIs (`HabitAlarmRecords.recordOccurrence`,
`HabitAlarmHistory.markOutcome`, `HabitAlarmScheduler.scheduleNext`,
`MindsetRepository.load()`, `isHabitDoneOn`, `AlarmRingService`,
`HabitCheckInNotifier.notificationId`) plus the new components in this commit.
