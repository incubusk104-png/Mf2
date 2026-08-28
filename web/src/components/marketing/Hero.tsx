import { Smartphone } from "lucide-react";

import { AppGalleryCta } from "@/components/marketing/AppGalleryCta";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/lib/i18n";
import { appDeepLink } from "@/lib/site";

/**
 * Cinematic hero — the meditation loop plays behind a dark gradient wash so
 * the serif headline stays readable on every viewport.
 */
export function Hero() {
  const { t } = useI18n();

  return (
    <section id="top" className="relative flex min-h-[94vh] items-center overflow-hidden">
      <video
        className="absolute inset-0 h-full w-full object-cover opacity-45"
        src="/assets/hero-video.mp4"
        poster="/assets/hero-hands.jpg"
        autoPlay
        muted
        loop
        playsInline
        aria-hidden
      />
      <div
        className="absolute inset-0 bg-gradient-to-b from-background/70 via-background/55 to-background"
        aria-hidden
      />
      <div className="atmosphere absolute inset-0" aria-hidden />

      <div className="relative z-10 mx-auto w-full max-w-6xl px-5 pb-20 pt-32">
        <p className="animate-fade-up text-xs font-semibold uppercase tracking-[0.3em] text-primary">
          {t.heroEyebrow}
        </p>

        <h1
          className="mt-5 max-w-3xl animate-fade-up font-display text-5xl font-semibold leading-[1.05] tracking-tight sm:text-6xl md:text-7xl"
          style={{ animationDelay: "120ms" }}
        >
          {t.heroTitleA}{" "}
          <em className="text-primary">{t.heroTitleAccent}</em>
          {t.heroTitleB}
        </h1>

        <p
          className="mt-6 max-w-xl animate-fade-up text-lg leading-relaxed text-muted-foreground"
          style={{ animationDelay: "240ms" }}
        >
          {t.heroSub}
        </p>

        <div
          id="download"
          className="mt-10 flex animate-fade-up flex-wrap items-center gap-5"
          style={{ animationDelay: "360ms" }}
        >
          <AppGalleryCta />
          <Button
            asChild
            variant="outline"
            size="lg"
            className="h-14 rounded-full border-primary/40 px-7 text-base font-semibold hover:bg-primary/10"
          >
            <a href={appDeepLink}>
              <Smartphone className="mr-2 h-5 w-5" aria-hidden />
              {t.heroOpenApp}
            </a>
          </Button>
        </div>

        <dl
          className="mt-16 flex animate-fade-up flex-wrap gap-x-10 gap-y-4"
          style={{ animationDelay: "480ms" }}
        >
          {[t.heroStat1, t.heroStat2, t.heroStat3].map((stat) => (
            <div key={stat} className="flex items-center gap-2.5">
              <span className="h-1.5 w-1.5 rounded-full bg-primary" aria-hidden />
              <dt className="sr-only">{stat}</dt>
              <dd className="text-sm font-medium text-foreground/80">{stat}</dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  );
}
