-- Multiple alarm times per habit, and per-occurrence habit records.
--
-- Two related gaps, both consequences of the same assumption: that a habit has
-- ONE alarm a day and is therefore ONE event a day.
--
--   1. habits.reminder_minutes is a single smallint, so a habit the user wants
--      at 07:00, 12:00 and 18:00 could only remember one of the three. On a
--      reinstall or a device change the other two alarms were silently gone —
--      the client re-armed exactly one time per habit from this column.
--
--   2. habit_logs had no way to say WHICH of the day's alarms a record answered.
--      With several alarms, "walk: done today" cannot distinguish the 07:00 walk
--      from the 18:00 one, so the client could neither show the user their
--      per-occurrence history nor reliably tell a re-delivered ring from a
--      genuine second occurrence.
--
-- Both additions are additive and nullable/defaulted, so an older client keeps
-- working against the new schema and a new client keeps working against an
-- un-migrated project (the client reads these defensively — see
-- SupabaseSync.selectOrEmpty and Habit.alarmMinutes' fallback).

-- ── 1. habits.alarm_times ────────────────────────────────────────────────────
-- Every time this habit rings, in minutes from midnight, ascending and deduped.
-- reminder_minutes is kept as the FIRST entry (see Habit.withAlarmTimes) so the
-- legacy column remains meaningful and the existing partial index and any older
-- client that only understands one time continue to work.
ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS alarm_times smallint[] NOT NULL DEFAULT '{}'::smallint[];

-- Bound each element to a real time of day.
--
-- NOTE: this predicate CANNOT be a subquery. PostgreSQL refuses a subquery
-- inside a CHECK ("cannot use subquery in check constraint", SQLSTATE 42P17),
-- and "every element is a valid time of day" cannot be expressed without
-- aggregating over the array — so it lives in an IMMUTABLE helper function
-- that the constraint calls. An IMMUTABLE function is permitted in a CHECK; a
-- subquery is not. An earlier revision of this file inlined the
-- `SELECT bool_and(...) FROM unnest(alarm_times)` directly into the CHECK,
-- which made the whole migration fail the moment it was applied.
--
-- Schema-qualified at both ends: a CHECK constraint is evaluated with the
-- caller's search_path, so an unqualified function name would resolve
-- differently for different roles.
CREATE OR REPLACE FUNCTION public.habits_alarm_times_valid(times smallint[])
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT times IS NULL
      OR (
        coalesce(array_length(times, 1), 0) <= 12
        AND NOT EXISTS (SELECT 1 FROM unnest(times) AS t WHERE t < 0 OR t > 1439)
      );
$$;

COMMENT ON FUNCTION public.habits_alarm_times_valid(smallint[]) IS
  'True when the array holds at most 12 times, each 0-1439. Backs the '
  'habits_alarm_times_valid CHECK constraint, which cannot itself contain a '
  'subquery.';

ALTER TABLE habits
  DROP CONSTRAINT IF EXISTS habits_alarm_times_valid;
ALTER TABLE habits
  ADD CONSTRAINT habits_alarm_times_valid CHECK (
    public.habits_alarm_times_valid(alarm_times)
  );

COMMENT ON COLUMN habits.alarm_times IS
  'All alarm times for this habit in minutes from midnight (0-1439), ascending. '
  'Empty = no alarm. reminder_minutes mirrors the first entry for legacy clients.';

-- ── 2. habit_logs.occurrence_key ─────────────────────────────────────────────
-- Shape: '<day>@<minutesFromMidnight>', e.g. '2026-09-16@420' for the 07:00
-- ring. NULL for a record the user made unprompted (tapping the habit, logging
-- water), which is why this cannot be NOT NULL.
--
-- The TIME is embedded in the key rather than stored as a separate smallint
-- because the client's uniqueness test — "has this occurrence already been
-- recorded?" — is then a single string comparison against the same value it
-- derives locally, so the client and the database cannot disagree about what
-- identifies an occurrence.
ALTER TABLE public.habit_logs
  ADD COLUMN IF NOT EXISTS occurrence_key text;

COMMENT ON COLUMN public.habit_logs.occurrence_key IS
  'Identifies the alarm occurrence this record answers: <day>@<minutesFromMidnight>. '
  'NULL = an unprompted record (the user logged it without an alarm ringing).';

-- One record per (habit, occurrence). This is what makes "record every single
-- firing, by time and by date" enforceable rather than merely intended: a
-- retried push, a re-delivered ring, or two devices syncing the same occurrence
-- converge on one row instead of accumulating duplicates that read as extra
-- walks. Partial (WHERE occurrence_key IS NOT NULL) so the unprompted records,
-- of which a user may legitimately make many in a day, are untouched.
CREATE UNIQUE INDEX IF NOT EXISTS idx_habit_logs_occurrence
  ON public.habit_logs (user_id, habit_id, occurrence_key)
  WHERE occurrence_key IS NOT NULL;

-- Reverse-lookup index for "every record of this habit, in occurrence order",
-- which is how the per-occurrence history is read.
CREATE INDEX IF NOT EXISTS idx_habit_logs_habit_occurrence
  ON public.habit_logs (habit_id, occurrence_key);
