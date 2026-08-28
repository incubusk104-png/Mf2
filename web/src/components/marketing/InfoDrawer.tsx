import { Link } from "react-router-dom";
import { Menu, Mail } from "lucide-react";

import { useI18n } from "@/lib/i18n";
import { site } from "@/lib/site";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";

export function InfoDrawer() {
  const { t } = useI18n();

  return (
    <Sheet>
      <SheetTrigger asChild>
        <Button
          variant="outline"
          size="icon"
          aria-label="More info"
          className="h-9 w-9 rounded-full"
        >
          <Menu className="h-4 w-4" aria-hidden="true" />
        </Button>
      </SheetTrigger>
      <SheetContent side="right" className="flex flex-col gap-6">
        <SheetHeader>
          <SheetTitle className="font-display">{site.name}</SheetTitle>
        </SheetHeader>

        <nav className="flex flex-col gap-4 text-sm">
          <Link
            to="/privacy"
            className="text-muted-foreground transition-colors hover:text-foreground"
          >
            {t.footerPrivacyEn}
          </Link>
          <a
            href={`mailto:${site.supportEmail}`}
            className="inline-flex items-center gap-1.5 text-muted-foreground transition-colors hover:text-foreground"
          >
            <Mail className="h-3.5 w-3.5" aria-hidden="true" />
            {t.footerSupport}
          </a>
        </nav>

        <p className="mt-auto text-xs text-muted-foreground">
          © {new Date().getFullYear()} {site.name}. {t.footerRights}
        </p>
      </SheetContent>
    </Sheet>
  );
}
