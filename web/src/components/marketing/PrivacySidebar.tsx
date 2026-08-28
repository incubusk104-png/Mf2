import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { FileText, Leaf } from "lucide-react";

import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
import { useI18n } from "@/lib/i18n";

/**
 * Toggleable privacy sidebar — a plain-language summary of the policy with
 * links to the full English and Simplified Chinese versions.
 */
export function PrivacySidebar({ trigger }: { trigger: ReactNode }) {
  const { t } = useI18n();

  const points: string[] = [
    t.sidebarPoint1,
    t.sidebarPoint2,
    t.sidebarPoint3,
    t.sidebarPoint4,
    t.sidebarPoint5,
  ];

  return (
    <Sheet>
      <SheetTrigger asChild>{trigger}</SheetTrigger>
      <SheetContent side="right" className="w-full max-w-md overflow-y-auto border-border bg-card">
        <SheetHeader className="text-left">
          <SheetTitle className="font-display text-2xl">{t.sidebarTitle}</SheetTitle>
          <SheetDescription>{t.sidebarIntro}</SheetDescription>
        </SheetHeader>

        <ul className="mt-6 space-y-4">
          {points.map((point) => (
            <li key={point} className="flex items-start gap-3 text-sm leading-relaxed">
              <Leaf className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden />
              <span className="text-muted-foreground">{point}</span>
            </li>
          ))}
        </ul>

        <div className="mt-8 space-y-2">
          <Link
            to="/privacy"
            className="flex items-center gap-2 rounded-xl border border-border bg-secondary px-4 py-3 text-sm font-medium transition-colors hover:border-primary/50"
          >
            <FileText className="h-4 w-4 text-primary" aria-hidden />
            {t.sidebarReadEn}
          </Link>
          <Link
            to="/privacy/zh"
            className="flex items-center gap-2 rounded-xl border border-border bg-secondary px-4 py-3 text-sm font-medium transition-colors hover:border-primary/50"
          >
            <FileText className="h-4 w-4 text-primary" aria-hidden />
            {t.sidebarReadZh}
          </Link>
        </div>
      </SheetContent>
    </Sheet>
  );
}
