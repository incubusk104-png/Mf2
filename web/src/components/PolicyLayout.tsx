import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";

import { site } from "@/lib/site";

export interface PolicySection {
  heading: string;
  paragraphs: string[];
  bullets?: string[];
}

interface PolicyLayoutProps {
  title: string;
  updatedLabel: string;
  backLabel: string;
  sections: PolicySection[];
  zhTitle?: string;
  zhUpdatedLabel?: string;
  zhSections?: PolicySection[];
  children?: ReactNode;
}

/** Shared dark-theme long-form layout for the legal pages. */
export function PolicyLayout({
  title,
  updatedLabel,
  backLabel,
  sections,
  zhTitle,
  zhUpdatedLabel,
  zhSections,
}: PolicyLayoutProps) {
  return (
    <main className="relative min-h-screen bg-background">
      <div className="atmosphere pointer-events-none absolute inset-0" aria-hidden />

      <div className="relative z-10 mx-auto max-w-3xl px-5 py-16">
        <div className="flex flex-wrap items-center gap-4">
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-sm text-muted-foreground transition-colors hover:text-foreground"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden />
            {backLabel}
          </Link>
        </div>

        <header className="mt-10 flex items-start gap-4">
          <img
            src="/assets/logo.png"
            alt={`${site.name} logo`}
            className="h-12 w-12 object-contain"
          />
          <div>
            <h1 className="font-display text-4xl font-semibold tracking-tight">{title}</h1>
            <p className="mt-2 text-sm text-muted-foreground">{updatedLabel}</p>
          </div>
        </header>

        <article className="mt-10 space-y-10">
          {sections.map((section) => (
            <section key={section.heading}>
              <h2 className="font-display text-2xl font-semibold">{section.heading}</h2>
              {section.paragraphs.map((paragraph) => (
                <p key={paragraph} className="mt-3 leading-relaxed text-muted-foreground">
                  {paragraph}
                </p>
              ))}
              {section.bullets && (
                <ul className="mt-3 list-disc space-y-2 pl-6 text-muted-foreground">
                  {section.bullets.map((bullet) => (
                    <li key={bullet} className="leading-relaxed">
                      {bullet}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          ))}
        </article>

        {zhSections && zhSections.length > 0 && (
          <>
            <hr className="mt-16 border-border" />
            <header className="mt-16 flex items-start gap-4">
              <img
                src="/assets/logo.png"
                alt={`${site.name} logo`}
                className="h-12 w-12 object-contain"
              />
              <div>
                <h1 className="font-display text-4xl font-semibold tracking-tight">
                  {zhTitle ?? title}
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">{zhUpdatedLabel ?? updatedLabel}</p>
              </div>
            </header>
            <article className="mt-10 space-y-10">
              {zhSections.map((section) => (
                <section key={section.heading}>
                  <h2 className="font-display text-2xl font-semibold">{section.heading}</h2>
                  {section.paragraphs.map((paragraph) => (
                    <p key={paragraph} className="mt-3 leading-relaxed text-muted-foreground">
                      {paragraph}
                    </p>
                  ))}
                  {section.bullets && (
                    <ul className="mt-3 list-disc space-y-2 pl-6 text-muted-foreground">
                      {section.bullets.map((bullet) => (
                        <li key={bullet} className="leading-relaxed">
                          {bullet}
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              ))}
            </article>
          </>
        )}

        <footer className="mt-16 border-t border-border pt-6 text-sm text-muted-foreground">
          © {new Date().getFullYear()} {site.name}
        </footer>
      </div>
    </main>
  );
}
