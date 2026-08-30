-- Server-side support objects required by the huawei-auth Edge Function.
-- Without BOTH of these, every Huawei sign-in fails with
-- "Sign-in is temporarily unavailable" (500) even when the on-device
-- Account Kit leg succeeded:
--
--   1. public.app_secrets            — holds the huawei_auth_pepper used to
--                                      derive per-user passwords (HMAC key).
--   2. public.huawei_user_id_by_email — service-role lookup of an auth user
--                                      id by the internal @huawei-id.local
--                                      address (auth.users is not otherwise
--                                      reachable through PostgREST).
--
-- This migration is idempotent and self-seeds the pepper with a random
-- value on first run. Re-running never rotates an existing pepper (rotating
-- would orphan every already-provisioned Huawei account).

-- ── 1. app_secrets ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.app_secrets (
  key text PRIMARY KEY,
  value text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- RLS on with zero policies + revoked grants = service-role-only access.
ALTER TABLE public.app_secrets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.app_secrets FROM anon, authenticated;

COMMENT ON TABLE public.app_secrets IS
  'Service-role-only key/value secrets (e.g. huawei_auth_pepper). No client access.';

-- Seed the Huawei auth pepper exactly once with 32 random bytes (hex).
INSERT INTO public.app_secrets (key, value)
SELECT 'huawei_auth_pepper', encode(gen_random_bytes(32), 'hex')
WHERE NOT EXISTS (
  SELECT 1 FROM public.app_secrets WHERE key = 'huawei_auth_pepper'
);

-- ── 2. huawei_user_id_by_email ─────────────────────────────────────────
-- SECURITY DEFINER so the service role can look up auth.users through RPC.
-- Restricted to the internal @huawei-id.local namespace: it can never be
-- used to probe real email addresses, and EXECUTE is revoked from client
-- roles anyway (the Edge Function calls it with the service-role key).
CREATE OR REPLACE FUNCTION public.huawei_user_id_by_email(p_email text)
RETURNS uuid
LANGUAGE sql
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT id FROM auth.users
  WHERE email = lower(p_email)
    AND lower(p_email) LIKE 'hw\_%@huawei-id.local'
  LIMIT 1;
$$;

REVOKE ALL ON FUNCTION public.huawei_user_id_by_email(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.huawei_user_id_by_email(text) FROM anon, authenticated;

COMMENT ON FUNCTION public.huawei_user_id_by_email(text) IS
  'huawei-auth Edge Function helper: resolve internal hw_*@huawei-id.local address to auth user id. Service-role only.';
