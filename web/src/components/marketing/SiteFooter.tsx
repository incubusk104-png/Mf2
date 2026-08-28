import { Link } from "react-router-dom";

import { AppGalleryCta } from "@/components/marketing/AppGalleryCta";
import { InfoDrawer } from "@/components/marketing/InfoDrawer";
import { SocialLinks } from "@/components/marketing/SocialLinks";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/lib/i18n";
import { site } from "@/lib/site";

export function SiteFooter() {
  const { t } = useI18n();

  return (
    <footer className="border-t border-border">
      <section className="mx-auto max-w-6xl px-5 py-20 text-center">
        <h2 className="font-display text-4xl font-semibold tracking-tight sm:text-5xl">
          {t.privacyBandTitle}
        </h2>
        <p className="mx-auto mt-4 max-w-2xl text-lg leading-relaxed text-muted-foreground">
          {t.privacyBandBody}
        </p>
        <div className="mt-8 flex flex-wrap items-center justify-center gap-4">
          <AppGalleryCta />
          <Button asChild variant="outline" size="lg" className="h-14 rounded-full px-7">
            <Link to="/privacy">{t.privacyBandCta}</Link>
          </Button>
        </div>
      </section>

      <div className="border-t border-border">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 px-5 py-8 sm:flex-row">
          <div className="flex items-center gap-2.5">
            <img src="/assets/logo.png" alt={`${site.name} logo`} className="h-7 w-7 object-contain" />
            <div>
              <p className="font-display font-semibold leading-none">{site.name}</p>
              <p className="mt-1 text-xs text-muted-foreground">{t.footerTagline}</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <SocialLinks />
            <InfoDrawer />
          </div>
        </div>
      </div>
    </footer>
  );
}
