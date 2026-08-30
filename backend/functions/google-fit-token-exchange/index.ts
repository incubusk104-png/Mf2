// google-fit-token-exchange — server-side Google OAuth2 token broker for the
// Mindset Frames Android app.
//
// Why this exists: the Google client secret must NEVER ship inside the APK.
// The app sends either an authorization code (first connect) or a refresh
// token (renewal); this function calls Google's token endpoint with the
// GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET read from encrypted Edge Function
// secrets and returns only the token triple the app needs.
//
// The user MUST authenticate with their Google account BEFORE any data sync.
// This function ONLY handles token exchange — it does not fetch any user data.
//
// Deploy:  supabase functions deploy google-fit-token-exchange
// Secrets: supabase secrets set GOOGLE_CLIENT_ID=xxxxx GOOGLE_CLIENT_SECRET=yyyyy
//
// Request  (POST, JSON):
//   { "grantType": "authorization_code", "code": "<code>", "redirectUri": "<uri>" }
//   { "grantType": "refresh_token", "refreshToken": "<stored refresh token>" }
// Response (200, JSON):
//   { "access_token": "...", "refresh_token": "...", "expires_in": 3600, "scope": "..." }
//
// The function never logs tokens or the client secret.

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

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
  redirectUri?: string;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return json(405, { error: "Method not allowed" });
  }

  const clientId = Deno.env.get("GOOGLE_CLIENT_ID");
  const clientSecret = Deno.env.get("GOOGLE_CLIENT_SECRET");
  if (!clientId || !clientSecret) {
    console.error(
      "google-fit-token-exchange: missing GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET secrets"
    );
    return json(500, { error: "Google Fit integration is not configured" });
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
    const redirectUri = body.redirectUri?.trim();
    if (!redirectUri) return json(400, { error: "Missing redirectUri" });
    params.set("grant_type", "authorization_code");
    params.set("code", code);
    params.set("redirect_uri", redirectUri);
  } else if (body.grantType === "refresh_token") {
    const refreshToken = body.refreshToken?.trim();
    if (!refreshToken) return json(400, { error: "Missing refreshToken" });
    params.set("grant_type", "refresh_token");
    params.set("refresh_token", refreshToken);
  } else {
    return json(400, {
      error: "grantType must be authorization_code or refresh_token",
    });
  }

  try {
    const googleResponse = await fetch(GOOGLE_TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: params.toString(),
    });

    if (!googleResponse.ok) {
      console.error(
        `google-fit-token-exchange: Google returned ${googleResponse.status}`
      );
      return json(googleResponse.status === 400 ? 400 : 502, {
        error: "Google token exchange failed",
      });
    }

    const data = await googleResponse.json();
    if (!data.access_token) {
      console.error(
        "google-fit-token-exchange: unexpected Google payload shape"
      );
      return json(502, { error: "Unexpected response from Google" });
    }

    // Return ONLY what the app needs
    return json(200, {
      access_token: data.access_token,
      refresh_token: data.refresh_token ?? null,
      expires_in: data.expires_in ?? 3600,
      scope: data.scope ?? "",
      token_type: data.token_type ?? "Bearer",
    });
  } catch (e) {
    console.error(
      "google-fit-token-exchange: network failure",
      e instanceof Error ? e.message : e
    );
    return json(502, { error: "Could not reach Google" });
  }
});
