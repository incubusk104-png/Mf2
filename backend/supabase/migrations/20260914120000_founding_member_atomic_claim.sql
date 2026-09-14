-- Atomic founding-member claim.
--
-- BEFORE: the POST handler in the `founding-member-eligibility` edge function
-- did hasClaimed() + getClaimCount() and then, as a separate round trip, an
-- insert(). Two requests arriving together at 99/100 could both read
-- "99 < 100" and both insert, landing at 101 (or 102...). The
-- `user_identifier UNIQUE` constraint only stopped the SAME user from
-- double-claiming — it did nothing for two DIFFERENT users racing the last
-- slot, because their identifiers differ so the constraint never fires.
--
-- AFTER: the count and the insert happen inside one function, one transaction,
-- serialized by a transaction-scoped advisory lock. Concurrent claims queue on
-- the lock, so the second one re-reads the count after the first has already
-- inserted and correctly sees the slot as gone.
--
-- The cap is 100 and it is GLOBAL — the count is over the whole table with no
-- country filter. `country` is stored for reporting only and never gates
-- anything. p_max_claims is a parameter (defaulted to 100) purely so the cap
-- lives in one obvious place at the call site; it is not a per-country knob.

CREATE OR REPLACE FUNCTION public.claim_founding_member(
  p_user_id text,
  p_country text DEFAULT '',
  p_plan_id text DEFAULT '',
  p_max_claims integer DEFAULT 100
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $function$
DECLARE
  v_claimed boolean;
  v_total   integer;
BEGIN
  IF p_user_id IS NULL OR btrim(p_user_id) = '' THEN
    RAISE EXCEPTION 'claim_founding_member: user identifier must not be blank'
      USING ERRCODE = '22023';
  END IF;

  IF p_max_claims IS NULL OR p_max_claims < 1 THEN
    p_max_claims := 100;
  END IF;

  -- Serializes concurrent callers so the count read below cannot go stale
  -- before the insert further down. Transaction-scoped: released automatically
  -- on commit or rollback, so it can never be leaked onto the connection.
  PERFORM pg_advisory_xact_lock(hashtext('founding_member_claims')::bigint);

  -- Re-read inside the lock: this is what makes a retry of an already-recorded
  -- claim idempotent rather than a second slot.
  SELECT EXISTS (
    SELECT 1 FROM public.founding_member_claims WHERE user_identifier = p_user_id
  ) INTO v_claimed;

  IF v_claimed THEN
    SELECT count(*)::integer INTO v_total FROM public.founding_member_claims;
    RETURN jsonb_build_object(
      'ok', true,
      'claimed', true,
      'charged', false,
      'remaining', greatest(0, p_max_claims - v_total)
    );
  END IF;

  SELECT count(*)::integer INTO v_total FROM public.founding_member_claims;

  IF v_total >= p_max_claims THEN
    RETURN jsonb_build_object(
      'ok', true,
      'claimed', false,
      'charged', false,
      'remaining', 0
    );
  END IF;

  INSERT INTO public.founding_member_claims (user_identifier, country, plan_id)
  VALUES (p_user_id, coalesce(p_country, ''), coalesce(p_plan_id, ''));

  RETURN jsonb_build_object(
    'ok', true,
    'claimed', true,
    'charged', true,
    'remaining', greatest(0, p_max_claims - v_total - 1)
  );
END;
$function$;

-- The table's RLS policy is "USING (false)" for every role, so this function
-- deliberately runs as its definer. FORCE ROW LEVEL SECURITY is NOT set on
-- founding_member_claims, so the owning role still reads and writes it
-- normally from inside the body while the edge function's service-role
-- connection bypasses RLS the same way the old direct insert() did.
REVOKE ALL ON FUNCTION public.claim_founding_member(text, text, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.claim_founding_member(text, text, text, integer) TO service_role;

COMMENT ON FUNCTION public.claim_founding_member(text, text, text, integer) IS
  'Atomically claims one of the 100 global founding-member slots. Serialized by '
  'a transaction-scoped advisory lock so a concurrent race cannot exceed the cap. '
  'Returns {ok, claimed, charged, remaining}.';
