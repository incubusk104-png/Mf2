# Mindset Frames — Code Review Report

**Repository:** `incubusk104-png/Mf2`
**Review date:** 2026-09-17
**Commit reviewed:** `main` (HEAD at clone time)
**Reviewer:** AI agent, static analysis
**Scope:** full repository — Android client, Supabase backend, React web app

---

## 1. Executive summary

Mf2 is a **mature, non-trivial app**, not a prototype. It is a Kotlin/Jetpack-Compose
Android habit tracker ("Mindset Frames") with roughly **58.5k lines** of first-party
code across three surfaces, a Supabase/Postgres backend with real migrations, and 26
localisation overlays. The alarm subsystem in particular is far more developed than a
typical habit app's — there are dedicated schedulers, ring services, snooze/stop
receivers, OEM battery-optimisation handling, and an existing `ALARM_REPORT.md`
documenting past fixes.

The code quality is genuinely good in the places that matter most: error handling on
network and platform boundaries is defensive and, crucially, **the reasons are written
down in the comments** — including the failures the team already hit. That is rare and
valuable.

The real problems are **architectural, not stylistic**:

1. **There is no local database.** All app state is one JSON blob in
   `SharedPreferences`, re-serialised on every write. `grep` finds no Room, no `@Entity`,
   no DAO. Every symptom in the P0/P1 findings below — whole-blob clobbering, ad-hoc
   `JSONObject` parsing inside `BroadcastReceiver`s, no schema, no migration story — is
   downstream of this one decision.
2. **Almost no automated tests, and none on Android.** Two test files exist, both in
   the web app. The alarm logic — the part with timezone maths, reboot handling, and
   OEM-specific quirks — is untested.
3. **A committed Huawei config file.** `android/app/agconnect-services.json` is in the
   repository.

None of these are unrecoverable. Items 1 and 3 are each a day or two of work; item 2 is
an ongoing discipline question, not a rewrite.

**Verification limitation, stated up front:** this sandbox has no JDK and no Android
SDK (`java`, `gradle` and `sdkmanager` are all absent), so **the changes in this PR were
not compiled**. All findings below are from reading the code, not from running it.
Anything marked *"verify"* is exactly that. The repository's own CI workflow is the
right place to confirm the build.

---

## 2. Tech stack and architecture

| Surface | Stack | Size |
|---|---|---|
| Android client | Kotlin, Jetpack Compose (Material 3), Coroutines/Flow, kotlinx.serialization | ~121 `.kt` files |
| Backend | Supabase — Postgres + Edge Functions (TypeScript) + RLS | `backend/`, `supabase/` |
| Web | React + Vite + TypeScript | `web/` |
| Build/CI | Gradle (Kotlin DSL), GitHub Actions | `.github/workflows/` |

### Module layout

```
android/app/src/main/java/com/rork/mindsetframestracker/
├── data/            models, repository, sync, content packs, integrations
├── notifications/   alarms, ring service/activity, receivers, notifiers
├── ui/              AppViewModel, navigation, screens/, components/, theme
└── (Application, MainActivity, DI wiring)
backend/functions/   Edge Functions
backend/supabase/migrations/    versioned SQL
web/src/             React app
```

### Data flow (as built)

```
UI (Compose)
  ↕ AppViewModel  ── state via Flow
MindsetRepository ── load()/save() ─→  SharedPreferences["mindset_frames"]["app_data"]
  ↕                                        (ONE JSON blob for all state)
SupabaseSync ── upsert/select ─→ Postgres
  ↑
Alarm receivers ── read the same SharedPreferences JSON directly
```

The last arrow is the important one: **alarm `BroadcastReceiver`s parse the application's
JSON blob by hand**, because they have no `ViewModel` and no injected repository. That is
a design smell with real consequences (§4, P0-1).

### Notable strengths

- **Defensive degradation on sync.** `SupabaseSync` distinguishes missing-table and
  dropped-column failures, names columns explicitly in its error summaries, and treats an
  absent `alarm_times` as "fall back to the legacy single time" rather than as data loss.
  It survives a client shipping ahead of its migration, which is the correct call.
- **Permissions are handled in one place.** `AlarmPermissions` centralises exact-alarm /
  notification / battery-optimisation checks instead of scattering them.
- **Idempotent habit creation.** `addHabitObject` refuses to add a duplicate id, so a
  double-tap cannot create two habits.
- **Comments explain *why*.** e.g. `HabitCheckInNotifier` documents that `splash_icon`
  must not be used as a notification small icon (a `<layer-list>` cannot inflate as a
  `BitmapDrawable`), and `BootReceiver` documents that the repeat mask must be carried
  across because defaulting it silently converted custom-day reminders to daily.
- **Accessibility touches** (`contentDescription` on icon-only buttons) are present in
  the newer dialogs.

---

## 3. Findings — P0 (data loss or core-flow breakage)

### P0-1 — Reboot fallback silently degrades every habit it re-arms

**Evidence:** `notifications/BootReceiver.kt:104–124`

After a reboot the primary path restores alarms from persisted state. If that fails, a
JSON fallback runs, and it reconstructs each habit from only three fields:

```kotlin
val habit = Habit(
    id = id,
    name = name,
    reminderMinutes = reminderMinutes,
    repeatDaysMask = repeatDaysMask,     // <-- fixed in a previous pass
)
HabitAlarmScheduler.schedule(context, habit)
```

It omits:

- **`alarmTimes`** — a habit with 07:00 / 12:00 / 18:00 re-arms as *one* alarm. Two
  reminders silently disappear.
- **`iconId`** — the habit rings and displays with generic artwork, and (with the feature
  in this PR) loses its curated motivational line pack.
- **`alarmMessage`** — the user's own motivational line is not delivered.

The comment above `repeatDaysMask` shows the team already found this exact bug once for
one field. It is still present for three others.

**Impact:** a rare path that, when it fires, quietly changes the user's schedule and
reminder content — the worst kind of bug, because nothing signals it happened.

**Fix:** pass `alarmTimes`, `iconId` and `alarmMessage` through the fallback the same way
`repeatDaysMask` now is, and add a `require`-style guard so a future field addition cannot
be forgotten. Better: delete the fallback entirely once alarms re-arm from a real
datastore (§5, P0-2).

---

## 4. Findings — P1 (correctness, security, or maintainability risk)

### P1-1 — All app state is one JSON blob in `SharedPreferences`; there is no database

**Evidence:** no `Room`, `@Entity`, or `@Dao` anywhere in the tree; persistence is
`SharedPreferences` key `app_data` holding the serialised `AppData`.

Consequences observed in the code:

- Every write re-serialises *all* state — habits, check-ins, moods, settings. Write cost
  grows with total history, on the main-adjacent path.
- There is no schema and no migration mechanism. Adding a field is safe only because
  kotlinx.serialization defaults missing keys; **removing or renaming one is not**, and
  there is no version marker to detect it.
- Multiple entry points write the same blob — the UI, and receivers reacting to alarms —
  with no transactional boundary. A write from an alarm callback racing a UI write is a
  lost-update window. *(verify: whether `MindsetRepository` holds a lock across
  load-modify-save; if it does, the window narrows but the unbounded blob write remains.)*

**Fix:** migrate hot, append-only collections (check-ins, activity records) to Room first
— that alone removes most of the write amplification and gives the alarm receivers a
queryable store. Habits/settings can follow.

### P1-2 — `android/app/agconnect-services.json` is committed

**Evidence:** the file is tracked at `android/app/agconnect-services.json`.

Huawei AppGallery Connect config files carry API keys and client secrets
(`client_secret`, `api_key`, `app_id`). A committed one is a credential in the repository
history, and the repo is public.

**Fix:** rotate the values in the AGC console, remove the file from tracking, add it to
`.gitignore`, and inject it at build time in CI (the `google-services.json` pattern).
Then **purge it from history** — rotation is what actually protects you; history rewriting
is what stops the next leak.

### P1-3 — No Android tests; the alarm logic is untested

**Evidence:** two test files exist, both under `web/src/test`. There is no
`androidTest` directory and no Kotlin unit test.

The untested surface is precisely the risky one: minute-from-midnight arithmetic, next-fire
computation across a DST boundary, repeat-mask day selection, snooze re-arming, and the
boot-restore path. These are pure functions — they are cheap to test, and P0-1 would have
been caught by a single round-trip test.

**Fix:** add JVM unit tests for `HabitAlarmScheduler`'s time computation and the
`Models.kt` helpers (`withAlarmTimes`, `withAlarmMessage`, `alarmMinutes`,
`formatAlarmTimes`). No emulator needed.

### P1-4 — Motivational copy added in this PR is English-only across 26 locales

**Evidence:** `android/app/src/main/assets/strings/` holds 26 overlays; the message packs
and the new editor labels were added as English literals in Kotlin.

The curated packs are *content* (worth translating); the editor's field labels are *UI*
(worth moving into the string table). Neither was done in this pass, which is a
deliberate scope decision — flagged here so it is a tracked gap rather than a surprise.

**Fix:** move the four editor labels into `strings/*.json`, and decide whether the packs
ship translated or fall back to English when a locale's overlay lacks them.

### P1-5 — `setHabitReminder` would silently clear a habit's message

**Evidence:** `ui/AppViewModel.kt` — the single-time convenience wrapper delegates to
`setHabitAlarmTimes`. With the new `alarmMessage` parameter defaulted to `null`, a caller
using it to *move an alarm time* would erase the user's motivational line as a side
effect.

**Status:** **fixed pre-emptively in this PR** by documenting the contract on the wrapper
instead of leaving a trap. The underlying shape (a nullable parameter where `null` means
"clear" rather than "unchanged") is a genuine design smell and is called out so a future
caller reads the warning.

---

## 5. Findings — P2 (quality, performance, UX)

### P2-1 — `AlarmRingingActivity` renders a timer ring with no habit identity

**Evidence:** the timer intents passed to the ringing screen carry no `habitId`/`habitName`,
so a completed timer's full-screen ring shows generic text and no habit artwork, even
though `TimerCompletion`/`TimerRepository` know which habit the timer belonged to.

**Impact:** the highest-attention screen in the app is the least informative one for
timers. **Fix:** put the habit id on the timer intent and resolve name/icon the way habit
alarms now do.

### P2-2 — Push and pull disagree on legacy schedules

**Evidence:** `SupabaseSync.kt` — the pull path treats an empty `alarm_times` as "the
legacy `reminder_minutes` is the schedule"; the push path maps `alarm_times = it.alarmMinutes`,
which for an old habit is a single-element list derived from the legacy field.

A user on an older build writing `reminder_minutes` only, then reading back on a newer
build, can see their intent reinterpreted. It degrades safely (no crash, no data loss in
the common case) but the two directions should share one normalisation function.

### P2-3 — Very large single files / composables

`ui/screens/HabitsScreen.kt` is ~1,100 lines and `ui/AppViewModel.kt` ~2,000. The habits
screen alone now hosts creation dialogs, the alarm editor, removal confirmation, the
screen-time flow, and premium/consent sheets.

**Impact:** every change to one flow risks the others (this review found and fixed two
call-site mismatches in exactly that file). **Fix:** extract the dialogs into
`ui/components/` — they are already nearly pure functions of their inputs, so the move is
mechanical.

### P2-4 — Ad-hoc JSON parsing duplicated across receivers

`HabitReminderText` (new, this PR) and `BootReceiver` both hand-walk
`SharedPreferences → JSONObject → habits[]` with their own field names as string literals.
A renamed serialized field breaks both silently. Until a real datastore exists, extract
one internal reader and have both call it.

### P2-5 — Volume of inline copy vs. string table

Many user-facing strings are Kotlin literals rather than `strings/*.json` entries (the new
editor labels included). With 26 locales available, this is a localisation debt that grows
with every feature.

---

## 6. Findings — P3 (nits)

- `P3-1` `MotivationalMessages.lineFor`'s seed mixes `hashCode()` across types; it is
  stable per (habit, day, occurrence) as documented, but a comment noting that
  `hashCode()` of a `String` is contractually stable in Kotlin/JVM (unlike identity hash)
  would save a future reader the worry.
- `P3-2` `formatAlarmTime` is private to the habits screen while `HabitReminderText`
  offers a 24-hour `formatTime`. Two formatters, two conventions, one app — worth merging.
- `P3-3` Only one `TODO` in the whole tree. Genuinely impressive; keep it that way rather
  than letting the count drift up.

---

## 7. Security review

| Area | Assessment |
|---|---|
| Auth / RLS | Supabase is used with `upsert(..., onConflict="id")` and explicit `uid` scoping in deletes. No client-side service-role key was found. **Verify RLS is enabled on every table** — that is a console check, not a code check. |
| Secrets in repo | **`agconnect-services.json` committed (P1-2).** Otherwise clean: no `eyJ…` JWTs, no `service_role`, no keystores. |
| Credentials at rest | Tokens (Strava/Polar/Huawei) are stored via the app's own storage; **verify** they go through `EncryptedSharedPreferences`/Keystore rather than plaintext. |
| Injection | No raw SQL string concatenation observed; queries go through the Supabase client builder. |
| Notification content | The motivational line is user-supplied text posted by a background receiver. Handled in this PR: sanitised on write **and** on read, control characters stripped, single line, length-bounded — a newline or control character in a notification title is a known way to make a notification render deceptively. |
| Network transport | HTTPS throughout; no cleartext exceptions observed. |

---

## 8. Performance

- **Write amplification (P1-1)** is the dominant issue: the full state blob is rewritten
  for a single check-in, and history is unbounded.
- **Alarm cold start:** receivers construct a repository and parse JSON on the alarm path.
  The feature added here avoids adding to it — the ringing screen resolves its line inside
  the IO block it *already* ran for habit artwork, and the notification reads only the two
  fields it needs.
- **Compose recomposition:** the ringing screen's resolved-icon state and the new message
  preview are local `remember`ed state, so editing the message text does not recompose the
  surrounding dialogs.

---

## 9. UX

- The habit-creation flow lets the user skip the alarm entirely, which is right — not
  every habit needs a reminder.
- Destructive habit removal is behind a confirmation dialog, with an explanatory comment
  about a stray-tap incident. Good.
- **Gap:** no way to preview what a reminder will actually look or read like before saving.
  The feature in this PR closes part of this by showing a live preview of the exact
  sentence that will fire.
- **Gap (P2-1):** the timer ring screen does not identify its habit.

---

## 10. Prioritised fix roadmap

| Priority | Item | Effort |
|---|---|---|
| **P0** | Fix `BootReceiver` JSON fallback to carry `alarmTimes`/`iconId`/`alarmMessage` (P0-1) | hours |
| **P1** | Rotate + untrack + history-purge `agconnect-services.json` (P1-2) | hours |
| **P1** | Add JVM unit tests for alarm time maths + model helpers (P1-3) | 1–2 days |
| **P1** | Introduce Room for check-ins/activity records, then habits (P1-1) | 1–2 weeks, incremental |
| **P1** | Move new editor labels into `strings/*.json`; decide pack translation (P1-4) | 1 day |
| **P2** | Pass habit identity to the timer ring screen (P2-1) | hours |
| **P2** | Extract `HabitsScreen` dialogs into `ui/components/` (P2-3) | 1 day |
| **P2** | One shared legacy-schedule normaliser for push and pull (P2-2) | hours |
| **P2** | One shared `SharedPreferences → habits[]` reader (P2-4) | hours |
| **P3** | Merge the two time formatters (P3-2) | nits |

### Suggested order

1. **P0-1 + P1-2 this week.** One is silent user-visible data loss; the other is a live
   credential in a public repo.
2. **P1-3 next.** Tests before the datastore migration, so the migration has a safety net.
3. **P1-1 as a deliberate, incremental project** — check-ins first, behind the existing
   repository interface so callers do not change.

---

## Appendix — repository metrics

| Metric | Value |
|---|---|
| First-party LOC (Kotlin/TS/TSX/SQL) | ~58,500 |
| Kotlin files | ~121 |
| Localisation overlays | 26 |
| Android test files | **0** |
| Web test files | 2 |
| Local database | **none** |
| `TODO`/`FIXME`/`HACK` markers | 1 |
| Committed credential files | 1 (`agconnect-services.json`) |

---

*Report produced by static analysis of the repository at the reviewed commit. No build or
runtime execution was performed — see the verification limitation in §1.*
