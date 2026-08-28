import { useState } from "react";
import { Check, Sparkles } from "lucide-react";
import { useI18n } from "@/lib/i18n";
import { pricing } from "@/lib/pricing";
import { Button } from "@/components/ui/button";
import { Reveal } from "@/components/marketing/Reveal";

/**
 * Free vs Premium comparison + founding-member banner.
 * Mirrors FeatureGrid's card styling for visual consistency.
 */
export function PricingSection() {
  const { t } = useI18n();
  const [cycle, setCycle] = useState<"monthly" | "yearly">("monthly");

  const foundingPrice = pricing.founding[cycle];
  const regularPrice = pricing.regular[cycle];

  return (
    <section id="pricing" className="mx-auto max-w-6xl px-5 py-24">
      <Reveal>
        <h2 className="max-w-2xl font-display text-4xl font-semibold tracking-tight sm:text-5xl">
          {t.pricingTitle}
        </h2>
        <p className="mt-4 max-w-xl text-lg text-muted-foreground">{t.pricingSub}</p>
      </Reveal>

      {/* Founding member banner */}
      <Reveal delay={100}>
        <div className="mt-8 flex flex-wrap items-center gap-3 rounded-2xl border border-primary/30 bg-primary/10 px-6 py-4">
          <Sparkles className="h-5 w-5 shrink-0 text-primary" aria-hidden />
          <p className="text-sm font-medium text-foreground">
            {t.pricingFoundingBanner.replace("{slots}", String(pricing.foundingSlots))}
          </p>
        </div>
      </Reveal>

      {/* Monthly / yearly toggle */}
      <Reveal delay={150}>
        <div
          role="group"
          aria-label={t.pricingToggleLabel}
          className="mt-8 inline-flex items-center rounded-full border border-border bg-secondary p-0.5 text-sm font-semibold"
        >
          {(["monthly", "yearly"] as const).map((option) => (
            <button
              key={option}
              type="button"
              onClick={() => setCycle(option)}
              aria-pressed={cycle === option}
              className={`rounded-full px-4 py-1.5 transition-colors ${
                cycle === option
                  ? "bg-primary text-primary-foreground"
                  : "text-muted-foreground hover:text-foreground"
              }`}
            >
              {option === "monthly" ? t.pricingMonthly : t.pricingYearly}
            </button>
          ))}
        </div>
      </Reveal>

      <div className="mt-8 grid gap-4 sm:grid-cols-2">
        {/* Free tier */}
        <Reveal delay={200} className="h-full">
          <article className="h-full rounded-2xl border border-border bg-card/60 p-6 transition-all duration-300 ease-out hover:-translate-y-1 hover:border-primary/40 hover:bg-card hover:shadow-lg hover:shadow-primary/5">
            <h3 className="font-display text-xl font-semibold">{t.pricingFreeTitle}</h3>
            <p className="mt-1 text-3xl font-semibold">{t.pricingFreePrice}</p>
            <ul className="mt-5 space-y-2.5 text-sm text-muted-foreground">
              {[t.pricingFreeF1, t.pricingFreeF2, t.pricingFreeF3].map((line) => (
                <li key={line} className="flex items-start gap-2">
                  <Check className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden />
                  {line}
                </li>
              ))}
            </ul>
          </article>
        </Reveal>

        {/* Premium / founding tier */}
        <Reveal delay={250} className="h-full">
          <article className="relative h-full rounded-2xl border-2 border-primary bg-card p-6 transition-all duration-300 ease-out hover:-translate-y-1 hover:border-primary/40 hover:shadow-xl hover:shadow-primary/10">
            <span className="absolute -top-3 right-6 rounded-full bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground">
              {t.pricingFoundingBadge}
            </span>
            <h3 className="font-display text-xl font-semibold">{t.pricingPremiumTitle}</h3>
            <div className="mt-1 flex items-baseline gap-2">
              <p className="text-3xl font-semibold text-primary">{foundingPrice}</p>
              <p className="text-sm text-muted-foreground line-through">{regularPrice}</p>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">{t.pricingLockedForever}</p>
            <ul className="mt-5 space-y-2.5 text-sm text-muted-foreground">
              {[t.pricingPremF1, t.pricingPremF2, t.pricingPremF3, t.pricingPremF4].map((line) => (
                <li key={line} className="flex items-start gap-2">
                  <Check className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden />
                  {line}
                </li>
              ))}
            </ul>
            <Button asChild className="mt-6 w-full rounded-full font-semibold">
              <a href="#download">{t.pricingCta}</a>
            </Button>
          </article>
        </Reveal>
      </div>

      <Reveal delay={300}>
        <p className="mt-6 text-xs text-muted-foreground">{t.pricingRegionNote}</p>
      </Reveal>
    </section>
  );
}
