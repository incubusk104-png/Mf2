# Habit alarm history

How per-occurrence habit alarm history works, and why it is keyed the way it is.

## The problem it solves

A habit set for 07:00 / 12:00 / 18:00 rings three times a day. Before this
feature the app had nowhere to record that: `HabitLogEntry` only exists when the
*user* supplied content (a duration, a count, a journal sentence), and
`HabitAlarmRecords` deliberately refuses to invent an entry for a habit whose
input only the user can give. The habit dialog could say "done today" but not
*which* of the three alarms fired, at what time, saying what — or that the 12:00
one was missed entirely.

## Keying: one record per occurrence

```kotlin
fun alarmEventKey(dayKey: String, scheduledMinutes: Int): String = "$dayKey@$scheduledMinutes"
```

An event's identity is `(habitId, dayKey, scheduledMinutes)`.

**Nothing may deduplicate by habit id, or by `(habitId, dayKey)`.** Either would
make a habit's three daily alarms indistinguishable from one — the exact collapse
this feature exists to prevent. `HabitAlarmHistory.record` therefore always
filters on the habit **and** the event key:

```kotlin
val others = data.alarmEvents.filterNot { it.habitId == habitId && it.eventKey == key }
```

That single rule gives all three behaviours the feature needs:

- a re-delivered ring (re-arm, duplicate broadcast) updates one row, not two;
- answering refines the event instead of duplicating it;
- a different scheduled time is a different key, so it always writes its own row.

## Outcomes

An alarm fires and is *then* answered — two facts about one occurrence. The
outcome is a field refined in place:

| outcome | meaning |
|---|---|
| `FIRED` | it rang; nothing recorded about the response yet |
| `ACKNOWLEDGED` | the user completed the habit from the ring |
| `DISMISSED` | the user stopped the alarm without completing it |
| `SNOOZED` | the user snoozed it; it will ring again |

`FIRED` never overwrites a real outcome: ring and answer can be delivered out of
order, and the answer is the more informative fact.

## Slots: the display state

The dialog renders **one row per scheduled time**, derived from the habit's
schedule rather than from the recorded events — so a time that never fired still
appears, as `MISSED` rather than silently absent. That is what lets the dialog
answer *"which of today's alarms went off, and which did I let pass"*.

| state | when |
|---|---|
| `PENDING` | scheduled later today; has not fired |
| `FIRED` | rang, no answer recorded |
| `ACKNOWLEDGED` / `DISMISSED` / `SNOOZED` | the recorded outcome |
| `MISSED` | its time passed that day with no event at all |

A time that fired but is no longer in the schedule is still listed, so editing a
habit does not erase an alarm that really rang that morning.

## Capture points

| entry point | records |
|---|---|
| `HabitCheckInNotifier` | `FIRED` — only once the notification is actually postable, past the permission and channel checks |
| `AlarmStopReceiver` | `DISMISSED` — the one path both the notification's "Stop alarm" button and the ringing screen's Stop route through |
| `HabitSnoozeReceiver` | `SNOOZED` |

A ring suppressed by a missing permission or a muted channel is deliberately not
recorded: it never reached the user.

`DISMISSED` rather than `ACKNOWLEDGED` on stop is deliberate. Stopping an alarm
says "I have seen this and it is no longer ringing", not "I did the habit".
Whether the habit was done stays owned by the tracking sheet and the timer, which
write their own log entries. Inferring a completion from a stop is the invented
record this codebase refuses to write elsewhere.

## Storage

Events live in `AppData.alarmEvents`, a separate list from `habitLogs` — what the
alarm did and what the user logged are different facts. The list defaults to
empty (so existing installs decode unchanged) and is capped at
`MAX_ALARM_EVENTS` (500, newest kept) so the persisted blob cannot grow
unbounded.

Messages are **snapshotted at ring time**, so a history view never shows a line
the alarm did not actually say after the user edits their message.

The cloud-restore merge deliberately does not take the cloud's `alarmEvents`, so
restoring never discards device-local history.

## Two bugs fixed on these paths

1. **Stopping one alarm cancelled all of them.** `AlarmRingingActivity` built its
   stop `Intent` with the plain constructor (no action), and
   `PendingIntent.FLAG_UPDATE_CURRENT` returns an *existing* PendingIntent when
   `(requestCode, Intent)` match one already registered. No-action filtered equal
   to the notification's **tap** intent under the same `habitId.hashCode()`
   request code, so the stop arrived with no `alarmMinutes` extra and fell
   through to "cancel every alarm this habit has". Answering the 07:00 alarm
   deleted the 12:00 and 18:00 ones. Fixed via `AlarmStopReceiver.makeExplicit()`.

2. **One time's snooze button replaced another's.** The notifier keyed its snooze
   PendingIntent on `habitId.hashCode()` alone, so with `FLAG_UPDATE_CURRENT` the
   three alarms of a multi-time habit shared one snooze action. Now keyed through
   `HabitAlarmScheduler.requestCodeFor(habitId, alarmMinutes)`.

## Strings

The 20 new labels are in `en.json` and in all 25 overlays (English fallback, so
nothing renders blank). They are not yet translated.
