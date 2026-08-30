// polar-token-exchange — server-side Polar AccessLink OAuth2 token broker
// for the Mindset Frames Android app.
//
// Why this exists: Polar's token endpoint requires HTTP Basic auth with
// client_id:client_secret. Shipping the client secret inside the APK is a
// security risk (it can be extracted from any decompiled build). The app now
// sends only the authorization code here; this function performs the Basic-
// auth exchange with POLAR_CLIENT_ID / POLAR_CLIENT_SECRET read from
// encrypted Edge Function secrets and returns only the token pair the app
// needs.
//
// Deploy:  supabase functions deploy polar-token-exchange
// Secrets: supabase secrets set POLAR_CLIENT_ID=xxxxx POLAR_CLIENT_SECRET=yyyyy
//
// Request  (POST, JSON):
//   { "code": "<authorization code from mindsetframes://polar-callback>" }
// Response (200, JSON):
//   { "access_token": "...", "x_user_id": 12345678, "expires_in": 473040000 }
//
// Request  (GET): returns the PUBLIC OAuth client id so app builds that were
// compiled without the POLAR_CLIENT_ID build secret can still start the
// OAuth flow (fixes "Polar isn't configured for this build yet"). The
// client id is a public identifier — it appears in every OAuth URL — so
// exposing it here leaks nothing. The client SECRET is never returned.
//   { "client_id": "xxxxx" }
//
// The function never logs tokens or the client secret.

const POLAR_TOKEN_URL = "https://polarremote.com/v2/oauth2/token";
const REDIRECT_URI = "mindsetframes://polar-callback";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method === "GET") {
    // Public client id discovery — lets the app start the OAuth consent
    // flow even when the APK was built without POLAR_CLIENT_ID baked in.
    const clientId = Deno.env.get("POLAR_CLIENT_ID");
    if (!clientId) {
      return json(500, { error: "Polar integration is not configured" });
    }
    return json(200, { client_id: clientId });
  }

  if (req.method !== "POST") {
    return json(405, { error: "Method not allowed" });
  }

  const clientId = Deno.env.get("POLAR_CLIENT_ID");
  const clientSecret = Deno.env.get("POLAR_CLIENT_SECRET");
  if (!clientId || !clientSecret) {
    console.error(
      "polar-token-exchange: missing POLAR_CLIENT_ID / POLAR_CLIENT_SECRET secrets",
    );
    return json(500, { error: "Polar integration is not configured" });
  }

  let body: { code?: string };
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "Invalid JSON body" });
  }

  const code = body.code?.trim();
  if (!code) return json(400, { error: "Missing code" });

  const params = new URLSearchParams({
    grant_type: "authorization_code",
    code,
    redirect_uri: REDIRECT_URI,
  });

  try {
    const polarResponse = await fetch(POLAR_TOKEN_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        Accept: "application/json",
        Authorization: `Basic ${btoa(`${clientId}:${clientSecret}`)}`,
      },
      body: params.toString(),
    });

    if (!polarResponse.ok) {
      // Do not forward Polar's raw body — it can echo request params.
      console.error(
        `polar-token-exchange: Polar returned ${polarResponse.status}`,
      );
      return json(polarResponse.status === 400 ? 400 : 502, {
        error: "Polar token exchange failed",
      });
    }

    const data = await polarResponse.json();
    if (!data.access_token) {
      console.error("polar-token-exchange: unexpected Polar payload shape");
      return json(502, { error: "Unexpected response from Polar" });
    }

    // Return ONLY what the app needs.
    return json(200, {
      access_token: data.access_token,
      x_user_id: data.x_user_id ?? null,
      expires_in: data.expires_in ?? null,
    });
  } catch (e) {
    console.error(
      "polar-token-exchange: network failure",
      e instanceof Error ? e.message : e,
    );
    return json(502, { error: "Could not reach Polar" });
  }
});
