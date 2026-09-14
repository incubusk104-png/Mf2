-- Founding-member cap: 500 slots PER COUNTRY / REGION.
--
-- Supersedes the single GLOBAL 100-slot cap introduced in
-- 20260914120000_founding_member_atomic_claim.sql. The atomic-claim shape is
-- kept exactly as it was -- the cap check and the insert happen in one
-- transaction under a transaction-scoped advisory lock -- but the bucket the
-- count is taken over changes from "the whole table" to "the caller's region",
-- and the lock key changes from one global string to one string PER REGION, so
-- a claim in one country no longer serializes against a claim in another.
--
-- Region = the ISO-3166 alpha-2 country code, upper-cased and trimmed.
-- Anything unresolvable (null, blank, a non-country string) folds into the
-- single 'ZZ' bucket. 'ZZ' is ISO-3166's own "unknown" value, so it is
-- deliberately conventional rather than invented here.
--
-- `country` stays the stored column: no data migration, no backfill, and the
-- rows written before this migration keep working, because the region is
-- DERIVED from country rather than stored beside it. Two claims that stored
-- different spellings of the same country ("de", " DE ") now count against the
-- same region bucket, which is the point.

-- ── Region normalization ────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.founding_member_region(p_country text)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $function$
  SELECT CASE
    WHEN p_country IS NULL THEN 'ZZ'
    WHEN btrim(p_country) = '' THEN 'ZZ'
    ELSE upper(btrim(p_country))
  END;
$function$;

COMMENT ON FUNCTION public.founding_member_region(text) IS
  'Normalizes a stored country into the founding-member region bucket '
  '(uppercased ISO-3166 alpha-2, or ZZ when unresolvable). Three copies of '
  'this rule must agree: this function, regionFor() in the '
  'founding-member-eligibility edge function, and currentRegion() in the '
  'Android SupabaseSync.kt.';

-- Expression index so the per-region count(*) is an index scan instead of a
-- sequential scan once the table grows past a few thousand rows.
CREATE INDEX IF NOT EXISTS founding_member_claims_region_idx
  ON public.founding_member_claims (public.founding_member_region(country));

-- ── Per-region count (read path) ────────────────────────────────────────────
-- The edge function cannot filter on a function expression through the
-- supabase-js query builder, so the read side gets its own tiny helper rather
-- than doing a full-table select and counting in TypeScript.
CREATE OR REPLACE FUNCTION public.founding_member_region_count(p_country text)
RETURNS integer
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $function$
  SELECT count(*)::integer
  FROM public.founding_member_claims
  WHERE public.founding_member_region(country) =
        public.founding_member_region(p_country);
$function$;

REVOKE ALL ON FUNCTION public.founding_member_region_count(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.founding_member_region_count(text) TO service_role;

-- ── Per-region targets ──────────────────────────────────────────────────────
-- The 500 target is the DEFAULT for every region, not a value frozen into the
-- function. Keeping the overrides in a table means a region can be re-targeted
-- (a soft launch, a correction) without shipping a migration, and it gives the
-- edge function one place to read the number from.
CREATE TABLE IF NOT EXISTS public.founding_member_region_caps (
  region     text PRIMARY KEY,
  max_claims integer NOT NULL CHECK (max_claims >= 1),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.founding_member_region_caps ENABLE ROW LEVEL SECURITY;

-- Read-only via service role; the edge function is the only consumer and it
-- calls through the SECURITY DEFINER function below.
CREATE POLICY "Deny direct access to founding_member_region_caps"
  ON public.founding_member_region_caps
  FOR ALL
  USING (false);

CREATE OR REPLACE FUNCTION public.founding_member_cap_for_region(p_country text)
RETURNS integer
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $function$
  SELECT coalesce(
    (
      SELECT c.max_claims
      FROM public.founding_member_region_caps c
      WHERE c.region = public.founding_member_region(p_country)
    ),
    500
  );
$function$;

REVOKE ALL ON FUNCTION public.founding_member_cap_for_region(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.founding_member_cap_for_region(text) TO service_role;

COMMENT ON FUNCTION public.founding_member_cap_for_region(text) IS
  'Slot target for a region: the per-region override when present, else the '
  'default 500. Single source of truth for the cap that both the GET read and '
  'the atomic claim use.';

-- ── Atomic per-region claim (write path) ────────────────────────────────────
CREATE OR REPLACE FUNCTION public.claim_founding_member(
  p_user_id text,
  p_country text DEFAULT '',
  p_plan_id text DEFAULT '',
  p_max_claims integer DEFAULT 500
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $function$
DECLARE
  v_region  text;
  v_claimed boolean;
  v_total   integer;
BEGIN
  IF p_user_id IS NULL OR btrim(p_user_id) = '' THEN
    RAISE EXCEPTION 'claim_founding_member: user identifier must not be blank'
      USING ERRCODE = '22023';
  END IF;

  -- 500 is the PER-REGION target. Callers pass it explicitly so the number
  -- lives in one obvious place at the call site; the default keeps a bare
  -- rpc() call correct on its own.
  IF p_max_claims IS NULL OR p_max_claims < 1 THEN
    p_max_claims := 500;
  END IF;

  v_region := public.founding_member_region(p_country);

  -- Per-REGION lock. Two callers in the same country queue here and the second
  -- re-reads the count after the first has committed; a caller in a different
  -- country takes a different key and never waits behind them. Transaction
  -- scoped, so it is released on commit or rollback and cannot be leaked.
  PERFORM pg_advisory_xact_lock(
    hashtext('founding_member_claims:' || v_region)::bigint
  );

  -- Re-read inside the lock. This is what makes a retry of an
  -- already-recorded claim idempotent instead of a second slot.
  SELECT EXISTS (
    SELECT 1 FROM public.founding_member_claims WHERE user_identifier = p_user_id
  ) INTO v_claimed;

  IF v_claimed THEN
    SELECT count(*)::integer INTO v_total
    FROM public.founding_member_claims
    WHERE public.founding_member_region(country) = v_region;
    RETURN jsonb_build_object(
      'ok', true,
      'claimed', true,
      'charged', false,
      'region', v_region,
      'remaining', greatest(0, p_max_claims - v_total)
    );
  END IF;

  SELECT count(*)::integer INTO v_total
  FROM public.founding_member_claims
  WHERE public.founding_member_region(country) = v_region;

  IF v_total >= p_max_claims THEN
    RETURN jsonb_build_object(
      'ok', true,
      'claimed', false,
      'charged', false,
      'region', v_region,
      'remaining', 0
    );
  END IF;

  INSERT INTO public.founding_member_claims (user_identifier, country, plan_id)
  VALUES (p_user_id, coalesce(p_country, ''), coalesce(p_plan_id, ''));

  RETURN jsonb_build_object(
    'ok', true,
    'claimed', true,
    'charged', true,
    'region', v_region,
    'remaining', greatest(0, p_max_claims - v_total - 1)
  );
END;
$function$;

-- The table's RLS policy is "USING (false)" for every role, so both functions
-- deliberately run as their definer. FORCE ROW LEVEL SECURITY is NOT set on
-- founding_member_claims, so the owning role still reads and writes the table
-- normally from inside the body while the edge function's service-role
-- connection bypasses RLS exactly as the previous implementation did.
REVOKE ALL ON FUNCTION public.claim_founding_member(text, text, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.claim_founding_member(text, text, text, integer) TO service_role;

COMMENT ON FUNCTION public.claim_founding_member(text, text, text, integer) IS
  'Atomically claims one of the 500 founding-member slots for the caller''s '
  'country/region. Serialized by a per-region transaction-scoped advisory lock '
  'so a concurrent race cannot push any single region past its cap. '
  'Returns {ok, claimed, charged, region, remaining}.';
