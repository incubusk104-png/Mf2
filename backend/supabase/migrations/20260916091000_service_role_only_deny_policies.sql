-- ============================================================================
-- Belt-and-braces: explicit DENY policies for service-role-only tables
--
-- These three tables have RLS enabled with NO policies, which already means
-- "no client can read or write any row" — Postgres denies by default. That is
-- correct and intentional: school secrets, purchase records and foundation
-- counters are all written by Edge Functions holding the service-role key.
--
-- The risk this migration addresses is not the current behaviour, it is the
-- NEXT change. An empty policy list reads to a future maintainer like an
-- oversight ("RLS is on but nothing is configured — someone must have
-- forgotten"), and the obvious-looking fix is to add a policy. Adding
-- `USING (true)` to app_secrets would expose the Huawei auth pepper; doing it
-- to tip_purchases would expose purchase tokens. Stating the denial
-- explicitly, with the reason, makes "no policies" a decision rather than an
-- apparent gap.
--
-- These policies deny everything for the public roles. A service-role
-- connection bypasses RLS entirely, so the Edge Functions are unaffected.
-- ============================================================================

-- ── app_secrets ─────────────────────────────────────────────────────────────
-- Holds the Huawei auth pepper. If an anon/authenticated caller could read
-- this, every derived Huawei password becomes forgeable.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'app_secrets') THEN
    EXECUTE 'DROP POLICY IF EXISTS app_secrets_deny_clients ON public.app_secrets';
    EXECUTE $p$
      CREATE POLICY app_secrets_deny_clients ON public.app_secrets
        FOR ALL
        TO anon, authenticated
        USING (false)
        WITH CHECK (false)
    $p$;
    EXECUTE $c$
      COMMENT ON TABLE public.app_secrets IS
        'Service-role-only secret store (Huawei auth pepper). RLS denies ALL access to anon/authenticated by explicit policy — see 20260916091000_service_role_only_deny_policies.sql. Do NOT add a permissive policy here.'
    $c$;
  END IF;
END $$;

-- ── tip_purchases ───────────────────────────────────────────────────────────
-- Purchase tokens are the dedup key for tips; they are audit material, not
-- client-readable data.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'tip_purchases') THEN
    EXECUTE 'DROP POLICY IF EXISTS tip_purchases_deny_clients ON public.tip_purchases';
    EXECUTE $p$
      CREATE POLICY tip_purchases_deny_clients ON public.tip_purchases
        FOR ALL
        TO anon, authenticated
        USING (false)
        WITH CHECK (false)
    $p$;
    EXECUTE $c$
      COMMENT ON TABLE public.tip_purchases IS
        'Huawei IAP tip purchases written by the tip-purchase Edge Function with the service role. RLS denies ALL access to anon/authenticated by explicit policy — do NOT add a permissive policy.'
    $c$;
  END IF;
END $$;

-- ── founding_member_region_caps ─────────────────────────────────────────────
-- The per-region founding-member counters. Read access is served through the
-- founding-member Edge Function (which returns remaining/region, not raw
-- rows), so clients never need direct table access.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'founding_member_region_caps') THEN
    EXECUTE 'DROP POLICY IF EXISTS founding_member_region_caps_deny_clients ON public.founding_member_region_caps';
    EXECUTE $p$
      CREATE POLICY founding_member_region_caps_deny_clients ON public.founding_member_region_caps
        FOR ALL
        TO anon, authenticated
        USING (false)
        WITH CHECK (false)
    $p$;
    EXECUTE $c$
      COMMENT ON TABLE public.founding_member_region_caps IS
        'Per-region founding-member counters, written by claim_founding_member() and read via the founding-member Edge Function. RLS denies ALL client access by explicit policy — do NOT add a permissive policy.'
    $c$;
  END IF;
END $$;
