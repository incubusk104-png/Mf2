-- Habit alarm repeat schedule + screen-time monitoring columns.
--
-- repeat_days_mask: 7-bit day mask for the habit's alarm, bit 0 = Monday …
-- bit 6 = Sunday (mirrors the system Clock app's Repeat row).
--   127 = daily (default), 0 = once, 31 = weekdays, 96 = weekends.
ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS repeat_days_mask smallint DEFAULT 127 NOT NULL
  CHECK (repeat_days_mask >= 0 AND repeat_days_mask <= 127);

-- Screen-time habits: package name of the phone app being monitored
-- (e.g. 'com.facebook.katana') and the daily minutes budget. Both NULL for
-- ordinary habits.
ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS monitored_package text DEFAULT NULL
  CHECK (monitored_package IS NULL OR char_length(monitored_package) <= 255);

ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS screen_time_limit_minutes integer DEFAULT NULL
  CHECK (screen_time_limit_minutes IS NULL OR (screen_time_limit_minutes > 0 AND screen_time_limit_minutes <= 1440));

ALTER TABLE habits
  ADD COLUMN IF NOT EXISTS monitored_app_label text DEFAULT NULL
  CHECK (monitored_app_label IS NULL OR char_length(monitored_app_label) <= 120);

COMMENT ON COLUMN habits.repeat_days_mask IS
  'Alarm repeat as 7-bit day mask (bit0=Mon..bit6=Sun). 127=daily, 0=once, 31=weekdays, 96=weekends.';
COMMENT ON COLUMN habits.monitored_package IS
  'Screen-time habit: Android package name of the monitored app. NULL for normal habits.';
COMMENT ON COLUMN habits.screen_time_limit_minutes IS
  'Screen-time habit: daily usage budget in minutes (auto-completes when under it).';
COMMENT ON COLUMN habits.monitored_app_label IS
  'Screen-time habit: display label of the monitored app.';
