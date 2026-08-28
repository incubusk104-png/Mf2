import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { CheckCircle2, Clock3, ExternalLink, MailWarning, Smartphone } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useI18n } from "@/lib/i18n";
import {
  type BridgeResult,
  isExpiredError,
  markHandled,
  scrubUrl,
  wasHandled,
} from "@/lib/authBridge";
import { appGalleryUrl, site } from "@/lib/site";
import { toast } from "@/components/ui/sonner";

type Phase = "opening" | "manual" | "error";

interface AuthBridgeViewProps {
  result: Exclude<BridgeResult, { kind: "none" }>;
  /** Skip the auto-launch and land directly on the manual hand-off card. */
  forceManual?: boolean;
}

/**
 * Full-screen auth-bridge state shown when the URL carries Supabase
 * auth-callback parameters. Handles the "Verified! Opening app…" launch,
 * loop prevention, and the manual fallback when the deep link can't resolve.
 */
export function AuthBridgeView({ result, forceManual = false }: AuthBridgeViewProps) {
  const { t } = useI18n();
  const isError = result.kind === "error";
  const [phase, setPhase] = useState<Phase>(() => {
    if (isError) return "error";
    if (forceManual) return "manual";
    // Back-navigation or refresh after a handled link: no auto relaunch.
    return result.kind === "success" && wasHandled(result.signature) ? "manual" : "opening";
  });
  const launchedRef = useRef<boolean>(false);
  const hiddenRef = useRef<boolean>(false);

  // Tokens must never linger in the address bar or history.
  useEffect(() => {
    scrubUrl();
  }, []);

  const openApp = useCallback(() => {
    if (result.kind !== "success") return;
    window.location.href = result.deepLink;
  }, [result]);

  useEffect(() => {
    if (phase !== "opening" || result.kind !== "success") return;

    const onHidden = () => {
      if (document.visibilityState === "hidden") hiddenRef.current = true;
    };
    document.addEventListener("visibilitychange", onHidden);
    window.addEventListener("pagehide", onHidden);

    const launchTimer = window.setTimeout(() => {
      if (!launchedRef.current) {
        launchedRef.current = true;
        markHandled(result.signature);
        openApp();
      }
    }, 900);

    // Loop prevention: if the page is still visible shortly after the launch
    // attempt, the scheme didn't resolve (no app installed / blocked webview).
    // Show the friendly manual fallback instead of leaving the user stuck.
    const fallbackTimer = window.setTimeout(() => {
      if (!hiddenRef.current && document.visibilityState === "visible") {
        setPhase("manual");
      }
    }, 3400);

    return () => {
      window.clearTimeout(launchTimer);
      window.clearTimeout(fallbackTimer);
      document.removeEventListener("visibilitychange", onHidden);
      window.removeEventListener("pagehide", onHidden);
    };
  }, [phase, result, openApp]);

  const onGetApp = useCallback(() => {
    if (site.appGalleryLive) {
      window.open(appGalleryUrl, "_blank", "noopener,noreferrer");
    } else {
      toast(t.heroComingSoonToast);
    }
  }, [t]);

  const expired = isError && isExpiredError(result.code);

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background px-6">
      <div className="atmosphere absolute inset-0" aria-hidden />

      <section className="relative z-10 w-full max-w-md animate-fade-up rounded-3xl border border-border bg-card/80 p-8 text-center shadow-2xl backdrop-blur">
        <img
          src="/assets/logo.png"
          alt={`${site.name} logo — a leaf with growth bars`}
          className="mx-auto h-16 w-16 object-contain"
        />

        {phase === "opening" && (
          <>
            <div className="mt-6 flex items-center justify-center gap-2 text-primary">
              <CheckCircle2 className="h-6 w-6" aria-hidden />
              <span className="font-display text-2xl font-semibold">{t.bridgeVerified}</span>
            </div>
            <p className="mt-2 text-muted-foreground">{t.bridgeOpening}</p>
            <div
              className="mx-auto mt-6 h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent"
              role="status"
              aria-label={t.bridgeOpening}
            />
            {result.kind === "success" && result.type === "recovery" && (
              <p className="mt-4 text-sm text-muted-foreground">{t.bridgeRecoveryNote}</p>
            )}
          </>
        )}

        {phase === "manual" && (
          <>
            <div className="mt-6 flex items-center justify-center gap-2">
              <Smartphone className="h-6 w-6 text-primary" aria-hidden />
              <span className="font-display text-2xl font-semibold">{t.bridgeManualTitle}</span>
            </div>
            <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{t.bridgeManualBody}</p>
            <Button
              onClick={openApp}
              size="lg"
              className="mt-6 w-full rounded-full text-base font-semibold"
            >
              {t.bridgeOpenApp}
            </Button>
            <p className="mt-6 text-sm text-muted-foreground">{t.bridgeNoApp}</p>
            <Button
              variant="outline"
              onClick={onGetApp}
              className="mt-2 w-full rounded-full"
            >
              <ExternalLink className="mr-2 h-4 w-4" aria-hidden />
              {t.bridgeGetOnAppGallery}
            </Button>
          </>
        )}

        {phase === "error" && (
          <>
            <div className="mt-6 flex items-center justify-center gap-2 text-accent">
              {expired ? (
                <Clock3 className="h-6 w-6" aria-hidden />
              ) : (
                <MailWarning className="h-6 w-6" aria-hidden />
              )}
              <span className="font-display text-2xl font-semibold">
                {expired ? t.bridgeExpiredTitle : t.bridgeErrorTitle}
              </span>
            </div>
            <p className="mt-3 text-sm leading-relaxed text-muted-foreground">
              {expired ? t.bridgeExpiredBody : t.bridgeErrorBody}
            </p>
            <Button
              onClick={openApp}
              size="lg"
              className="mt-6 w-full rounded-full text-base font-semibold"
            >
              {t.bridgeOpenApp}
            </Button>
          </>
        )}

        <Link
          to="/"
          className="mt-8 inline-block text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
        >
          {t.bridgeBackHome}
        </Link>
      </section>
    </main>
  );
}
