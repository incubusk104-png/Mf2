/**
 * Central site configuration for mindsetframes.online.
 * Every external identity lives here so release-day changes are one-line flips.
 */
export const site = {
  name: "Mindset Frames",
  domain: "https://mindsetframes.online",

  /**
   * Developer identity as registered in Huawei AppGallery Connect.
   * IMPORTANT: replace with the EXACT registered developer name before
   * submission — AGC reviewers cross-check this string in the privacy policy.
   */
  developerName: "Mindset Frames",

  supportEmail: "mindsetframes2026@gmail.com",

  /** Android applicationId registered on AppGallery Connect. */
  packageName: "com.mindsetframes.habittracker",

  /** Custom scheme handled by MainActivity for the auth web-bridge. */
  deepLinkScheme: "com.mindsetframes.habittracker",

  /** AppGallery app id (agconnect app_id 118611057 → listing C118611057). */
  appGalleryAppId: "C118611057",

  /** Flip to true the moment the listing goes live on AppGallery. */
  appGalleryLive: false,

  privacyEffectiveDate: "2026-08-14",
} as const;

/** Web listing URL on Huawei AppGallery. */
export const appGalleryUrl = `https://appgallery.huawei.com/app/${site.appGalleryAppId}`;

/** Deep link that opens the installed app on its auth-callback entry. */
export const appDeepLink = `${site.deepLinkScheme}://auth-callback`;
