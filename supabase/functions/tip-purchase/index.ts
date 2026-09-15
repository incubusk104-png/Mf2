// tip-purchase — server-side verification + recording of Huawei IAP tip
// purchases (consumables: tip_small, tip_medium, tip_large) for the
// Mindset Frames Android app.
//
// Why this exists: the app's TipBilling flow completes entirely on-device,
// so a tampered client could claim a tip that was never paid. This function
// receives the signed inAppPurchaseData from the app, verifies it with
// Huawei's Order Service (when HUAWEI_IAP_CLIENT_ID / HUAWEI_IAP_CLIENT_SECRET
// secrets are configured), and records the purchase in public.tip_purchases
// with the purchaseToken as the dedup key. Recording still happens (marked
// unverified) when the Huawei secrets are absent, so tips are never lost.
//
// NOTE ON THE SHARED VERIFIER: the Huawei Order Service call lives in
// ../_shared/huaweiOrder.ts, because founding-member-eligibility needs the
// exact same "did Huawei really take money for this?" answer — and a slot,
// unlike a tip, must never be granted on an unverified purchase. One
// implementation means the two can never drift into disagreeing about what a
// verified purchase is.
//
// Deploy:  supabase functions deploy tip-purchase --no-verify-jwt
// Secrets (optional but recommended):
//   supabase secrets set HUAWEI_IAP_CLIENT_ID=<AGC OAuth client id> \
//                        HUAWEI_IAP_CLIENT_SECRET=<AGC OAuth client secret>
//   (AppGallery Connect -> your project -> Project settings -> App information
//    -> OAuth 2.0 client ID. Same credentials used for other AGC server APIs.)
//
// Request  (POST, JSON):
//   {
//     "purchaseData": "<inAppPurchaseData JSON string from IAP>",
//     "signature":   "<inAppDataSignature>",          // optional, stored
//     "userId":      "<supabase user id or device id>" // optional
//   }
// Response (200, JSON):
//   { "recorded": true, "verified": true|false, "productId": "tip_small" }
//
// The function never logs tokens or the client secret.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { verifyHuaweiOrder } from "../_shared/huaweiOrder.ts";

const KNOWN_TIP_PRODUCTS = new Set(["tip_small", "tip_medium", "tip_large"]);

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function adminClient() {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("Missing Supabase credentials");
  return createClient(url, key);
}

interface TipBody {
  purchaseData?: string;
  signature?: string;
  userId?: string;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return json(405, { error: "Method not allowed" });
  }

  let body: TipBody;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "Invalid JSON body" });
  }

  const purchaseData = body.purchaseData?.trim();
  if (!purchaseData || purchaseData.length > 16384) {
    return json(400, { error: "purchaseData is required" });
  }

  // Parse the signed purchase payload from the device.
  let productId = "";
  let purchaseToken = "";
  let orderId = "";
  try {
    const parsed = JSON.parse(purchaseData);
    productId = typeof parsed.productId === "string" ? parsed.productId : "";
    purchaseToken = typeof parsed.purchaseToken === "string" ? parsed.purchaseToken : "";
    orderId = typeof parsed.orderId === "string" ? parsed.orderId : "";
  } catch {
    return json(400, { error: "purchaseData is not valid JSON" });
  }

  if (!purchaseToken) return json(400, { error: "purchaseData has no purchaseToken" });
  if (!KNOWN_TIP_PRODUCTS.has(productId)) {
    return json(400, { error: `Unknown tip product: ${productId}` });
  }

  // Server-side verification.
  //
  // Three states, and a tip deliberately treats them differently from a
  // founding slot: a tip is not scarce, so "could not verify" is recorded as an
  // unverified tip rather than discarded — the user did pay. A definitive
  // refusal from Huawei ("rejected"), however, is never recorded at all.
  const verification = await verifyHuaweiOrder(purchaseToken, productId);
  if (verification.status === "rejected") {
    return json(402, {
      recorded: false,
      verified: false,
      error: "Huawei rejected this purchase",
      detail: verification.reason,
    });
  }
  const verified = verification.status === "verified";

  // Prefer the order id HUAWEI reported over the one in the client's payload:
  // the client's is unverified, and order_id is the audit handle for this row.
  const authoritativeOrderId = verification.order?.orderId || orderId;

  try {
    const supabase = adminClient();
    const { error } = await supabase.from("tip_purchases").upsert(
      {
        purchase_token: purchaseToken,
        product_id: productId,
        order_id: authoritativeOrderId || null,
        user_identifier: body.userId?.trim().slice(0, 128) || null,
        signature: body.signature?.trim().slice(0, 4096) || null,
        verified,
      },
      { onConflict: "purchase_token" },
    );
    if (error) {
      console.error("tip-purchase: insert failed:", error.message);
      return json(500, { error: "Could not record the tip" });
    }
  } catch (e) {
    console.error("tip-purchase:", e instanceof Error ? e.message : e);
    return json(500, { error: "Could not record the tip" });
  }

  return json(200, {
    recorded: true,
    verified,
    productId,
  });
});
