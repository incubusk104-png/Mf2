CREATE TABLE IF NOT EXISTS founding_member_claims (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_identifier text NOT NULL UNIQUE,
  claimed_at timestamptz DEFAULT now(),
  country text,
  plan_id text
);

ALTER TABLE founding_member_claims ENABLE ROW LEVEL SECURITY;

-- This table is only written by the edge function using the service role key.
-- Direct client access is denied.
CREATE POLICY "Deny direct access to founding_member_claims"
  ON founding_member_claims
  FOR ALL
  USING (false);
