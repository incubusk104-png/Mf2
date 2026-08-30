-- Server-side record of Huawei IAP tip purchases (consumables).
-- Written exclusively by the tip-purchase Edge Function using the service
-- role; RLS with no policies means clients can neither read nor write rows
-- directly.

CREATE TABLE IF NOT EXISTS public.tip_purchases (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  -- Huawei purchase token — the dedup key: replaying the same signed
  -- payload can never create a second row.
  purchase_token text NOT NULL UNIQUE,
  product_id text NOT NULL CHECK (product_id IN ('tip_small', 'tip_medium', 'tip_large')),
  order_id text,
  -- Supabase auth user id (or device id) when the tipper was signed in.
  user_identifier text,
  -- inAppDataSignature captured for later re-verification / audit.
  signature text,
  -- True when Huawei's Order Service confirmed the purchase server-side.
  verified boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.tip_purchases ENABLE ROW LEVEL SECURITY;
-- No policies: service-role-only access.

CREATE INDEX IF NOT EXISTS idx_tip_purchases_user
  ON public.tip_purchases (user_identifier)
  WHERE user_identifier IS NOT NULL;

COMMENT ON TABLE public.tip_purchases IS
  'Huawei IAP tip (consumable) purchases recorded by the tip-purchase Edge Function.';
