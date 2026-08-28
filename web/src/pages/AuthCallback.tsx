import { useMemo } from "react";

import { AuthBridgeView } from "@/components/AuthBridgeView";
import { appDeepLink } from "@/lib/site";
import { readAuthResult, type BridgeResult } from "@/lib/authBridge";

/**
 * Dedicated auth-bridge route — register
 * https://mindsetframes.online/auth-callback as a Supabase Redirect URL.
 * With params it verifies and opens the app; without params it offers the
 * manual "Open app" hand-off instead of a dead end.
 */
export default function AuthCallback() {
  const bridge = useMemo<BridgeResult>(() => readAuthResult(), []);

  if (bridge.kind !== "none") {
    return <AuthBridgeView result={bridge} />;
  }

  return (
    <AuthBridgeView
      forceManual
      result={{ kind: "success", deepLink: appDeepLink, type: "manual", signature: "manual" }}
    />
  );
}
