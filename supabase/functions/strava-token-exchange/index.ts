// strava-token-exchange — server-side Strava OAuth2 token broker for the
// Mindset Frames Android app.
//
// Why this exists: the Strava client secret must NEVER ship inside the APK.
// The app sends either an authorization code (first connect) or a refresh
// token (renewal); this function calls Strava's token endpoint with the
// STRAVA_CLIENT_ID / STRAVA_CLIENT_SECRET read from encrypted Edge Function
// secrets and returns only the token triple the app needs.
//
// Deploy:  supabase functions deploy strava-token-exchange
// Secrets: supabase secrets set STRAVA_CLIENT_ID=xxxxx STRAVA_CLIENT_SECRET=yyyyy
//
// Request  (POST, JSON):
//   { "grantType": "authorization_code", "code": "<code from redirect>" }
//   { "grantType": "refresh_token", "refreshToken": "<stored refresh token>" }
// Response (200, JSON):
//   { "access_token": "...", "refresh_token": "...", "expires_at": 1735689600 }
//
// The function never logs tokens or the client secret.

const STRAVA_TOKEN_URL = "https://www.strava.com/oauth/token";

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

interface ExchangeBody {
  grantType?: string;
  code?: string;
  refreshToken?: string;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return json(405, { error: "Method not allowed" });
  }

  const clientId = Deno.env.get("STRAVA_CLIENT_ID");
  const clientSecret = Deno.env.get("STRAVA_CLIENT_SECRET");
  if (!clientId || !clientSecret) {
    console.error("strava-token-exchange: missing STRAVA_CLIENT_ID / STRAVA_CLIENT_SECRET secrets");
    return json(500, { error: "Strava integration is not configured" });
  }

  let body: ExchangeBody;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "Invalid JSON body" });
  }

  const params = new URLSearchParams({
    client_id: clientId,
    client_secret: clientSecret,
  });

  if (body.grantType === "authorization_code") {
    const code = body.code?.trim();
    if (!code) return json(400, { error: "Missing code" });
    params.set("grant_type", "authorization_code");
    params.set("code", code);
  } else if (body.grantType === "refresh_token") {
    const refreshToken = body.refreshToken?.trim();
    if (!refreshToken) return json(400, { error: "Missing refreshToken" });
    params.set("grant_type", "refresh_token");
    params.set("refresh_token", refreshToken);
  } else {
    return json(400, { error: "grantType must be authorization_code or refresh_token" });
  }

  try {
    const stravaResponse = await fetch(STRAVA_TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: params.toString(),
    });

    if (!stravaResponse.ok) {
      // Do not forward Strava's raw body — it can echo request params.
      console.error(`strava-token-exchange: Strava returned ${stravaResponse.status}`);
      return json(stravaResponse.status === 400 ? 400 : 502, {
        error: "Strava token exchange failed",
      });
    }

    const data = await stravaResponse.json();
    if (!data.access_token || !data.refresh_token || !data.expires_at) {
      console.error("strava-token-exchange: unexpected Strava payload shape");
      return json(502, { error: "Unexpected response from Strava" });
    }

    // Return ONLY what the app needs — athlete profile data is dropped.
    return json(200, {
      access_token: data.access_token,
      refresh_token: data.refresh_token,
      expires_at: data.expires_at,
    });
  } catch (e) {
    console.error("strava-token-exchange: network failure", e instanceof Error ? e.message : e);
    return json(502, { error: "Could not reach Strava" });
  }
});
