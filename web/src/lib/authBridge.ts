import { appDeepLink } from "@/lib/site";

/**
 * Supabase auth web-bridge.
 *
 * Supabase email links (confirm signup, magic link, recovery, email change)
 * land on this site with the result either in the URL fragment
 * (#access_token=…&refresh_token=…&type=signup — implicit flow) or in the
 * query string (?code=… PKCE, or ?error=…&error_code=otp_expired…).
 *
 * The bridge classifies the result, forwards every parameter to the app via
 * its custom scheme, and — critically — never lets the user get stuck:
 * tokens are scrubbed from the address bar immediately, the automatic
 * deep-link launch fires at most once per browser session, and a manual
 * "Open app" fallback appears whenever the launch can't be confirmed.
 */

export type BridgeResult =
  | { kind: "none" }
  | { kind: "error"; code: string; description: string }
  | { kind: "success"; deepLink: string; type: string; signature: string };

const AUTH_PARAM_KEYS = [
  "access_token",
  "refresh_token",
  "expires_in",
  "expires_at",
  "token_type",
  "provider_token",
  "code",
  "token_hash",
  "type",
  "error",
  "error_code",
  "error_description",
  "message",
] as const;

function collectParams(url: URL): URLSearchParams {
  const merged = new URLSearchParams();
  url.searchParams.forEach((value, key) => merged.set(key, value));
  // Fragment params win — that's where Supabase puts the sensitive tokens.
  new URLSearchParams(url.hash.replace(/^#/, "")).forEach((value, key) => merged.set(key, value));
  return merged;
}

/** Tiny non-cryptographic signature used only to de-duplicate handled links. */
function signatureOf(input: string): string {
  let hash = 5381;
  for (let i = 0; i < input.length; i++) {
    hash = ((hash << 5) + hash + input.charCodeAt(i)) >>> 0;
  }
  return hash.toString(36);
}

/** Reads and classifies auth parameters from the current URL. */
export function readAuthResult(href: string = window.location.href): BridgeResult {
  let url: URL;
  try {
    url = new URL(href);
  } catch {
    return { kind: "none" };
  }
  const params = collectParams(url);
  const hasAuthParam = AUTH_PARAM_KEYS.some((key) => params.has(key));
  if (!hasAuthParam) return { kind: "none" };

  const error = params.get("error");
  const errorCode = params.get("error_code");
  if (error || errorCode) {
    return {
      kind: "error",
      code: errorCode ?? error ?? "unknown",
      description: params.get("error_description") ?? "",
    };
  }

  const forward = new URLSearchParams();
  AUTH_PARAM_KEYS.forEach((key) => {
    const value = params.get(key);
    if (value !== null && value !== "") forward.set(key, value);
  });

  const fragment = forward.toString();
  return {
    kind: "success",
    deepLink: fragment ? `${appDeepLink}#${fragment}` : appDeepLink,
    type: params.get("type") ?? "signup",
    signature: signatureOf(fragment),
  };
}

/**
 * Removes tokens from the address bar and browser history so a refresh or
 * back-navigation never re-exposes or re-fires the one-time link.
 */
export function scrubUrl(): void {
  try {
    window.history.replaceState(null, "", window.location.pathname);
  } catch {
    /* history API unavailable — nothing sensitive is persisted by us anyway */
  }
}

const HANDLED_KEY = "mf.bridge.handled";

/** True when this exact link already auto-launched once in this session. */
export function wasHandled(signature: string): boolean {
  try {
    return window.sessionStorage.getItem(HANDLED_KEY) === signature;
  } catch {
    return false;
  }
}

/** Marks a link as consumed for this browser session (loop prevention). */
export function markHandled(signature: string): void {
  try {
    window.sessionStorage.setItem(HANDLED_KEY, signature);
  } catch {
    /* private mode — the visibility fallback still prevents loops */
  }
}

/** True when the error code means the one-time link expired or was reused. */
export function isExpiredError(code: string): boolean {
  return code === "otp_expired" || code === "access_denied" || code === "403";
}
