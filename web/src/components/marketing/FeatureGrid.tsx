import type { LucideIcon } from "lucide-react";
import {
  BarChart3,
  CloudUpload,
  Flame,
  HeartPulse,
  Languages,
  ShieldCheck,
} from "lucide-react";

import { useI18n, type Copy } from "@/lib/i18n";
import { Reveal } from "@/components/marketing/Reveal";

interface Feature {
  icon: LucideIcon;
  titleKey: keyof Copy;
  bodyKey: keyof Copy;
}

const FEATURES: ReadonlyArray<Feature> = [
  { icon: HeartPulse, titleKey: "feat1Title", bodyKey: "feat1Body" },
  { icon: Flame, titleKey: "feat2Title", bodyKey: "feat2Body" },
  { icon: BarChart3, titleKey: "feat3Title", bodyKey: "feat3Body" },
  { icon: Languages, titleKey: "feat4Title", bodyKey: "feat4Body" },
  { icon: CloudUpload, titleKey: "feat5Title", bodyKey: "feat5Body" },
  { icon: ShieldCheck, titleKey: "feat6Title", bodyKey: "feat6Body" },
];

export function FeatureGrid() {
  const { t } = useI18n();

  return (
    <section id="features" className="mx-auto max-w-6xl px-5 py-24">
      <h2 className="max-w-2xl font-display text-4xl font-semibold tracking-tight sm:text-5xl">
        {t.featuresTitle}
      </h2>
      <p className="mt-4 max-w-xl text-lg text-muted-foreground">{t.featuresSub}</p>

      <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {FEATURES.map(({ icon: Icon, titleKey, bodyKey }, i) => (
          <Reveal key={titleKey} delay={i * 70}>
            <article className="group rounded-2xl border border-border bg-card/60 p-6 transition-all duration-300 ease-out hover:-translate-y-1 hover:border-primary/40 hover:bg-card hover:shadow-lg hover:shadow-primary/5">
              <span className="inline-flex h-11 w-11 items-center justify-center rounded-xl bg-primary/12 text-primary ring-1 ring-primary/25 transition-transform duration-300 group-hover:scale-110">
                <Icon className="h-5 w-5" aria-hidden />
              </span>
              <h3 className="mt-4 font-display text-xl font-semibold">{t[titleKey]}</h3>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{t[bodyKey]}</p>
            </article>
          </Reveal>
        ))}
      </div>
    </section>
  );
}
