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

const HUAWEI_TOKEN_URL = "https://oauth-login.cloud.huawei.com/oauth2/v3/token";
// Site-specific order-service roots. Verification is attempted against each
// until one answers (Huawei routes by the developer account's site).
const ORDER_VERIFY_URLS = [
  "https://orders-drcn.iap.cloud.huawei.com.cn/applications/purchases/tokens/verify",
  "https://orders-drcn.iap.hicloud.com/applications/purchases/tokens/verify",
  "https://orders-dre.iap.hicloud.com/applications/purchases/tokens/verify",
  "https://orders-dra.iap.hicloud.com/applications/purchases/tokens/verify",
  "https://orders-drru.iap.hicloud.com/applications/purchases/tokens/verify",
];

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

/** App-level access token from Huawei's OAuth server (client_credentials). */
async function getHuaweiAccessToken(
  clientId: string,
  clientSecret: string,
): Promise<string | null> {
  try {
    const response = await fetch(HUAWEI_TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        client_id: clientId,
        client_secret: clientSecret,
      }),
    });
    if (!response.ok) {
      console.warn(`tip-purchase: Huawei token endpoint returned ${response.status}`);
      return null;
    }
    const data = await response.json();
    return typeof data.access_token === "string" ? data.access_token : null;
  } catch (e) {
    console.warn(
      "tip-purchase: Huawei token endpoint unreachable:",
      e instanceof Error ? e.message : e,
    );
    return null;
  }
}

/**
 * Verifies a purchase token with Huawei's Order Service. Returns true when
 * Huawei confirms the order, false when Huawei rejects it, and null when
 * verification could not be performed (network / not configured).
 */
async function verifyWithHuawei(
  purchaseToken: string,
  productId: string,
): Promise<boolean | null> {
  const clientId = Deno.env.get("HUAWEI_IAP_CLIENT_ID");
  const clientSecret = Deno.env.get("HUAWEI_IAP_CLIENT_SECRET");
  if (!clientId || !clientSecret) return null; // not configured — best effort

  const accessToken = await getHuaweiAccessToken(clientId, clientSecret);
  if (!accessToken) return null;

  // Huawei requires: Authorization: Basic base64("APPAT:" + accessToken)
  const authHeader = `Basic ${btoa(`APPAT:${accessToken}`)}`;

  for (const url of ORDER_VERIFY_URLS) {
    try {
      const response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json; charset=UTF-8",
          Authorization: authHeader,
        },
        body: JSON.stringify({ purchaseToken, productId }),
      });
      if (!response.ok) continue; // wrong site root — try the next one
      const data = await response.json();
      // responseCode "0" = success; purchaseTokenData carries the order state.
      if (data.responseCode === "0" && typeof data.purchaseTokenData === "string") {
        const tokenData = JSON.parse(data.purchaseTokenData);
        // purchaseState 0 = purchased
        return tokenData.purchaseState === 0;
      }
      if (data.responseCode && data.responseCode !== "0") {
        console.warn(`tip-purchase: Huawei rejected the token (rc=${data.responseCode})`);
        return false;
      }
    } catch {
      // network issue with this root — try the next
    }
  }
  return null;
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

  // Server-side verification (best effort — see header comment).
  const verified = await verifyWithHuawei(purchaseToken, productId);
  if (verified === false) {
    return json(402, { recorded: false, verified: false, error: "Huawei rejected this purchase" });
  }

  try {
    const supabase = adminClient();
    const { error } = await supabase.from("tip_purchases").upsert(
      {
        purchase_token: purchaseToken,
        product_id: productId,
        order_id: orderId || null,
        user_identifier: body.userId?.trim().slice(0, 128) || null,
        signature: body.signature?.trim().slice(0, 4096) || null,
        verified: verified === true,
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
    verified: verified === true,
    productId,
  });
});
