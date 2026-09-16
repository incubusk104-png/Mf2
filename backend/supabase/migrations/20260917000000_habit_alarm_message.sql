-- habits.alarm_message — the motivational reminder line, synced.
--
-- Companion to the client's `Habit.alarmMessage`: the encouraging sentence a
-- habit's reminder says at ring time, which the user either writes themselves or
-- leaves empty to get the curated pack for that habit.
--
-- Until this column existed the field was DEVICE-LOCAL. It was written,
-- stored, and delivered correctly on the device that created it — but a
-- reinstall or a device change silently restored the habit without its line,
-- because the push/pull mapping had nowhere to put it. The client already sends
-- and reads `alarm_message` (see SupabaseSync's `HabitRow`) and degrades safely
-- while the column is absent: PostgREST reports it as a missing column, the
-- client retries the upsert without it so the rest of the row still lands, and
-- the field is surfaced to the user as "still waiting to reach the cloud"
-- rather than being silently dropped. Applying this migration turns that
-- degradation back into a real, round-tripping sync.
--
-- Additive and nullable on purpose:
--   * `DEFAULT NULL` means "no custom message — use the curated line", which is
--     exactly what a habit created before this feature existed means.
--   * An older client that never sends the column keeps working untouched, and
--     a new client keeps working against an un-migrated project.

ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS alarm_message text DEFAULT NULL;

-- Bound the stored length to the same ceiling the client enforces
-- (MotivationalMessages.MAX_MESSAGE_LENGTH). The client sanitises on write and
-- again on read, so this is defence in depth rather than the only guard: a
-- direct PostgREST write, or a future client that forgets to sanitise, must not
-- be able to store an essay that then renders as a broken notification.
--
-- Also rejects control characters — notably the newline — for the same reason
-- the client strips them: this value becomes notification text posted by a
-- background receiver, and a newline in a notification title is a known way to
-- make a notification render deceptively (a half-height row, or a body that
-- reads as if it came from another app).
ALTER TABLE habits
  DROP CONSTRAINT IF EXISTS habits_alarm_message_valid;
ALTER TABLE habits
  ADD CONSTRAINT habits_alarm_message_valid CHECK (
    alarm_message IS NULL
    OR (
      char_length(alarm_message) <= 120
      AND alarm_message !~ '[\x00-\x1F\x7F]'
    )
  );

COMMENT ON COLUMN habits.alarm_message IS
  'Motivational line this habit''s reminders say at ring time. NULL = use the '
  'client''s curated pack for the habit''s icon. Max 120 chars, single line.';

-- No index: this column is never filtered or sorted on. It is read only as part
-- of the row it belongs to (the restore path selects whole habits), so an index
-- here would cost write throughput on every habit upsert for nothing.
