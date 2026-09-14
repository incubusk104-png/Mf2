/**
 * Central pricing config for mindsetframes.online.
 * Edit values here only — components read from this file.
 *
 * IMPORTANT: actual charge happens in Huawei AppGallery Connect (IAP),
 * priced per-country there. These numbers are the USD anchor/reference
 * shown on the marketing site — not a live price feed. Keep this in
 * sync by hand whenever you change the AGC base price.
 */
export const pricing = {
  /**
   * Founding-member target PER COUNTRY/REGION. Matches the default in
   * founding_member_cap_for_region() (backend/supabase/migrations/
   * 20260915120000_founding_member_per_country_cap.sql) — a region can be
   * overridden in founding_member_region_caps, so this is the default the
   * marketing copy quotes, not a hard ceiling for every region.
   */
  foundingSlots: 500,

  founding: {
    monthly: "$2.99",
    yearly: "$19.99",
  },

  regular: {
    monthly: "$4.99",
    yearly: "$34.99",
  },
} as const;
