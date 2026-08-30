-- ============================================================================
-- Activity Integrations — Strava, Google Fit, Huawei Health Kit
--
-- Stores OAuth connection state, synced activity data, smart adaptive alarms,
-- consistency reports, and social share tokens.
-- ============================================================================

-- ── activity_connections ─────────────────────────────────────────────────────
-- Tracks which third-party fitness accounts the user has connected.
-- The user MUST authenticate (OAuth) with each provider before any sync.
CREATE TABLE IF NOT EXISTS activity_connections (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  provider text NOT NULL CHECK (provider IN ('strava', 'google_fit', 'huawei_health')),
  -- OAuth tokens (encrypted at rest by Supabase)
  access_token text,
  refresh_token text,
  token_expires_at bigint,         -- Unix epoch seconds
  -- Provider-specific user profile
  provider_user_id text,
  provider_display_name text,
  provider_avatar_url text,
  -- Connection state
  is_connected boolean DEFAULT false NOT NULL,
  last_sync_at timestamptz,
  sync_error text,
  connected_at timestamptz DEFAULT now(),
  disconnected_at timestamptz,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now(),
  -- One connection per provider per user
  UNIQUE (user_id, provider)
);

-- RLS: users can only see/modify their own connections
ALTER TABLE activity_connections ENABLE ROW LEVEL SECURITY;
CREATE POLICY activity_connections_select ON activity_connections
  FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY activity_connections_insert ON activity_connections
  FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY activity_connections_update ON activity_connections
  FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY activity_connections_delete ON activity_connections
  FOR DELETE USING (auth.uid() = user_id);

-- ── activity_data ────────────────────────────────────────────────────────────
-- Normalized activity records pulled from all connected providers.
-- Each row = one activity session (run, walk, yoga, sleep, etc.)
CREATE TABLE IF NOT EXISTS activity_data (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  connection_id uuid NOT NULL REFERENCES activity_connections(id) ON DELETE CASCADE,
  provider text NOT NULL CHECK (provider IN ('strava', 'google_fit', 'huawei_health')),
  -- Provider's unique ID for deduplication
  provider_activity_id text NOT NULL,
  -- Normalized fields
  activity_type text NOT NULL,      -- 'run', 'walk', 'cycle', 'swim', 'yoga', 'sleep', 'workout', etc.
  activity_name text,               -- User-given name or provider default
  started_at timestamptz NOT NULL,
  ended_at timestamptz,
  duration_seconds integer,         -- Total active time
  distance_meters real,             -- Distance (if applicable)
  calories_burned real,
  heart_rate_avg integer,
  heart_rate_max integer,
  steps integer,
  elevation_gain_meters real,
  -- Raw provider payload for future use
  raw_data jsonb DEFAULT '{}',
  -- The day this activity belongs to (for grouping with habits)
  activity_date date NOT NULL,
  synced_at timestamptz DEFAULT now(),
  created_at timestamptz DEFAULT now(),
  -- Prevent duplicate imports
  UNIQUE (user_id, provider, provider_activity_id)
);

ALTER TABLE activity_data ENABLE ROW LEVEL SECURITY;
CREATE POLICY activity_data_select ON activity_data
  FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY activity_data_insert ON activity_data
  FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Index for time-range queries (dashboard, reports)
CREATE INDEX IF NOT EXISTS idx_activity_data_user_date
  ON activity_data (user_id, activity_date DESC);
CREATE INDEX IF NOT EXISTS idx_activity_data_user_type
  ON activity_data (user_id, activity_type, activity_date DESC);

-- ── smart_alarms ─────────────────────────────────────────────────────────────
-- Adaptive alarm schedule generated from activity patterns.
-- When a user's habits have inconsistent timing, the system analyzes their
-- actual activity data to suggest optimal alarm times.
CREATE TABLE IF NOT EXISTS smart_alarms (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  habit_id uuid REFERENCES habits(id) ON DELETE CASCADE,
  -- Alarm configuration
  alarm_type text NOT NULL CHECK (alarm_type IN (
    'habit_reminder',      -- Tied to a specific habit
    'activity_nudge',      -- Suggests starting an activity based on patterns
    'consistency_check',   -- Fires when user is about to break a streak
    'recovery_reminder',   -- Post-workout recovery window
    'sleep_wind_down'      -- Based on sleep pattern analysis
  )),
  -- Computed optimal time (minutes from midnight, like habits.reminder_minutes)
  suggested_minutes smallint NOT NULL CHECK (suggested_minutes >= 0 AND suggested_minutes <= 1439),
  -- The user's actual alarm time (may differ from suggestion)
  active_minutes smallint CHECK (active_minutes IS NULL OR (active_minutes >= 0 AND active_minutes <= 1439)),
  -- Days of week this alarm fires (bitmask: Mon=1, Tue=2, Wed=4, ... Sun=64)
  days_of_week smallint DEFAULT 127 NOT NULL CHECK (days_of_week >= 0 AND days_of_week <= 127),
  -- Analysis metadata
  confidence_score real DEFAULT 0.5 CHECK (confidence_score >= 0 AND confidence_score <= 1),
  based_on_activities integer DEFAULT 0,      -- How many activity records informed this
  avg_activity_time_minutes smallint,         -- Average time user actually does this
  variance_minutes smallint,                  -- How inconsistent the user's timing is
  -- State
  is_enabled boolean DEFAULT true NOT NULL,
  is_user_overridden boolean DEFAULT false NOT NULL,  -- User manually changed the time
  last_triggered_at timestamptz,
  message text,                               -- Custom alarm message
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now()
);

ALTER TABLE smart_alarms ENABLE ROW LEVEL SECURITY;
CREATE POLICY smart_alarms_select ON smart_alarms
  FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY smart_alarms_insert ON smart_alarms
  FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY smart_alarms_update ON smart_alarms
  FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY smart_alarms_delete ON smart_alarms
  FOR DELETE USING (auth.uid() = user_id);

CREATE INDEX IF NOT EXISTS idx_smart_alarms_user
  ON smart_alarms (user_id, is_enabled) WHERE is_enabled = true;

-- ── consistency_reports ──────────────────────────────────────────────────────
-- Pre-generated consistency + activity reports the user can share.
CREATE TABLE IF NOT EXISTS consistency_reports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  -- Report configuration
  report_type text NOT NULL CHECK (report_type IN ('weekly', 'monthly', 'custom')),
  period_start date NOT NULL,
  period_end date NOT NULL,
  -- Aggregated metrics
  total_activities integer DEFAULT 0,
  total_duration_seconds bigint DEFAULT 0,
  total_distance_meters real DEFAULT 0,
  total_calories real DEFAULT 0,
  total_steps bigint DEFAULT 0,
  habit_completion_rate real DEFAULT 0,     -- 0.0–1.0
  longest_streak integer DEFAULT 0,
  current_streak integer DEFAULT 0,
  most_active_day text,                      -- 'Monday', 'Tuesday', etc.
  most_active_time text,                     -- 'morning', 'afternoon', 'evening'
  top_activities jsonb DEFAULT '[]',         -- [{ type, count, duration }]
  mood_distribution jsonb DEFAULT '{}',      -- { calm: 5, focused: 3, ... }
  daily_breakdown jsonb DEFAULT '[]',        -- Day-by-day detail
  -- Sharing
  share_token text UNIQUE,                   -- Public share URL token
  share_image_url text,                      -- Pre-rendered share card
  is_public boolean DEFAULT false NOT NULL,
  shared_platforms jsonb DEFAULT '[]',       -- ['twitter', 'instagram', ...]
  share_count integer DEFAULT 0,
  created_at timestamptz DEFAULT now()
);

ALTER TABLE consistency_reports ENABLE ROW LEVEL SECURITY;
CREATE POLICY consistency_reports_select ON consistency_reports
  FOR SELECT USING (auth.uid() = user_id OR is_public = true);
CREATE POLICY consistency_reports_insert ON consistency_reports
  FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY consistency_reports_update ON consistency_reports
  FOR UPDATE USING (auth.uid() = user_id);

CREATE INDEX IF NOT EXISTS idx_consistency_reports_user
  ON consistency_reports (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_consistency_reports_share
  ON consistency_reports (share_token) WHERE share_token IS NOT NULL;

-- ── Helper: update updated_at on changes ─────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_activity_connections_updated
  BEFORE UPDATE ON activity_connections
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_smart_alarms_updated
  BEFORE UPDATE ON smart_alarms
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Comments
COMMENT ON TABLE activity_connections IS
  'OAuth connections to Strava / Google Fit / Huawei Health Kit. User must authenticate before sync.';
COMMENT ON TABLE activity_data IS
  'Normalized activity records synced from connected fitness providers.';
COMMENT ON TABLE smart_alarms IS
  'Adaptive alarms computed from activity patterns to handle inconsistent habit timing.';
COMMENT ON TABLE consistency_reports IS
  'Shareable activity + habit consistency reports with social media sharing support.';
