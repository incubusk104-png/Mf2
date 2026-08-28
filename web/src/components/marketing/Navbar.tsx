import { useCallback, useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";

import { Button } from "@/components/ui/button";
import { PrivacySidebar } from "@/components/marketing/PrivacySidebar";
import { useI18n, type Lang } from "@/lib/i18n";
import { site } from "@/lib/site";

const NAV_LINKS: ReadonlyArray<{ id: string; key: "navFeatures" | "navScreens" | "navLanguages" | "navPricing" }> = [
  { id: "features", key: "navFeatures" },
  { id: "screens", key: "navScreens" },
  { id: "languages", key: "navLanguages" },
  { id: "pricing", key: "navPricing" },
];

export function Navbar() {
  const { t, lang, setLang } = useI18n();
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  const toggle = useCallback(
    (next: Lang) => () => {
      if (next !== lang) setLang(next);
    },
    [lang, setLang],
  );

  return (
    <header
      className={`fixed inset-x-0 top-0 z-50 border-b border-border/60 bg-background/70 backdrop-blur-xl transition-shadow duration-300 ${
        scrolled ? "shadow-lg shadow-black/20" : "shadow-none"
      }`}
    >
      <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-5">
        <a href="#top" className="flex items-center gap-2.5">
          <img
            src="/assets/logo.png"
            alt={`${site.name} logo`}
            className="h-8 w-8 object-contain"
          />
          <span className="font-display text-lg font-semibold tracking-tight">{site.name}</span>
        </a>

        <div className="hidden items-center gap-7 md:flex">
          {NAV_LINKS.map((link) => (
            <a
              key={link.id}
              href={`#${link.id}`}
              className="text-sm text-muted-foreground transition-colors hover:text-foreground"
            >
              {t[link.key]}
            </a>
          ))}
        </div>

        <div className="flex items-center gap-2">
          <div
            role="group"
            aria-label="Language selector"
            className="flex items-center rounded-full border border-border bg-secondary p-0.5 text-xs font-semibold"
          >
            <button
              type="button"
              onClick={toggle("en")}
              aria-pressed={lang === "en"}
              className={`rounded-full px-2.5 py-1 transition-colors ${
                lang === "en" ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground"
              }`}
            >
              EN
            </button>
            <button
              type="button"
              onClick={toggle("zh")}
              aria-pressed={lang === "zh"}
              className={`rounded-full px-2.5 py-1 transition-colors ${
                lang === "zh" ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground"
              }`}
            >
              中文
            </button>
          </div>

          <PrivacySidebar
            trigger={
              <Button variant="ghost" size="icon" aria-label={t.navPrivacy} className="rounded-full">
                <ShieldCheck className="h-5 w-5" aria-hidden />
              </Button>
            }
          />

          <Button asChild size="sm" className="hidden rounded-full font-semibold sm:inline-flex">
            <a href="#download">{t.navDownload}</a>
          </Button>
        </div>
      </nav>
    </header>
  );
}
