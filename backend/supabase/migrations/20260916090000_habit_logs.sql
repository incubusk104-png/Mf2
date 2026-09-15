-- ============================================================================
-- habit_logs — the payload of a habit's own tracking tool, synced
--
-- WHY THIS TABLE EXISTS
--
-- `checkins` records *that* a habit was done on a day. It cannot record *what*
-- was done: how long the walk was, what the journal entry said, how many
-- glasses of water were drunk. Those live in HabitLogEntry, which until now
-- was written to SharedPreferences only — so a journal note, a measured
-- duration or a count existed on exactly one device, and was lost on
-- reinstall. ("Every setup has a record" is only true if the record survives.)
--
-- The shape mirrors HabitLogEntry one-to-one, so the Android serializer needs
-- no translation layer.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.habit_logs (
  -- Client-generated id (HabitLogEntry.id) so an upsert is idempotent.
  id text PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  -- Per-install device id, matching the convention every other synced table
  -- uses. Kept for provenance; not part of any key.
  device_id text,
  habit_id uuid NOT NULL,
  -- Local calendar day, 'YYYY-MM-DD'. Stored as text rather than date to
  -- match `checkins.day` exactly — the two are joined on (habit_id, day).
  day text NOT NULL,
  -- The tracking mode that produced this entry (CHECK / TIMER / STOPWATCH /
  -- JOURNAL / COUNT). Text, not an enum, so a new mode can ship in the app
  -- without a migration.
  mode text,
  title text,
  note text,
  -- COUNT entries: the amount and its unit ("8", "glasses").
  count integer,
  unit text,
  -- TIMER / STOPWATCH entries: measured seconds.
  duration_seconds integer,
  -- When the user actually recorded it (client clock, epoch millis).
  logged_at_ms bigint,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Upsert target: one row per entry id (primary key), and the read path is
-- always "this user's entries", optionally for one habit within a day range.
CREATE INDEX IF NOT EXISTS idx_habit_logs_user_day
  ON public.habit_logs (user_id, day DESC);
CREATE INDEX IF NOT EXISTS idx_habit_logs_user_habit
  ON public.habit_logs (user_id, habit_id, day DESC);

ALTER TABLE public.habit_logs ENABLE ROW LEVEL SECURITY;

-- Owner-scoped, mirroring `checkins`: a user can only ever see or touch their
-- own rows, on every verb.
DROP POLICY IF EXISTS habit_logs_select ON public.habit_logs;
CREATE POLICY habit_logs_select ON public.habit_logs
  FOR SELECT USING (auth.uid() = user_id);

DROP POLICY IF EXISTS habit_logs_insert ON public.habit_logs;
CREATE POLICY habit_logs_insert ON public.habit_logs
  FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS habit_logs_update ON public.habit_logs;
CREATE POLICY habit_logs_update ON public.habit_logs
  FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS habit_logs_delete ON public.habit_logs;
CREATE POLICY habit_logs_delete ON public.habit_logs
  FOR DELETE USING (auth.uid() = user_id);

COMMENT ON TABLE public.habit_logs IS
  'Payload of a habit''s tracking tool (count / note / duration), one row per entry. Owner-scoped RLS. Mirrors HabitLogEntry in the Android app.';
COMMENT ON COLUMN public.habit_logs.day IS
  'Local calendar day, YYYY-MM-DD. Joinable with checkins.day.';
COMMENT ON COLUMN public.habit_logs.count IS
  'COUNT mode amount (e.g. glasses of water). NULL for other modes.';
COMMENT ON COLUMN public.habit_logs.duration_seconds IS
  'TIMER/STOPWATCH measured seconds. NULL for other modes.';
