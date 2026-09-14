import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

/**
 * Founding-member cap: 100 slots, GLOBAL.
 *
 * The cap is on the whole table — `SELECT count(*) FROM founding_member_claims`
 * with no `country` filter. `country` is stored per claim for reporting only and
 * never gates anything, so there is no per-country quota and no 500-slot tier.
 */
const MAX_CLAIMS = 100;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

interface ClaimBody {
  user_id?: string;
  country?: string;
  plan_id?: string;
}

function adminClient() {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("Missing Supabase credentials");
  return createClient(url, key);
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function getClaimCount(supabase: ReturnType<typeof adminClient>): Promise<number> {
  const { count, error } = await supabase
    .from("founding_member_claims")
    .select("*", { count: "exact", head: true });
  if (error) throw error;
  return count ?? 0;
}

async function hasClaimed(
  supabase: ReturnType<typeof adminClient>,
  userId: string,
): Promise<boolean> {
  const { data, error } = await supabase
    .from("founding_member_claims")
    .select("id")
    .eq("user_identifier", userId)
    .maybeSingle();
  if (error) throw error;
  return data != null;
}

interface AtomicClaimResult {
  ok?: boolean;
  claimed?: boolean;
  charged?: boolean;
  remaining?: number;
}

/**
 * Records a claim through the `claim_founding_member` SQL function, which does
 * the cap check and the insert in ONE transaction under a transaction-scoped
 * advisory lock.
 *
 * This replaces the previous count-then-insert pair, which was not atomic: two
 * requests arriving together at 99/100 could both read "99 < 100" and both
 * insert, landing the table at 101. The `user_identifier UNIQUE` constraint
 * only ever stopped the SAME user double-claiming; two different users racing
 * the last slot have distinct identifiers, so it never fired for them.
 */
async function claimFoundingMember(
  supabase: ReturnType<typeof adminClient>,
  userId: string,
  country: string,
  planId: string,
): Promise<AtomicClaimResult> {
  const { data, error } = await supabase.rpc("claim_founding_member", {
    p_user_id: userId,
    p_country: country || "",
    p_plan_id: planId || "",
    p_max_claims: MAX_CLAIMS,
  });
  if (error) throw error;
  return (data ?? {}) as AtomicClaimResult;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const supabase = adminClient();

    if (req.method === "GET") {
      const url = new URL(req.url);
      const userId = url.searchParams.get("user_id")?.trim();
      if (!userId) {
        return json({ error: "Missing user_id" }, 400);
      }
      // Display-only read. The authoritative gate is the atomic claim below —
      // this only decides whether the card is worth rendering at all.
      const [claimed, total] = await Promise.all([
        hasClaimed(supabase, userId),
        getClaimCount(supabase),
      ]);
      const remaining = Math.max(0, MAX_CLAIMS - total);
      const eligible = !claimed && remaining > 0;
      return json({ eligible, claimed, remaining });
    }

    if (req.method === "POST") {
      const body = (await req.json()) as ClaimBody;
      const userId = body.user_id?.trim();
      if (!userId) {
        return json({ error: "Missing user_id" }, 400);
      }

      const result = await claimFoundingMember(
        supabase,
        userId,
        body.country ?? "",
        body.plan_id ?? "",
      );

      const claimed = result.claimed === true;
      return json({
        // `eligible` stays false on a successful claim: the caller has now
        // consumed their slot, so they are no longer eligible to claim again.
        // Only a repeat call for an already-recorded claim reports it back.
        eligible: false,
        claimed,
        remaining: typeof result.remaining === "number"
          ? Math.max(0, result.remaining)
          : 0,
        // True only when THIS call consumed a slot (not when it re-read an
        // existing claim). Lets the client tell "recorded now" from "already
        // recorded", which is what makes a retry safe.
        charged: result.charged === true,
      });
    }

    return json({ error: "Method not allowed" }, 405);
  } catch (err) {
    console.error("founding-member-eligibility error", err);
    return json({ error: "Internal server error" }, 500);
  }
});
