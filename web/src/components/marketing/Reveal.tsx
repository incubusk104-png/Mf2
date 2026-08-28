import type { ReactNode } from "react";
import { useInView } from "@/hooks/use-in-view";

/**
 * Scroll-reveal wrapper. Reuses the existing `animate-fade-up` keyframe
 * (already defined in tailwind.config.ts) so motion feels consistent with
 * the Hero's entrance animation. Respects prefers-reduced-motion via the
 * global rule already in index.css.
 */
export function Reveal({
  children,
  delay = 0,
  className = "",
}: {
  children: ReactNode;
  delay?: number;
  className?: string;
}) {
  const { ref, inView } = useInView<HTMLDivElement>();

  return (
    <div
      ref={ref}
      className={`${inView ? "animate-fade-up" : "opacity-0"} ${className}`}
      style={{ animationDelay: inView ? `${delay}ms` : undefined }}
    >
      {children}
    </div>
  );
}
