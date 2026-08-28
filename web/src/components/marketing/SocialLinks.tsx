const SOCIALS = [
  {
    name: "Facebook",
    href: "https://www.facebook.com/share/1Q2xvUz673/",
    path: "M22 12a10 10 0 1 0-11.6 9.9v-7H7.9V12h2.5V9.8c0-2.5 1.5-3.9 3.8-3.9 1.1 0 2.2.2 2.2.2v2.5h-1.3c-1.2 0-1.6.8-1.6 1.6V12h2.8l-.4 2.9h-2.4v7A10 10 0 0 0 22 12z",
  },
  {
    name: "Instagram",
    href: "https://www.instagram.com/mindsetframes2026?igsh=ZmJxZHpvM3dpMWM3",
    path: "M12 2c2.7 0 3.1 0 4.1.1 1.1 0 1.8.2 2.5.5.7.3 1.2.6 1.7 1.1.5.5.9 1 1.1 1.7.3.7.5 1.4.5 2.5.1 1 .1 1.4.1 4.1s0 3.1-.1 4.1c0 1.1-.2 1.8-.5 2.5-.3.7-.6 1.2-1.1 1.7-.5.5-1 .9-1.7 1.1-.7.3-1.4.5-2.5.5-1 .1-1.4.1-4.1.1s-3.1 0-4.1-.1c-1.1 0-1.8-.2-2.5-.5-.7-.3-1.2-.6-1.7-1.1-.5-.5-.9-1-1.1-1.7-.3-.7-.5-1.4-.5-2.5C2 15.1 2 14.7 2 12s0-3.1.1-4.1c0-1.1.2-1.8.5-2.5.3-.7.6-1.2 1.1-1.7.5-.5 1-.9 1.7-1.1.7-.3 1.4-.5 2.5-.5C8.9 2 9.3 2 12 2zm0 1.8c-2.7 0-3 0-4 .1-.9 0-1.4.2-1.7.3-.4.2-.7.3-1 .6-.3.3-.5.6-.6 1-.2.4-.3.9-.3 1.7-.1 1-.1 1.3-.1 4s0 3 .1 4c0 .9.2 1.4.3 1.7.2.4.3.7.6 1 .3.3.6.5 1 .6.4.2.9.3 1.7.3 1 .1 1.3.1 4 .1s3 0 4-.1c.9 0 1.4-.2 1.7-.3.4-.2.7-.3 1-.6.3-.3.5-.6.6-1 .2-.4.3-.9.3-1.7.1-1 .1-1.3.1-4s0-3-.1-4c0-.9-.2-1.4-.3-1.7-.2-.4-.3-.7-.6-1-.3-.3-.6-.5-1-.6-.4-.2-.9-.3-1.7-.3-1-.1-1.3-.1-4-.1zm0 3.5a4.7 4.7 0 1 1 0 9.4 4.7 4.7 0 0 1 0-9.4zm0 1.8a2.9 2.9 0 1 0 0 5.8 2.9 2.9 0 0 0 0-5.8zm5-2a1.1 1.1 0 1 1-2.2 0 1.1 1.1 0 0 1 2.2 0z",
  },
  {
    name: "TikTok",
    href: "https://www.tiktok.com/@mindset.frames7?_r=1&_t=ZS-98qLbF1Lg8e",
    path: "M16.6 5.8a4.6 4.6 0 0 1-3.8-4H9.5v12.6a2.6 2.6 0 1 1-1.9-2.5v-3.2a5.8 5.8 0 1 0 5 5.7V9.4a7.7 7.7 0 0 0 4 1.1V7.3a4.6 4.6 0 0 1-1-1.5z",
  },
  {
    name: "Reddit",
    href: "https://www.reddit.com/u/mindsetframes2026/s/yVSbEbrdOL",
    path: "M22 12a2.2 2.2 0 0 0-3.7-1.6 10.4 10.4 0 0 0-5.4-1.7L14 5.4l3 .7a1.6 1.6 0 1 0 .2-.9l-3.4-.8a.5.5 0 0 0-.6.4l-1.1 3.8a10.4 10.4 0 0 0-5.5 1.7A2.2 2.2 0 1 0 3.9 14a4 4 0 0 0 0 .6c0 3 3.6 5.4 8.1 5.4s8.1-2.4 8.1-5.4a4 4 0 0 0 0-.6A2.2 2.2 0 0 0 22 12zM8 13.5a1.3 1.3 0 1 1 2.5 0 1.3 1.3 0 0 1-2.5 0zm7.4 3a5.4 5.4 0 0 1-6.8 0 .5.5 0 0 1 .7-.7 4.4 4.4 0 0 0 5.4 0 .5.5 0 0 1 .7.7zm-.9-1.7a1.3 1.3 0 1 1 0-2.5 1.3 1.3 0 0 1 0 2.5z",
  },
] as const;

export function SocialLinks() {
  return (
    <div className="flex items-center gap-3">
      {SOCIALS.map(({ name, href, path }) => (
        <a
          key={name}
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={name}
          className="flex h-9 w-9 items-center justify-center rounded-full border border-border text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground"
        >
          <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
            <path d={path} />
          </svg>
        </a>
      ))}
    </div>
  );
}
