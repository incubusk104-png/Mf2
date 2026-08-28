import { useMemo } from "react";

import { AuthBridgeView } from "@/components/AuthBridgeView";
import { FeatureGrid } from "@/components/marketing/FeatureGrid";
import { Hero } from "@/components/marketing/Hero";
import { LanguagesSection } from "@/components/marketing/LanguagesSection";
import { Navbar } from "@/components/marketing/Navbar";
import { PricingSection } from "@/components/marketing/PricingSection";
import { ScreensShowcase } from "@/components/marketing/ScreensShowcase";
import { SiteFooter } from "@/components/marketing/SiteFooter";
import { readAuthResult, type BridgeResult } from "@/lib/authBridge";

/**
 * mindsetframes.online — marketing landing page AND Supabase auth web-bridge.
 *
 * When Supabase redirects an email link here (Site URL), the URL carries
 * auth-callback parameters; the page then switches to auth-bridge mode and
 * hands the session off to the app via deep link. Otherwise it renders the
 * marketing site.
 */
export default function Index() {
  // Read once on first render — the bridge scrubs the URL afterwards.
  const bridge = useMemo<BridgeResult>(() => readAuthResult(), []);

  if (bridge.kind !== "none") {
    return <AuthBridgeView result={bridge} />;
  }

  return (
    <div className="min-h-screen bg-background">
      <Navbar />
      <main>
        <Hero />
        <FeatureGrid />
        <ScreensShowcase />
        <LanguagesSection />
        <PricingSection />
      </main>
      <SiteFooter />
    </div>
  );
}
