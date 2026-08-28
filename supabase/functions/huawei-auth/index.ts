// huawei-auth — verifies a HUAWEI ID sign-in server-side and returns a
// Supabase session for the Mindset Frames Android app.
//
// Security model (v2 — token-verified):
//  1. The app sends the ID token issued by HUAWEI Account Kit on-device.
//  2. This function asks Huawei's account server to verify it
//     (POST oauth2/v3/tokeninfo) and additionally checks issuer, audience
//     (this app's OAuth client ID) and expiry. A caller can NEVER mint a
//     session without a genuine, fresh Huawei credential for this exact app.
//  3. The internal account address is derived from the VERIFIED subject:
//     hw_<sha256(sub).slice(0, 24)>@huawei-id.local — the caller can never
//     choose the account it lands in, and the internal domain can never
//     collide with a real person's email.
//  4. The account password is derived with HMAC-SHA256 keyed by a random
//     pepper stored in a service-role-only table (public.app_secrets) —
//     nothing shipped inside the APK can derive or guess it.
//  5. The password is (re)set through the admin API on every verified
//     sign-in, then a normal password grant produces the session returned
//     to the app. Re-setting on each sign-in also self-heals accounts if
//     the derivation scheme or pepper ever rotates.
//
// The function never logs tokens, passwords, or the pepper.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const TOKENINFO_URL =
  "https://oauth-login.cloud.huawei.com/oauth2/v3/tokeninfo";

// OAuth 2.0 client ID of the Mindset Frames AppGallery app — a public
// identifier (agconnect-services.json oauth_client.client_id), NOT a secret.
// Tokens minted for any other app are rejected even if otherwise valid.
const EXPECTED_AUDIENCE = "118642709";

const EXPECTED_ISSUERS = new Set([
  "https://accounts.huawei.com",
  "accounts.huawei.com",
]);

const PASSWORD_CONTEXT = "huawei-pw-v2:";

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

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** base64url(HMAC-SHA256(pepper, context || subject)) truncated to 32 chars. */
async function derivePassword(pepper: string, subject: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(pepper),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(PASSWORD_CONTEXT + subject),
  );
  return btoa(String.fromCharCode(...new Uint8Array(mac)))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "")
    .slice(0, 32);
}

interface HuaweiClaims {
  iss?: string;
  aud?: string;
  sub?: string;
  exp?: number | string;
  email?: string;
  display_name?: string;
  name?: string;
}

/**
 * Verifies the Huawei ID token with Huawei's account server. Returns the
 * verified claims, or null when the token is invalid, expired, or minted
 * for a different app.
 */
async function verifyHuaweiIdToken(idToken: string): Promise<HuaweiClaims | null> {
  let response: Response;
  try {
    response = await fetch(TOKENINFO_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ id_token: idToken }),
    });
  } catch (error) {
    console.error(
      "huawei-auth: tokeninfo unreachable:",
      error instanceof Error ? error.message : "unknown",
    );
    return null;
  }
  if (!response.ok) {
    console.warn(`huawei-auth: tokeninfo rejected the token (${response.status})`);
    return null;
  }
  let claims: HuaweiClaims;
  try {
    claims = (await response.json()) as HuaweiClaims;
  } catch {
    return null;
  }

  const sub = typeof claims.sub === "string" ? claims.sub.trim() : "";
  const aud = typeof claims.aud === "string" ? claims.aud.trim() : "";
  const iss = typeof claims.iss === "string" ? claims.iss.trim() : "";
  const exp = Number(claims.exp ?? 0);

  if (!sub || sub.length > 256) return null;
  if (!EXPECTED_ISSUERS.has(iss)) {
    console.warn("huawei-auth: unexpected issuer");
    return null;
  }
  if (aud !== EXPECTED_AUDIENCE) {
    console.warn("huawei-auth: token was issued for a different app");
    return null;
  }
  if (!Number.isFinite(exp) || exp * 1000 < Date.now()) {
    console.warn("huawei-auth: token expired");
    return null;
  }
  return claims;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return json(405, { error: "Method not allowed" });
  }

  let body: { idToken?: unknown };
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "Invalid JSON body" });
  }

  const idToken = typeof body.idToken === "string" ? body.idToken.trim() : "";
  if (idToken.length < 32 || idToken.length > 8192) {
    return json(400, { error: "idToken is required" });
  }

  const claims = await verifyHuaweiIdToken(idToken);
  if (!claims) {
    return json(401, { error: "Huawei could not verify this sign-in" });
  }

  const subject = (claims.sub as string).trim();
  const identityEmail = `hw_${(await sha256Hex(subject)).slice(0, 24)}@huawei-id.local`;
  // Only Huawei-verified profile data is stored — never caller-supplied.
  const huaweiEmail =
    typeof claims.email === "string" ? claims.email.trim().slice(0, 320) : null;
  const displayName =
    typeof claims.display_name === "string" && claims.display_name.trim()
      ? claims.display_name.trim().slice(0, 120)
      : typeof claims.name === "string"
        ? claims.name.trim().slice(0, 120)
        : null;

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const admin = createClient(
    supabaseUrl,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { autoRefreshToken: false, persistSession: false } },
  );

  // Pepper lives only in the database (RLS + no grants = service-role only).
  const { data: secretRow, error: secretError } = await admin
    .from("app_secrets")
    .select("value")
    .eq("key", "huawei_auth_pepper")
    .single();
  if (secretError || !secretRow?.value) {
    console.error("huawei-auth: pepper unavailable:", secretError?.message);
    return json(500, { error: "Sign-in is temporarily unavailable" });
  }
  const password = await derivePassword(secretRow.value as string, subject);

  const userMetadata = {
    provider: "huawei",
    huawei_email: huaweiEmail,
    display_name: displayName,
  };

  // Find-or-create, then authoritatively (re)set the password. Because the
  // Huawei identity has been verified above, resetting is safe — and it
  // migrates any account created under an older derivation scheme.
  const { data: existingId, error: lookupError } = await admin.rpc(
    "huawei_user_id_by_email",
    { p_email: identityEmail },
  );
  if (lookupError) {
    console.error("huawei-auth: user lookup failed:", lookupError.message);
    return json(500, { error: "Sign-in is temporarily unavailable" });
  }

  if (existingId) {
    const { error } = await admin.auth.admin.updateUserById(
      existingId as string,
      { password, user_metadata: userMetadata },
    );
    if (error) {
      console.error("huawei-auth: password heal failed:", error.message);
      return json(500, { error: "Could not connect the account" });
    }
  } else {
    const { error } = await admin.auth.admin.createUser({
      email: identityEmail,
      password,
      email_confirm: true,
      user_metadata: userMetadata,
    });
    if (error) {
      // Race with a concurrent first sign-in: fall through to the heal path.
      const { data: racedId } = await admin.rpc("huawei_user_id_by_email", {
        p_email: identityEmail,
      });
      if (!racedId) {
        console.error("huawei-auth: createUser failed:", error.message);
        return json(500, { error: "Could not provision the account" });
      }
      const { error: healError } = await admin.auth.admin.updateUserById(
        racedId as string,
        { password, user_metadata: userMetadata },
      );
      if (healError) {
        console.error("huawei-auth: raced heal failed:", healError.message);
        return json(500, { error: "Could not connect the account" });
      }
    }
  }

  // Standard password grant so the app receives a first-class session.
  const anon = createClient(supabaseUrl, Deno.env.get("SUPABASE_ANON_KEY")!, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const { data: signIn, error: signInError } =
    await anon.auth.signInWithPassword({ email: identityEmail, password });
  if (signInError || !signIn.session) {
    console.error("huawei-auth: session grant failed:", signInError?.message);
    return json(500, { error: "Could not start the session" });
  }

  return json(200, {
    access_token: signIn.session.access_token,
    refresh_token: signIn.session.refresh_token,
    user: { id: signIn.session.user.id, email: identityEmail },
    // Verified profile info the app may show instead of the internal address.
    huawei_email: huaweiEmail,
    display_name: displayName,
  });
});
