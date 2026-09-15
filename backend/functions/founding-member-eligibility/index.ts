import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { verifyHuaweiOrder } from "../_shared/huaweiOrder.ts";

/**
 * Founding-member cap: 500 slots PER COUNTRY / REGION.
 *
 * The cap used to be 100 and GLOBAL — one count over the whole table, with
 * `country` stored for reporting only. It is now scoped: every country/region
 * has its own 500-slot target, and only the caller's own region is counted, so
 * one region selling out does not affect any other.
 *
 * The region is DERIVED from the caller's country, never stored separately.
 * That keeps existing rows — written under the old scheme, with whatever
 * spelling of the country the client happened to send — counting against the
 * right bucket instead of needing a backfill.
 *
 * The authoritative gate is still the atomic claim in the SQL function; the GET
 * branch here is a display-only read that decides whether the Founding Member
 * card is worth rendering at all.
 *
 * NOTE: MAX_CLAIMS is the FALLBACK default only. The cap actually used is
 * resolved per region from founding_member_cap_for_region(); MAX_CLAIMS is what
 * a region falls back to when that lookup is unavailable, and it matches the
 * migration's own DEFAULT 500.
 */
const MAX_CLAIMS = 500;

/**
 * The only plan ids that may consume a founding slot.
 *
 * This is an ALLOW-LIST, deliberately server-side: `plan_id` arrives from the
 * client, so without it a caller could claim a founding slot while naming any
 * plan at all. Anything not in this set is refused before Huawei is even
 * consulted, which also keeps a non-founding purchase from burning a slot.
 */
const FOUNDING_PLAN_IDS = new Set([
  "mindset_premium_founding_monthly",
  "mindset_premium_founding_yearly",
]);

/** Region bucket for anything unresolvable. 'ZZ' is ISO-3166's unknown value. */
const UNKNOWN_REGION = "ZZ";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

interface ClaimBody {
  user_id?: string;
  country?: string;
  plan_id?: string;
  /**
   * The signed purchase, straight from Huawei's IAP result. Required: the slot
   * is only consumed after these are verified against Huawei's Order Service.
   * `purchaseData` is the `inAppPurchaseData` JSON string and `signature` is
   * the `inAppDataSignature` that accompanies it.
   */
  purchase_data?: string;
  signature?: string;
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

/**
 * Normalizes a country into its region bucket: uppercased, trimmed, and folded
 * to 'ZZ' when it is missing or blank.
 *
 * This is a deliberate MIRROR of the SQL function founding_member_region(). The
 * two must agree exactly, or a caller could be shown the card for one bucket
 * and have the claim counted against another. The Android
 * SupabaseSync.currentRegion() carries the same rule a third time; keep all
 * three in step.
 */
function regionFor(country: string | null | undefined): string {
  const trimmed = (country ?? "").trim().toUpperCase();
  return trimmed === "" ? UNKNOWN_REGION : trimmed;
}

/**
 * Per-region slot count. Uses the SQL helper rather than the query builder
 * because the region is a function of `country`, which the builder cannot
 * filter on. Falls back to a bounded count if the helper is missing, so a
 * deploy where the migration has not landed yet degrades instead of failing.
 */
async function getRegionClaimCount(
  supabase: ReturnType<typeof adminClient>,
  region: string,
): Promise<number> {
  const { data, error } = await supabase.rpc("founding_member_region_count", {
    p_country: region,
  });
  if (!error && typeof data === "number") return data;

  // Pre-migration fallback. Filtering on `country = region` is not exactly the
  // helper's semantics — it misses rows stored with different casing or
  // spacing — but it is close enough for a display-only read and keeps the
  // endpoint usable. The POST path never takes this branch: it claims through
  // the SQL function, which normalizes properly.
  const { count, error: countError } = await supabase
    .from("founding_member_claims")
    .select("*", { count: "exact", head: true })
    .eq("country", region);
  if (countError) throw countError;
  return count ?? 0;
}

/**
 * Resolves the slot target for a region from the cap table, falling back to the
 * default when there is no override (or no table). Kept per-region on purpose:
 * a single country can be given a different target without a code change.
 */
async function getRegionCap(
  supabase: ReturnType<typeof adminClient>,
  region: string,
): Promise<number> {
  const { data, error } = await supabase.rpc("founding_member_cap_for_region", {
    p_country: region,
  });
  if (error) return MAX_CLAIMS;
  const parsed = typeof data === "number" ? data : Number(data);
  return Number.isFinite(parsed) && parsed >= 1 ? parsed : MAX_CLAIMS;
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
  reason?: string;
  region?: string;
  remaining?: number;
}

/**
 * Records a claim through the `claim_founding_member` SQL function, which does
 * the per-region cap check and the insert in ONE transaction under a
 * transaction-scoped advisory lock keyed on the region.
 *
 * The lock is what makes the cap hold under concurrency: two requests arriving
 * together at 499/500 in the same country queue on the same key, so the second
 * re-reads the count after the first has inserted and correctly sees the slot
 * as gone. `user_identifier UNIQUE` alone never covered this — it only stops
 * the SAME user double-claiming, and two different users racing the last slot
 * have distinct identifiers, so it never fires for them.
 *
 * The proof arguments are what stop a slot being consumed without payment. They
 * are passed straight through from the Huawei verification above and are the
 * ONLY source the SQL function trusts; the client's own assertion of success is
 * never forwarded.
 */
async function claimFoundingMember(
  supabase: ReturnType<typeof adminClient>,
  userId: string,
  country: string,
  planId: string,
  maxClaims: number,
  proof: {
    verified: boolean;
    orderId: string | null;
    purchaseToken: string | null;
    purchaseState: number | null;
  },
): Promise<AtomicClaimResult> {
  const { data, error } = await supabase.rpc("claim_founding_member", {
    p_user_id: userId,
    p_country: country || "",
    p_plan_id: planId || "",
    p_max_claims: maxClaims,
    p_verified: proof.verified,
    p_order_id: proof.orderId,
    p_purchase_token: proof.purchaseToken,
    p_purchase_state: proof.purchaseState,
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

      // The caller's country comes from the query string. It matters: without
      // it every caller would land in the 'ZZ' bucket, so a user in a sold-out
      // country would be told a slot is still free. `region` is echoed back so
      // the client can confirm which bucket it was measured against.
      const region = regionFor(url.searchParams.get("country"));

      // Display-only read. The authoritative gate is the atomic claim below.
      const [claimed, total, cap] = await Promise.all([
        hasClaimed(supabase, userId),
        getRegionClaimCount(supabase, region),
        getRegionCap(supabase, region),
      ]);
      const remaining = Math.max(0, cap - total);
      const eligible = !claimed && remaining > 0;
      return json({
        eligible,
        claimed,
        remaining,
        cap,
        region,
        // Convenience for the client's copy: true once this region's target is
        // met. This is a snapshot — between this read and the POST another
        // claim can take the last slot, which is exactly why the POST result,
        // not this flag, decides whether the purchase counted.
        soldOut: remaining <= 0,
      });
    }

    if (req.method === "POST") {
      const body = (await req.json()) as ClaimBody;
      const userId = body.user_id?.trim();
      if (!userId) {
        return json({ error: "Missing user_id" }, 400);
      }

      // Resolve the cap for the claimer's own region so the value handed to the
      // atomic function matches what the GET branch reported.
      const region = regionFor(body.country);
      const cap = await getRegionCap(supabase, region);

      const planId = (body.plan_id ?? "").trim();

      // ── Gate 1: is this even a founding plan? ──────────────────────────────
      // Refused before any network call, so a non-founding purchase can never
      // burn a slot and an unknown plan id is not reported as a payment problem.
      if (!FOUNDING_PLAN_IDS.has(planId)) {
        return json(
          {
            eligible: false,
            claimed: false,
            charged: false,
            reason: "not_a_founding_plan",
            region,
            cap,
          },
          400,
        );
      }

      const purchaseData = body.purchase_data?.trim();
      if (!purchaseData || purchaseData.length > 24576) {
        return json(
          {
            eligible: false,
            claimed: false,
            charged: false,
            reason: "missing_purchase_data",
            region,
            cap,
          },
          400,
        );
      }

      // The product inside the signed payload must also be a founding plan —
      // the client-supplied plan_id and the payload can disagree, and Huawei's
      // copy is the one that counts.
      let productId = "";
      try {
        const parsed = JSON.parse(purchaseData);
        productId = typeof parsed.productId === "string" ? parsed.productId : "";
      } catch {
        return json({ error: "purchase_data is not valid JSON" }, 400);
      }
      if (!FOUNDING_PLAN_IDS.has(productId)) {
        return json(
          {
            eligible: false,
            claimed: false,
            charged: false,
            reason: "payload_not_a_founding_plan",
            region,
            cap,
          },
          400,
        );
      }

      // ── Gate 2: did Huawei actually take the money? ────────────────────────
      // This is the whole point of the endpoint. The on-device
      // SubscriptionResult.Success is NOT evidence: it is produced locally from
      // the purchase-result Intent, and it is also produced from
      // ORDER_PRODUCT_OWNED, which can carry an empty payload. So the signed
      // purchase is replayed against Huawei's Order Service and only a
      // completed, purchased order is accepted.
      const verification = await verifyHuaweiOrder(
        // The token is read from the payload Huawei signed, never from a
        // separate client field that could be substituted.
        (() => {
          try {
            const parsed = JSON.parse(purchaseData);
            return typeof parsed.purchaseToken === "string" ? parsed.purchaseToken : "";
          } catch {
            return "";
          }
        })(),
        productId,
      );

      if (verification.status !== "verified" || !verification.order) {
        // "rejected" and "unavailable" are different diagnoses but the same
        // outcome for a scarce slot: fail CLOSED. Consuming a slot we could not
        // verify would permanently sell something we may never have been paid
        // for, and a slot cannot be given back.
        return json(
          {
            eligible: false,
            claimed: false,
            charged: false,
            reason: verification.status === "rejected"
              ? "purchase_rejected"
              : "verification_unavailable",
            detail: verification.reason,
            region,
            cap,
          },
          verification.status === "rejected" ? 402 : 503,
        );
      }

      const result = await claimFoundingMember(
        supabase,
        userId,
        body.country ?? "",
        planId,
        cap,
        {
          verified: true,
          orderId: verification.order.orderId || null,
          purchaseToken: verification.order.purchaseToken || null,
          purchaseState: verification.order.purchaseState,
        },
      );

      const claimed = result.claimed === true;
      const charged = result.charged === true;
      return json({
        // `eligible` stays false on a successful claim: the caller has now
        // consumed their slot, so they are no longer eligible to claim again.
        // Only a repeat call for an already-recorded claim reports it back.
        eligible: false,
        claimed,
        // `charged` is the field the client keys on: it is true ONLY when THIS
        // call consumed a slot, so a retry (or a rejected claim) never shows the
        // user a success message they did not earn.
        charged,
        reason: result.reason ?? (claimed ? "claimed" : "not_claimed"),
        remaining: typeof result.remaining === "number"
          ? Math.max(0, result.remaining)
          : 0,
        cap,
        region: result.region ?? region,
      });
    }

    return json({ error: "Method not allowed" }, 405);
  } catch (err) {
    console.error("founding-member-eligibility error", err);
    return json({ error: "Internal server error" }, 500);
  }
});