-- Add alarm/reminder metadata columns to the habits table.
-- These columns allow the server to store per-habit alarm configuration
-- so it can be synced across devices and restored after reinstall/device
-- change. The Android client uses these values to re-arm AlarmManager
-- alarms via HabitAlarmScheduler.rescheduleAll().

-- reminder_minutes: minutes from midnight (0–1439) when the habit alarm
-- should fire. NULL means no individual alarm for this habit.
ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS reminder_minutes smallint DEFAULT NULL
  CHECK (reminder_minutes IS NULL OR (reminder_minutes >= 0 AND reminder_minutes <= 1439));

-- icon_id: references HabitIconCatalog.HabitIcon.id in the Android client
-- (e.g. "water", "running", "strava_yoga"). Used to associate the correct
-- icon + category colour when restoring habits on a new device.
ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS icon_id text DEFAULT NULL
  CHECK (icon_id IS NULL OR char_length(icon_id) <= 100);

-- is_pinned: whether the user pinned this habit to the top of their list.
ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS is_pinned boolean DEFAULT false NOT NULL;

-- duration_seconds: for timed habits (meditation, workout timers).
-- NULL means it's a simple checkbox habit.
ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS duration_seconds integer DEFAULT NULL
  CHECK (duration_seconds IS NULL OR duration_seconds > 0);

-- Index for fast lookups: "give me all habits that have an alarm set"
CREATE INDEX IF NOT EXISTS idx_habits_reminder
  ON habits (user_id, reminder_minutes)
  WHERE reminder_minutes IS NOT NULL;

-- Comment for documentation
COMMENT ON COLUMN habits.reminder_minutes IS
  'Minutes from midnight (0-1439) for this habit''s daily alarm. NULL = no alarm.';
COMMENT ON COLUMN habits.icon_id IS
  'References HabitIconCatalog icon ID in the Android client (e.g. "water", "running").';
COMMENT ON COLUMN habits.is_pinned IS
  'Pinned habits sort to the top of the user''s habit list.';
COMMENT ON COLUMN habits.duration_seconds IS
  'Timer duration for timed habits (meditation, workouts). NULL = checkbox habit.';
