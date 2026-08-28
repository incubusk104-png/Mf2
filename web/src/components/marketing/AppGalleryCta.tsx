import { useCallback } from "react";

import { useI18n } from "@/lib/i18n";
import { site } from "@/lib/site";
import { toast } from "@/components/ui/sonner";

/**
 * Official "Explore it on AppGallery" badge. Links to the live listing once
 * published; until then it shows an honest "launching soon" toast instead of
 * ever exposing a dead store link.
 */
export function AppGalleryCta({ className = "" }: { className?: string }) {
  const { t } = useI18n();

  const onClick = useCallback(
    (event: React.MouseEvent<HTMLAnchorElement>) => {
      if (!site.appGalleryLive) {
        event.preventDefault();
        toast(t.heroComingSoonToast);
      }
    },
    [t],
  );

  return (
    <a
      href={site.appGalleryLive ? site.appGalleryUrl : "#"}
      onClick={onClick}
      target={site.appGalleryLive ? "_blank" : undefined}
      rel="noopener noreferrer"
      aria-label={t.heroBadgeAlt}
      className={`group inline-flex items-center transition-transform hover:scale-[1.02] ${className}`}
    >
      <img
        src="/assets/appgallery-badge.png"
        alt={t.heroBadgeAlt}
        className="h-14 w-auto rounded-xl shadow-lg shadow-black/40"
        loading="eager"
      />
    </a>
  );
}
