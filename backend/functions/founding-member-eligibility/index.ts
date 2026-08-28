import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

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

async function recordClaim(
  supabase: ReturnType<typeof adminClient>,
  userId: string,
  country: string,
  planId: string,
): Promise<void> {
  const { error } = await supabase.from("founding_member_claims").insert({
    user_identifier: userId,
    country: country || "",
    plan_id: planId || "",
  });
  if (error) throw error;
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
        return new Response(JSON.stringify({ error: "Missing user_id" }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
      const [claimed, total] = await Promise.all([
        hasClaimed(supabase, userId),
        getClaimCount(supabase),
      ]);
      const remaining = Math.max(0, MAX_CLAIMS - total);
      const eligible = !claimed && remaining > 0;
      return new Response(
        JSON.stringify({ eligible, claimed, remaining }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } },
      );
    }

    if (req.method === "POST") {
      const body = (await req.json()) as ClaimBody;
      const userId = body.user_id?.trim();
      if (!userId) {
        return new Response(JSON.stringify({ error: "Missing user_id" }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
      const [claimed, total] = await Promise.all([
        hasClaimed(supabase, userId),
        getClaimCount(supabase),
      ]);
      if (claimed || total >= MAX_CLAIMS) {
        return new Response(
          JSON.stringify({ eligible: false, claimed: true, remaining: Math.max(0, MAX_CLAIMS - total) }),
          { headers: { ...corsHeaders, "Content-Type": "application/json" } },
        );
      }
      await recordClaim(supabase, userId, body.country ?? "", body.plan_id ?? "");
      return new Response(
        JSON.stringify({ eligible: false, claimed: true, remaining: Math.max(0, MAX_CLAIMS - total - 1) }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } },
      );
    }

    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err) {
    console.error("founding-member-eligibility error", err);
    return new Response(JSON.stringify({ error: "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
