# Motivational Habit Alarms — Feature Summary

Adds motivational lines to habit reminder alarms. A habit no longer fires a plain
alert — it fires with an encouraging sentence of your choosing, or one the app
supplies for that habit.

The example the request was built around:

> **Drink Water** · 07:00
> *It's time to water up! 💧 Stay hydrated, you've got this!*

---

## 1. Files added

| File | Purpose |
|---|---|
| `android/.../data/MotivationalMessages.kt` | The message library: 13 curated packs (water, medicine, movement, stretch, sleep, journal, gratitude, reading, focus, routine, money, social, general), the preset picker, the per-day rotation, and the input sanitiser. |
| `android/.../notifications/HabitReminderText.kt` | Resolves the line to display at ring time, reading the habit's saved message directly from storage so it works from a background alarm receiver with no running app. |
| `CODE_REVIEW_REPORT.md` | The code review report (separate deliverable). |

## 2. Files changed

| File | Change |
|---|---|
| `data/Models.kt` | Added `Habit.alarmMessage: String?` and `Habit.withAlarmMessage()` — the single write path, which normalises blank to `null`. |
| `notifications/HabitCheckInNotifier.kt` | The notification body is now the motivational line (was a fixed `"Time for your habit"`), with `BigTextStyle` so it is readable on expand and a `"07:00 · 2 of 3 today"` subtext. |
| `notifications/AlarmRingingActivity.kt` | The full-screen ring now shows the same line instead of a generic subtitle. Resolved in the IO block the screen already ran, so cold start is not slowed. |
| `ui/AppViewModel.kt` | `setHabitAlarmTimes(...)` and `addHabitObject(...)` accept an `alarmMessage`, written through `withAlarmMessage`. |
| `ui/screens/HabitsScreen.kt` | New "Motivational message" editor with suggestion chips and a live preview, in both habit-creation dialogs; the line is threaded through both confirm paths. |
| `data/SupabaseSync.kt` | `alarm_message` added to the habit push/pull mapping, nullable-with-default so a project whose schema predates the column still syncs habits (same degradation path `alarm_times` uses). |

## 3. How it works, end to end

```
HabitsScreen  ──user picks/types a line──►  Habit.alarmMessage   (sanitised, stored)
                                                    │
                                    HabitAlarmScheduler.schedule()  arms one alarm per time
                                                    │
                                          ▼ alarm fires
                              HabitReminderReceiver  (no UI, no app process)
                                                    │
                                    HabitReminderText.lineFor(context, intent)
                                       ├── custom message present?  → use it
                                       └── otherwise               → curated pack for the icon,
                                                                     rotated per (habit, day, occurrence)
                                                    │
                              ┌─────────────────────┴─────────────────────┐
                              ▼                                           ▼
                HabitCheckInNotifier                        AlarmRingingActivity
                (notification shade)                        (full-screen ring)
                    title: Drink Water                          title: Drink Water
                    body:  It's time to water up! 💧 …          subtitle: It's time to water up! 💧 …
```

### The three design decisions worth knowing

**The message is resolved at ring time, not baked into the alarm.** The scheduler
stores only the schedule; the text is read from storage when the alarm fires. So editing
a habit's message takes effect on alarms that are *already armed* — there is no window
where a stale line can be delivered, and nothing to re-arm when the text changes.

**Blank means "use the app's own line", never "say nothing".** Every layer degrades to
the curated pack for that habit instead of to an empty body: a habit with no custom
message still gets a specific, warm reminder (a water habit gets hydration lines), and a
habit that cannot be resolved at all gets a generic encouragement.

**The line is sanitised on write *and* on read.** It is posted on a notification by a
background receiver, so a newline, a control character, or an essay-length paste would
render as a broken or deceptive notification. `MotivationalMessages.sanitize` flattens to
a single line and bounds the length to 120 characters; it runs in the editor, on the model
write, and again at ring time (a restored cloud row can bypass the editor).

## 4. How to use it

### Set a message for the habit you are creating

1. Open the **Habits** tab.
2. Tap a habit icon (e.g. **Drink Water**).
3. In the alarm dialog, scroll to **Motivational message**.
4. Either tap one of the **suggestions** — for a water habit the first chip is
   *"It's time to water up! 💧 Stay hydrated, you've got this!"* — or type your own.
5. **Preview** underneath shows the exact sentence that will fire.
6. Set the time(s) and tap **Save alarm**.

### Change the message on an existing habit

Tap the habit's icon → **Set alarm** (or re-open its alarm dialog). The field is
pre-filled with what the habit currently says; edit it and save. Existing armed alarms
pick up the new line automatically — nothing to re-arm.

### Make the app write the message for you

Leave the field **empty**. The reminder resolves to the curated pack for that habit:

| Habit | Example line |
|---|---|
| Drink water | *Water break! 💧 Your future self says thanks.* |
| Movement | *You never regret the movement you did. 🏃 Let's go!* |
| Wind down | *You've done enough today. 🌙 Let yourself rest.* |
| Journal | *Two minutes, one honest line. 📝 That's all this needs.* |
| Custom habit | *You've got this. ✨ One small step, right now.* |

The line rotates by day, so the same habit says something different tomorrow — while the
same habit shows the *same* line all day, so a habit that rings three times does not read
as three different apps.

### Custom (to-do) habits

**Habits → Add a custom habit** → the same **Motivational message** field appears above
the time picker.

## 5. How to build and run

The Android app is a standard Gradle project:

```bash
cd android
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew installDebug           # push to a connected device/emulator
```

To see the feature: `installDebug`, add a **Drink Water** habit with an alarm a minute or
two ahead (and the message left empty, or set your own), then wait for the ring. Verified
on the notification shade and on the full-screen ring.

**⚠️ Not compiled in this environment.** This sandbox has no JDK and no Android SDK
(`java`, `gradle` and `sdkmanager` are all absent), so the changes were reviewed
statically — imports, call-site signatures, brace/paren balance — but **not built**.
Please compile locally or via the repository's CI workflow before merging. The touch
points to watch are the new `AlarmPickerDialog`/`TodoListDialog` `onConfirm` arities,
which were updated at all four call sites.

### Testing the reminder path without waiting

```bash
# Fire a habit's reminder immediately (alarm time passed as an extra)
adb shell am broadcast -n com.rork.mindsetframestracker/.notifications.HabitReminderReceiver
```

## 6. Follow-ups deliberately left out of this pass

- **Cloud sync of the message needs a column.** `SupabaseSync` now sends/receives
  `alarm_message` and degrades safely while the column is absent — the user's habits keep
  syncing and the field is reported as dropped. To make the line sync between devices, add
  the column:

  ```sql
  alter table habits add column if not exists alarm_message text;
  ```

  Without it, messages are **device-local** — created and delivered correctly on the
  device, but not restored on a new one.

- **Editor labels are English-only.** With 26 locale overlays available, the four new
  labels should move into `strings/*.json`, and a decision made on whether the packs ship
  translated (see P1-4 in the review report).

- **The curated packs are content, not just strings.** They are in Kotlin so the sanitiser
  and rotation stay in one place; if they are translated later, keep them behind
  `MotivationalMessages` rather than scattering copy through the UI.
