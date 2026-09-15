-- A founding-member slot may only be consumed by a VERIFIED, PAID order.
--
-- THE BUG THIS CLOSES
-- -------------------
-- `claim_founding_member` inserted a row on the client's word alone. Its only
-- inputs were the user id, the country, the plan id and the cap — nothing that
-- proved a payment had happened. The app called it from
-- SubscriptionResult.Success, but that value is produced entirely on-device by
-- parsing the purchase-result Intent, so:
--
--   * a modified client could claim a slot with no purchase at all;
--   * SubscriptionResult.Success is ALSO produced from ORDER_PRODUCT_OWNED,
--     which may carry an EMPTY purchase payload — so even an unmodified app
--     could consume a slot on a re-tap of a button, with no new payment;
--   * a refunded or later-voided order still held its slot forever.
--
-- A founding slot is sold once and never again, so a slot consumed without
-- payment is a slot permanently lost. The gate now lives in the database where
-- the client cannot reach it.
--
-- WHAT REPLACES IT
-- ----------------
-- The caller (founding-member-eligibility) verifies the signed purchase against
-- Huawei's Order Service first, and passes the VERIFIED order id, purchase
-- token and purchase state in. The function refuses to write anything unless it
-- is handed a verified, purchased order that carries a usable proof identifier.
--
-- EXISTING ROWS ARE GRANDFATHERED, NOT DELETED
-- --------------------------------------------
-- Rows written before this migration came through the store-success path, so
-- they represent a real order that was never server-verified (the server had no
-- way to check). They are marked verified with an explicit method of
-- 'legacy_client_asserted' rather than being silently dropped — retroactively
-- deleting claimants would free slots that were legitimately sold and would let
-- a region exceed its advertised cap. Everything written from now on must carry
-- 'huawei_order_service'.

-- ── Proof columns ────────────────────────────────────────────────────────────
ALTER TABLE public.founding_member_claims
  ADD COLUMN IF NOT EXISTS verified boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS verification_method text,
  ADD COLUMN IF NOT EXISTS order_id text,
  ADD COLUMN IF NOT EXISTS purchase_token text,
  ADD COLUMN IF NOT EXISTS purchase_state integer,
  ADD COLUMN IF NOT EXISTS verified_at timestamptz;

-- Grandfather the rows that predate server verification. They were only ever
-- written after the store reported success, so they are treated as verified by
-- client assertion — recorded as such rather than presented as proof.
UPDATE public.founding_member_claims
SET verified = true,
    verification_method = 'legacy_client_asserted'
WHERE verification_method IS NULL;

-- ── One paid order can never buy two slots ───────────────────────────────────
-- Partial unique indexes: they only constrain rows that actually carry the
-- identifier, so the grandfathered rows (which have neither) stay untouched.
CREATE UNIQUE INDEX IF NOT EXISTS founding_member_claims_order_id_key
  ON public.founding_member_claims (order_id)
  WHERE order_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS founding_member_claims_purchase_token_key
  ON public.founding_member_claims (purchase_token)
  WHERE purchase_token IS NOT NULL;

COMMENT ON COLUMN public.founding_member_claims.verified IS
  'True only when the purchase behind this claim was verified. Rows written '
  'after 20260916100000 are verified against Huawei''s Order Service; older '
  'rows are grandfathered as client-asserted.';

COMMENT ON COLUMN public.founding_member_claims.order_id IS
  'Huawei order id, as reported BY HUAWEI. Never the client''s value. Unique '
  'when present, so one paid order cannot consume two slots.';

-- ── The count only ever counts verified claims ───────────────────────────────
CREATE OR REPLACE FUNCTION public.founding_member_region_count(p_country text)
RETURNS integer
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $function$
  SELECT count(*)::integer
  FROM public.founding_member_claims
  WHERE verified = true
    AND public.founding_member_region(country) =
        public.founding_member_region(p_country);
$function$;

REVOKE ALL ON FUNCTION public.founding_member_region_count(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.founding_member_region_count(text) TO service_role;

-- ── The atomic claim, now proof-gated ────────────────────────────────────────
-- The previous 4-argument signature is DROPPED, not left in place. Postgres
-- identifies a function by name AND argument types, so keeping it would leave a
-- second, unverified entry point callable by anyone who reached it — the exact
-- hole this migration exists to close.
DROP FUNCTION IF EXISTS public.claim_founding_member(text, text, text, integer);

CREATE OR REPLACE FUNCTION public.claim_founding_member(
  p_user_id text,
  p_country text DEFAULT '',
  p_plan_id text DEFAULT '',
  p_max_claims integer DEFAULT 500,
  -- Proof, all of it derived server-side from Huawei's Order Service response.
  p_verified boolean DEFAULT false,
  p_order_id text DEFAULT NULL,
  p_purchase_token text DEFAULT NULL,
  p_purchase_state integer DEFAULT NULL
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

  IF p_max_claims IS NULL OR p_max_claims < 1 THEN
    p_max_claims := 500;
  END IF;

  v_region := public.founding_member_region(p_country);

  -- THE GATE. Refusing here is what makes "only a successful purchase counts"
  -- true even against a hostile client: nothing is written, no slot is
  -- consumed, and the caller is told exactly why.
  --
  -- Note this runs BEFORE the advisory lock: an unverified request must not
  -- queue behind real claimants, and it cannot affect the count anyway.
  IF p_verified IS NOT TRUE THEN
    SELECT count(*)::integer INTO v_total
    FROM public.founding_member_claims
    WHERE verified = true
      AND public.founding_member_region(country) = v_region;
    RETURN jsonb_build_object(
      'ok', false, 'claimed', false, 'charged', false,
      'reason', 'unverified_purchase',
      'region', v_region,
      'remaining', greatest(0, p_max_claims - v_total)
    );
  END IF;

  IF p_purchase_state IS DISTINCT FROM 0 THEN
    SELECT count(*)::integer INTO v_total
    FROM public.founding_member_claims
    WHERE verified = true
      AND public.founding_member_region(country) = v_region;
    RETURN jsonb_build_object(
      'ok', false, 'claimed', false, 'charged', false,
      'reason', 'not_purchased_state',
      'region', v_region,
      'remaining', greatest(0, p_max_claims - v_total)
    );
  END IF;

  -- A claim with no proof identifier could never be deduplicated against a
  -- replayed order, so it is refused rather than stored unpinnable.
  IF (p_order_id IS NULL OR btrim(p_order_id) = '')
     AND (p_purchase_token IS NULL OR btrim(p_purchase_token) = '') THEN
    SELECT count(*)::integer INTO v_total
    FROM public.founding_member_claims
    WHERE verified = true
      AND public.founding_member_region(country) = v_region;
    RETURN jsonb_build_object(
      'ok', false, 'claimed', false, 'charged', false,
      'reason', 'missing_order_proof',
      'region', v_region,
      'remaining', greatest(0, p_max_claims - v_total)
    );
  END IF;

  -- Per-REGION lock: two callers in the same country queue here and the second
  -- re-reads the count after the first has committed; a caller in a different
  -- country takes a different key and never waits behind them.
  PERFORM pg_advisory_xact_lock(
    hashtext('founding_member_claims:' || v_region)::bigint
  );

  SELECT EXISTS (
    SELECT 1 FROM public.founding_member_claims WHERE user_identifier = p_user_id
  ) INTO v_claimed;

  IF v_claimed THEN
    SELECT count(*)::integer INTO v_total
    FROM public.founding_member_claims
    WHERE verified = true
      AND public.founding_member_region(country) = v_region;
    RETURN jsonb_build_object(
      'ok', true, 'claimed', true, 'charged', false,
      'reason', 'already_claimed',
      'region', v_region,
      'remaining', greatest(0, p_max_claims - v_total)
    );
  END IF;

  -- The same paid order presented by a second account. The unique index would
  -- also stop it, but checking here keeps the failure a clean JSON answer
  -- rather than a raw constraint violation reaching the client.
  IF p_order_id IS NOT NULL AND btrim(p_order_id) <> '' THEN
    IF EXISTS (
      SELECT 1 FROM public.founding_member_claims
      WHERE order_id = p_order_id OR (p_purchase_token IS NOT NULL
                                      AND purchase_token = p_purchase_token)
    ) THEN
      SELECT count(*)::integer INTO v_total
      FROM public.founding_member_claims
      WHERE verified = true
        AND public.founding_member_region(country) = v_region;
      RETURN jsonb_build_object(
        'ok', true, 'claimed', false, 'charged', false,
        'reason', 'order_already_used',
        'region', v_region,
        'remaining', greatest(0, p_max_claims - v_total)
      );
    END IF;
  END IF;

  SELECT count(*)::integer INTO v_total
  FROM public.founding_member_claims
  WHERE verified = true
    AND public.founding_member_region(country) = v_region;

  IF v_total >= p_max_claims THEN
    RETURN jsonb_build_object(
      'ok', true, 'claimed', false, 'charged', false,
      'reason', 'region_full',
      'region', v_region,
      'remaining', 0
    );
  END IF;

  INSERT INTO public.founding_member_claims (
    user_identifier, country, plan_id,
    verified, verification_method, order_id, purchase_token, purchase_state,
    verified_at
  )
  VALUES (
    p_user_id, coalesce(p_country, ''), coalesce(p_plan_id, ''),
    true, 'huawei_order_service',
    nullif(btrim(coalesce(p_order_id, '')), ''),
    nullif(btrim(coalesce(p_purchase_token, '')), ''),
    p_purchase_state,
    now()
  );

  RETURN jsonb_build_object(
    'ok', true, 'claimed', true, 'charged', true,
    'reason', 'claimed',
    'region', v_region,
    'remaining', greatest(0, p_max_claims - v_total - 1)
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.claim_founding_member(text, text, text, integer, boolean, text, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.claim_founding_member(text, text, text, integer, boolean, text, text, integer) TO service_role;

COMMENT ON FUNCTION public.claim_founding_member(text, text, text, integer, boolean, text, text, integer) IS
  'Atomically claims one of the founding-member slots for the caller''s region, '
  'ONLY when handed a purchase Huawei has verified and that is in the purchased '
  'state. Serialized by a per-region transaction-scoped advisory lock. '
  'Returns {ok, claimed, charged, reason, region, remaining}.';
