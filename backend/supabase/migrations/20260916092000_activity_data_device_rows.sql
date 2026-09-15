-- ============================================================================
-- activity_data — accept rows captured ON THE DEVICE
--
-- WHY THIS IS NEEDED
--
-- `activity_data` was designed for server-side provider pulls routed through
-- `activity_connections`: every row carries a NOT NULL `connection_id` FK and a
-- provider from ('strava', 'google_fit', 'huawei_health').
--
-- But the Android app also captures Health Connect data locally
-- (`ActivityMonitor` -> `MindsetRepository.saveActivityRecord`) and that data
-- never left the device. Meanwhile the backend already READS `activity_data`
-- for two features — `smart-alarms` (adaptive alarm times) and
-- `consistency-report` — so both were blind to exactly the data the app's
-- Health Connect integration produces.
--
-- Three things block a device-captured row from being stored:
--   1. `connection_id` is NOT NULL, but a locally-captured Health Connect
--      record has no third-party OAuth connection behind it.
--   2. the provider CHECK constraint has no value for a device-side source.
--   3. RLS has SELECT/INSERT policies only — an upsert resolves to
--      `ON CONFLICT DO UPDATE`, which also needs an UPDATE policy, so a
--      re-sync of the same record would fail with an RLS violation.
--
-- This migration fixes exactly those three and nothing else. The table keeps
-- its shape, its existing rows and its existing readers.
-- ============================================================================

-- ── 1. A device-captured record has no third-party connection ───────────────
ALTER TABLE public.activity_data
  ALTER COLUMN connection_id DROP NOT NULL;

COMMENT ON COLUMN public.activity_data.connection_id IS
  'Third-party OAuth connection this row was pulled through. NULL for rows captured on-device (e.g. Health Connect), which have no connection.';

-- ── 2. Let provider name a device-side source ───────────────────────────────
-- 'health_connect' is the Android platform source the app actually reads.
-- It is kept distinct from 'google_fit' rather than aliased to it, because the
-- two are different APIs with different provenance and a reader should be able
-- to tell them apart.
ALTER TABLE public.activity_data
  DROP CONSTRAINT IF EXISTS activity_data_provider_check;
ALTER TABLE public.activity_data
  ADD CONSTRAINT activity_data_provider_check
  CHECK (provider IN ('strava', 'google_fit', 'huawei_health', 'health_connect'));

-- ── 3. An upsert needs UPDATE as well as INSERT ─────────────────────────────
-- Without this, re-syncing an already-uploaded record (the normal case — the
-- device re-pushes its whole local list) is rejected by RLS.
DROP POLICY IF EXISTS activity_data_update ON public.activity_data;
CREATE POLICY activity_data_update ON public.activity_data
  FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

-- The dedup key for device rows: one row per (user, source, local record id).
-- Already covered by the existing UNIQUE (user_id, provider, provider_activity_id).
CREATE INDEX IF NOT EXISTS idx_activity_data_user_source
  ON public.activity_data (user_id, provider, activity_date DESC);
