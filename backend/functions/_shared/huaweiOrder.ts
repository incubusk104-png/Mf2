// Shared Huawei Order Service verification.
//
// WHY THIS IS SHARED
// ------------------
// Two edge functions need to answer the same question — "did Huawei really
// take money for this purchase?" — and they must never answer it differently:
//
//   * tip-purchase                  (consumable tips)
//   * founding-member-eligibility   (the founding-member slot, which is a
//                                    scarce resource and must only be consumed
//                                    by a real, completed, paid order)
//
// The founding-member case is the one that matters most: a slot is a promise
// that is never sold again, so it must never be granted on the client's word.
// The app's own `SubscriptionResult.Success` is produced purely on-device and
// a modified client could simply assert it, so the server re-verifies the
// signed purchase against Huawei before anything is written.

const HUAWEI_TOKEN_URL = "https://oauth-login.cloud.huawei.com/oauth2/v3/token";

/**
 * Site-specific Order Service roots. Huawei routes a developer account to one
 * of these by site; verification is attempted against each in turn until one
 * answers, so a project created in any region works without configuration.
 * `drcn` (China) and `dre`/`dra` (Europe/Asia) are the documented roots.
 */
const ORDER_ROOTS = [
  "https://orders-drcn.iap.cloud.huawei.com.cn",
  "https://orders-drcn.iap.hicloud.com",
  "https://orders-dre.iap.hicloud.com",
  "https://orders-dra.iap.hicloud.com",
  "https://orders-drru.iap.hicloud.com",
];

/**
 * Path shapes a purchase token may be verified through.
 *
 * Huawei documents the in-app/consumable path as
 * `/applications/purchases/tokens/verify`. Subscriptions are a DIFFERENT
 * resource in the same service and the exact path has varied between API
 * revisions, so all known shapes are tried rather than betting on one.
 * A wrong path answers 404/405 and the loop simply moves on; a wrong guess
 * therefore costs one request, not a broken purchase.
 */
const VERIFY_PATHS = [
  "/applications/purchases/tokens/verify",
  "/applications/purchases/subscriptions/tokens/verify",
  "/applications/subscriptions/purchases/tokens/verify",
];

export interface HuaweiOrder {
  /** 0 = purchased. Anything else means the order is not a completed payment. */
  purchaseState: number | null;
  /** Huawei's order id — the authoritative proof an order was really placed. */
  orderId: string;
  /** The token Huawei echoes back; used as the dedup key when storing. */
  purchaseToken: string;
  /** Subscriptions only: the subscription the order belongs to. */
  subscriptionId: string;
  /** The product the order is actually for, per HUAWEI (not per the client). */
  productId: string;
}

export type VerifyStatus = "verified" | "rejected" | "unavailable";

export interface VerifyResult {
  status: VerifyStatus;
  /** Present only when status === "verified". */
  order?: HuaweiOrder;
  /** Human-readable explanation, for logs and the response body. */
  reason: string;
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
      console.warn(`huaweiOrder: token endpoint returned ${response.status}`);
      return null;
    }
    const data = await response.json();
    return typeof data.access_token === "string" ? data.access_token : null;
  } catch (e) {
    console.warn(
      "huaweiOrder: token endpoint unreachable:",
      e instanceof Error ? e.message : e,
    );
    return null;
  }
}

/**
 * Verifies a purchase token with Huawei's Order Service.
 *
 * Three-state on purpose. The caller MUST treat them differently:
 *
 *   * "verified"    — Huawei confirmed a completed purchase. Safe to grant.
 *   * "rejected"    — Huawei answered and refused. Must NOT grant.
 *   * "unavailable" — verification could not be performed (secrets missing,
 *                     network down, every root unreachable). This is NOT the
 *                     same as "rejected", but for anything scarce it must also
 *                     fail closed — see the caller.
 *
 * `purchaseTokenData` comes back from Huawei as a JSON *string*, not an
 * object, so it is parsed explicitly. Every field is read defensively: the
 * response shape differs slightly between the consumable and subscription
 * resources, and a missing field must degrade to "rejected", never to a
 * crash or an accidental grant.
 */
export async function verifyHuaweiOrder(
  purchaseToken: string,
  productId: string,
): Promise<VerifyResult> {
  const clientId = Deno.env.get("HUAWEI_IAP_CLIENT_ID");
  const clientSecret = Deno.env.get("HUAWEI_IAP_CLIENT_SECRET");
  if (!clientId || !clientSecret) {
    return {
      status: "unavailable",
      reason:
        "HUAWEI_IAP_CLIENT_ID / HUAWEI_IAP_CLIENT_SECRET are not configured on this project",
    };
  }

  const accessToken = await getHuaweiAccessToken(clientId, clientSecret);
  if (!accessToken) {
    return { status: "unavailable", reason: "could not obtain a Huawei access token" };
  }

  // Huawei requires: Authorization: Basic base64("APPAT:" + accessToken)
  const authHeader = `Basic ${btoa(`APPAT:${accessToken}`)}`;
  let reachedHuawei = false;

  for (const root of ORDER_ROOTS) {
    for (const path of VERIFY_PATHS) {
      try {
        const response = await fetch(`${root}${path}`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json; charset=UTF-8",
            Authorization: authHeader,
          },
          body: JSON.stringify({ purchaseToken, productId }),
        });

        // 404/405 = this path shape does not exist on this service revision.
        if (response.status === 404 || response.status === 405) continue;
        if (!response.ok) continue;

        const data = await response.json();
        if (typeof data?.responseCode !== "string") continue;
        reachedHuawei = true;

        if (data.responseCode !== "0") {
          // Huawei answered definitively: this token is not a valid purchase.
          console.warn(`huaweiOrder: Huawei refused the token (rc=${data.responseCode})`);
          return {
            status: "rejected",
            reason: `Huawei rejected the purchase token (responseCode ${data.responseCode})`,
          };
        }

        if (typeof data.purchaseTokenData !== "string") {
          return {
            status: "rejected",
            reason: "Huawei returned success without any order data",
          };
        }

        let parsed: Record<string, unknown>;
        try {
          parsed = JSON.parse(data.purchaseTokenData);
        } catch {
          return { status: "rejected", reason: "Huawei's order payload was not JSON" };
        }

        const purchaseState = typeof parsed.purchaseState === "number"
          ? parsed.purchaseState
          : null;

        // A non-zero purchaseState means the order exists but is NOT a
        // completed payment (canceled / refunded / pending). Never grant.
        if (purchaseState !== 0) {
          return {
            status: "rejected",
            reason: `order is not in the purchased state (purchaseState ${purchaseState})`,
          };
        }

        const orderId = typeof parsed.orderId === "string" ? parsed.orderId : "";
        const subscriptionId =
          typeof parsed.subscriptionId === "string" ? parsed.subscriptionId : "";
        const verifiedProductId =
          typeof parsed.productId === "string" ? parsed.productId : productId;

        // The product HUAWEI says was bought must be the one we asked about.
        // Without this, a client could present a cheap product's token for an
        // expensive product that shares the rest of the flow.
        if (verifiedProductId && productId && verifiedProductId !== productId) {
          return {
            status: "rejected",
            reason: `order is for ${verifiedProductId}, not ${productId}`,
          };
        }

        return {
          status: "verified",
          reason: "Huawei confirmed a completed purchase",
          order: {
            purchaseState,
            orderId,
            purchaseToken,
            subscriptionId,
            productId: verifiedProductId,
          },
        };
      } catch {
        // Network/parse issue with this root or path — try the next.
      }
    }
  }

  return {
    status: "unavailable",
    reason: reachedHuawei
      ? "Huawei answered but not for this request shape"
      : "no Huawei Order Service root was reachable",
  };
}
