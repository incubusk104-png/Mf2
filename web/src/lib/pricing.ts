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
  /** Matches backend/functions/founding-member-eligibility MAX_CLAIMS. */
  foundingSlots: 100,

  founding: {
    monthly: "$2.99",
    yearly: "$19.99",
  },

  regular: {
    monthly: "$4.99",
    yearly: "$34.99",
  },
} as const;
