# Alarm flow: personal dialog with a conditional connect-fitness offer

## What was asked

> Remove the "connect fitness tracker" step from the alarm flow. Instead, when the
> alarm goes off, show the user a dialog — like fitness apps do for a walk habit —
> offering to connect Strava, Google Health, etc., so activity is captured and
> recorded inside the app. If the habit does not involve a fitness app, do NOT show
> the connect option — just the alarm. Also add personal dialogs based on each
> habit's tools.

## What changed

### The connect step is gone from the flow, not moved within it

`HabitTimerOptionsHost` (the host the alarm hands the user to) used to render
`HabitTrackingSheet` with an `onOpenTracker` callback, and layer
`HabitTrackerConnectHost` above it when that callback fired. That made connecting
a **step in a sequence**: ring → dismiss → find the tracker row → connect.

It now renders `AlarmActionDialog`, a single dialog raised by the ring itself, in
which the offer sits in the same card as the habit's own tool. There is no step to
get through and nothing to go looking for.

### The offer is conditional, and its absence is structural

`AlarmRingGate.shouldOfferConnect(sources, statuses, alreadyAsked)` is the single
decision, and it requires all three:

| condition | why |
|---|---|
| `sources` is non-empty | the habit has a fitness source at all — `TrackerConnections.sourcesFor(iconId)` |
| no source is connected yet | the activity is already being captured; nothing left to ask |
| not `alreadyAsked` | a one-time choice — re-asking is a nag |

When `shouldOfferConnect` is false, `RingConnectOffer` **early-returns**: no
heading, no body, no row. A non-fitness habit's alarm is the alarm and its own
tool. This is deliberate over rendering a disabled or explanatory block — a
greyed-out Strava row on a meditation alarm is the same defect in different
clothes.

Connected-ness is read only for **this habit's** sources. A naive
"is anything connected?" check would silently remove the offer from every
subsequent habit the moment the user connected anything once.

### Personal copy, driven by the habit's own tool

`AlarmDialogs.forHabit(...)` returns `AlarmDialogCopy(title, subtitle,
connectHeading, connectBody)` as a pure function of `(habit name, icon, mode,
unit, source names)`. Every mode gets its own heading and its own wording:

- CHECK → "Time for your medication" / "Nothing to measure. Tap done and it's on the board."
- COUNT → "Log how many glasses you've done so far."
- TIMER → "Set the length and it rings when you're done."
- STOPWATCH → "No target to set: start it when you head out, stop it when you're back."
- JOURNAL → "Time to write" / "Get the words down while they're fresh."

Trackable habits additionally get the offer line, e.g.
*"Connect Strava or Google Health Connect and this walk records itself — no timer
to remember."* Non-trackable habits get a blank `connectHeading`/`connectBody`,
so the copy itself cannot promise an automatic record that nothing can deliver.

Prose uses the habit's own noun ("walk", not "Walking", not the raw
`strava_weight_training` id), falling back to the user's typed name.

### Not offered more than once, per habit

`AlarmConnectPrompt` persists the set of habits already offered. It is **per
habit** because connecting is only meaningful relative to a habit — declining the
offer on a walk says nothing about a gym habit, and a single global flag would
suppress the offer on every habit after the first. It records "asked", not
"connected": a user who tapped "Not now" has answered, and connecting is itself an
answer. Written in a `LaunchedEffect` so the ring path never writes to disk on a
mere recomposition.

### The habit's own tool, shared rather than duplicated

`CheckInput` / `TimerInput` / `StopwatchInput` / `JournalInput` / `CountInput`
were `private` inside `HabitTrackingSheet`. They are now `internal` in
`HabitToolInputs.kt` and used by **both** surfaces, so "log how many glasses"
cannot come to mean one thing on the ring and another in the sheet. The sheet's
private copies were deleted; because the shared declarations are in the same
package and keep their names, its call sites resolve unchanged.

### Activity is captured and recorded inside the app

After a successful connect from the ring dialog, `onActivityCaptured` fires once
(keyed on the connected-set, not on every recomposition) and calls the matching
`AppViewModel.sync*` for each newly-connected provider, attributing the activity
to `pending.habitId`. This mirrors the branch the habits screen uses on resume, so
both paths import identically.

## Files

| file | change |
|---|---|
| `data/AlarmRingGate.kt` | new — the pure offer decision + connected-source set |
| `data/AlarmConnectPrompt.kt` | new — persisted per-habit "already offered" record |
| `data/AlarmDialogCopy.kt` | new — pure per-habit/per-mode dialog copy |
| `ui/components/AlarmActionDialog.kt` | new — the dialog the ring raises |
| `ui/components/AlarmConnectOffer.kt` | new — the conditional connect block |
| `ui/components/HabitToolInputs.kt` | new — the five inputs, hoisted from the sheet |
| `ui/components/HabitTrackingSheet.kt` | private inputs + helpers removed (moved) |
| `ui/navigation/AppNavigation.kt` | ring host renders `AlarmActionDialog`; gating wired |
| `test/.../AlarmDialogPolicyTest.kt` | new — JVM tests over the two pure decisions |

## Verification

```
cd android
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

`AlarmDialogPolicyTest` asserts the three user-visible claims of the request:

1. a habit no fitness app can supply is never offered a connection, and its copy
   carries no connect wording for any mode;
2. a fitness habit with nothing connected is offered, a connected source
   suppresses it, an **unrelated** connection does not, and an already-asked
   habit is not asked again;
3. each mode produces its own heading, a walk is addressed as a walk and names
   its real trackers, a count habit talks in its own unit, and a Strava-derived
   icon is named in prose rather than by its raw id.

Plus: `HabitTrackerConnectPlacementTest` is unchanged and still passes — it pins
that the global "Connect fitness trackers" control never returns to the habits
screen header.
