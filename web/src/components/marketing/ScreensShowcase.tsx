import { useI18n, type Copy } from "@/lib/i18n";

interface Screen {
  src: string;
  captionKey: keyof Copy;
  altKey: keyof Copy;
}

const SCREENS: ReadonlyArray<Screen> = [
  { src: "/assets/screenshot-1.png", captionKey: "screen1Caption", altKey: "screen1Alt" },
  { src: "/assets/screenshot-2.png", captionKey: "screen2Caption", altKey: "screen2Alt" },
  { src: "/assets/screenshot-3.png", captionKey: "screen3Caption", altKey: "screen3Alt" },
  { src: "/assets/screenshot-languages.png", captionKey: "screen4Caption", altKey: "screen4Alt" },
];

/**
 * High-fidelity device mockups in a snap-scrolling rail, edges softly masked.
 */
export function ScreensShowcase() {
  const { t } = useI18n();

  return (
    <section id="screens" className="py-24">
      <div className="mx-auto max-w-6xl px-5">
        <h2 className="max-w-2xl font-display text-4xl font-semibold tracking-tight sm:text-5xl">
          {t.screensTitle}
        </h2>
        <p className="mt-4 max-w-xl text-lg text-muted-foreground">{t.screensSub}</p>
      </div>

      <div className="mask-fade-x no-scrollbar mt-12 flex snap-x snap-mandatory gap-8 overflow-x-auto px-[max(1.25rem,calc((100vw-72rem)/2))] pb-4">
        {SCREENS.map(({ src, captionKey, altKey }, index) => (
          <figure key={src} className="shrink-0 snap-center">
            <div
              className="animate-float rounded-[2.2rem] bg-gradient-to-b from-primary/20 to-transparent p-[2px]"
              style={{ animationDelay: `${index * 900}ms` }}
            >
              <img
                src={src}
                alt={t[altKey]}
                loading="lazy"
                className="h-[520px] w-auto rounded-[2.1rem] shadow-2xl shadow-black/50"
              />
            </div>
            <figcaption className="mt-4 text-center text-sm font-medium text-muted-foreground">
              {t[captionKey]}
            </figcaption>
          </figure>
        ))}
      </div>
    </section>
  );
}
