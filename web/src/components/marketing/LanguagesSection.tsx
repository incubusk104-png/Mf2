import { Check, Lock } from "lucide-react";

import { useI18n } from "@/lib/i18n";

const LANGUAGE_NAMES: ReadonlyArray<string> = [
  "English", "中文", "Tagalog", "Español", "Français", "Deutsch", "日本語", "한국어",
  "العربية", "हिन्दी", "Português", "Русский", "Italiano", "Bahasa Indonesia", "Türkçe",
  "Tiếng Việt", "ภาษาไทย", "বাংলা", "اردو", "Nederlands", "Polski", "Svenska", "Norsk",
  "Ελληνικά", "Українська", "Bahasa Melayu",
];

/**
 * The dual-free language model: English free everywhere, the device-locale
 * regional language free on first launch, everything else Premium.
 */
export function LanguagesSection() {
  const { t } = useI18n();
  const marqueeItems = [...LANGUAGE_NAMES, ...LANGUAGE_NAMES];

  return (
    <section id="languages" className="py-24">
      <div className="mx-auto max-w-6xl px-5">
        <h2 className="max-w-2xl font-display text-4xl font-semibold tracking-tight sm:text-5xl">
          {t.langTitle}
        </h2>
        <p className="mt-4 max-w-xl text-lg text-muted-foreground">{t.langSub}</p>
      </div>

      <div className="mask-fade-x mt-12 overflow-hidden" aria-hidden>
        <div className="flex w-max animate-marquee gap-4">
          {marqueeItems.map((name, index) => (
            <span
              key={`${name}-${index}`}
              className="whitespace-nowrap rounded-full border border-border bg-card/60 px-5 py-2 font-display text-lg text-foreground/70"
            >
              {name}
            </span>
          ))}
        </div>
      </div>

      <div className="mx-auto mt-12 grid max-w-6xl gap-4 px-5 sm:grid-cols-2">
        <article className="rounded-2xl border border-primary/35 bg-primary/10 p-6">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-primary px-3 py-1 text-xs font-bold uppercase tracking-wider text-primary-foreground">
            <Check className="h-3.5 w-3.5" aria-hidden /> Free
          </span>
          <h3 className="mt-4 font-display text-xl font-semibold">{t.langFreeEnglish}</h3>
        </article>

        <article className="rounded-2xl border border-primary/35 bg-primary/10 p-6">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-primary px-3 py-1 text-xs font-bold uppercase tracking-wider text-primary-foreground">
            <Check className="h-3.5 w-3.5" aria-hidden /> Free
          </span>
          <h3 className="mt-4 font-display text-xl font-semibold">{t.langFreeRegional}</h3>
          <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{t.langFreeRegionalBody}</p>
        </article>
      </div>

      <p className="mx-auto mt-6 flex max-w-6xl items-center gap-2 px-5 text-sm text-muted-foreground">
        <Lock className="h-4 w-4 text-accent" aria-hidden />
        {t.langPremiumNote}
      </p>
    </section>
  );
}
