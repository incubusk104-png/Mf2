import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

export type Lang = "en" | "zh";

export interface Copy {
  navFeatures: string;
  navScreens: string;
  navLanguages: string;
  navPricing: string;
  navPrivacy: string;
  navDownload: string;

  heroEyebrow: string;
  heroTitleA: string;
  heroTitleAccent: string;
  heroTitleB: string;
  heroSub: string;
  heroOpenApp: string;
  heroBadgeAlt: string;
  heroComingSoon: string;
  heroComingSoonToast: string;
  heroStat1: string;
  heroStat2: string;
  heroStat3: string;

  featuresTitle: string;
  featuresSub: string;
  feat1Title: string;
  feat1Body: string;
  feat2Title: string;
  feat2Body: string;
  feat3Title: string;
  feat3Body: string;
  feat4Title: string;
  feat4Body: string;
  feat5Title: string;
  feat5Body: string;
  feat6Title: string;
  feat6Body: string;
  feat7Title: string;
  feat7Body: string;
  feat8Title: string;
  feat8Body: string;
  feat9Title: string;
  feat9Body: string;

  screensTitle: string;
  screensSub: string;
  screen1Caption: string;
  screen1Alt: string;
  screen2Caption: string;
  screen2Alt: string;
  screen3Caption: string;
  screen3Alt: string;
  screen4Caption: string;
  screen4Alt: string;

  langTitle: string;
  langSub: string;
  langFreeEnglish: string;
  langFreeRegional: string;
  langFreeRegionalBody: string;
  langPremiumNote: string;

  pricingTitle: string;
  pricingSub: string;
  pricingFoundingBanner: string;
  pricingFoundingBadge: string;
  pricingToggleLabel: string;
  pricingMonthly: string;
  pricingYearly: string;
  pricingFreeTitle: string;
  pricingFreePrice: string;
  pricingFreeF1: string;
  pricingFreeF2: string;
  pricingFreeF3: string;
  pricingPremiumTitle: string;
  pricingLockedForever: string;
  pricingPremF1: string;
  pricingPremF2: string;
  pricingPremF3: string;
  pricingPremF4: string;
  pricingCta: string;
  pricingRegionNote: string;

  privacyBandTitle: string;
  privacyBandBody: string;
  privacyBandCta: string;

  sidebarTitle: string;
  sidebarIntro: string;
  sidebarPoint1: string;
  sidebarPoint2: string;
  sidebarPoint3: string;
  sidebarPoint4: string;
  sidebarPoint5: string;
  sidebarReadEn: string;
  sidebarReadZh: string;

  footerTagline: string;
  footerPrivacyEn: string;
  footerPrivacyZh: string;
  footerSupport: string;
  footerServerNote: string;
  footerRights: string;

  bridgeVerified: string;
  bridgeOpening: string;
  bridgeManualTitle: string;
  bridgeManualBody: string;
  bridgeOpenApp: string;
  bridgeNoApp: string;
  bridgeGetOnAppGallery: string;
  bridgeExpiredTitle: string;
  bridgeExpiredBody: string;
  bridgeErrorTitle: string;
  bridgeErrorBody: string;
  bridgeBackHome: string;
  bridgeRecoveryNote: string;
}

const en: Copy = {
  navFeatures: "Features",
  navScreens: "Screens",
  navLanguages: "Languages",
  navPricing: "Pricing",
  navPrivacy: "Privacy",
  navDownload: "Download",

  heroEyebrow: "Mood-first habit tracking",
  heroTitleA: "Habits that begin",
  heroTitleAccent: "with how you feel",
  heroTitleB: ".",
  heroSub:
    "Mindset Frames pairs a gentle daily mood check-in with calm habit tracking. Private by design, backed up only when you choose — in 26 languages.",
  heroOpenApp: "Open in App",
  heroBadgeAlt: "Explore it on Huawei AppGallery",
  heroComingSoon: "Launching soon on AppGallery",
  heroComingSoonToast:
    "Mindset Frames is in final review on Huawei AppGallery — this button will link to the listing at release.",
  heroStat1: "26 languages",
  heroStat2: "Local-first privacy",
  heroStat3: "No ads. Ever.",

  featuresTitle: "A calmer kind of consistency",
  featuresSub:
    "No guilt trips, no noisy gamification. Just a quiet daily rhythm that adapts to how you arrive.",
  feat1Title: "Daily mood check-in",
  feat1Body: "Calm, Focused, Motivated, or Overwhelmed — the whole app re-tunes its tone and theme to meet you there.",
  feat2Title: "Gentle streaks & badges",
  feat2Body: "Streaks that carry through an unfinished day and badges that never re-lock. Progress without pressure.",
  feat3Title: "Insights that breathe",
  feat3Body: "Yearly heatmaps, mood-vs-habit trends, and a Month in Pixels — your consistency, painted softly.",
  feat4Title: "26 languages",
  feat4Body: "English free everywhere, your region's language free on first launch, and 23+ more with Premium.",
  feat5Title: "Cloud backup, your call",
  feat5Body: "Sign in with HUAWEI ID or email to back up and restore. Skip it entirely and stay 100% on-device.",
  feat6Title: "Private by design",
  feat6Body: "Local-first storage, encrypted sessions, strict per-account isolation, and one-tap account deletion.",
  feat7Title: "Activity integrations",
  feat7Body: "Sync Strava, Google Fit, and Huawei Health — login to each account first, then capture activity data into your Mindset Frames timeline.",
  feat8Title: "Smart adaptive alarms",
  feat8Body: "Your habit times are inconsistent? Smart alarms analyze your real activity patterns and set optimal reminders — weekday vs. weekend, with streak protection.",
  feat9Title: "Share your consistency",
  feat9Body: "Generate weekly or monthly reports and share them on Twitter/X, Facebook, LinkedIn, WhatsApp, Telegram, WeChat, and more.",

  screensTitle: "Designed to feel like paper, not pressure",
  screensSub: "Warm cream surfaces, a hand-set serif, and a companion that is genuinely proud of you.",
  screen1Caption: "Today's check-in",
  screen1Alt: "Mindset Frames Today screen with mood check-in, daily reflection prompt, and habit list",
  screen2Caption: "Insights & trends",
  screen2Alt: "Mindset Frames Insights screen with completion trend chart, year heatmap, and Month in Pixels calendar",
  screen3Caption: "Weekly rhythm",
  screen3Alt: "Mindset Frames Weekly screen with check-in bars and habit grid",
  screen4Caption: "26 languages",
  screen4Alt: "Mindset Frames language settings listing 26 languages with free and premium tiers",

  langTitle: "Two languages free, from day one",
  langSub: "Language should never be a paywall for getting started.",
  langFreeEnglish: "English (US & UK) — free everywhere",
  langFreeRegional: "Your region's language — free on first launch",
  langFreeRegionalBody:
    "Simplified Chinese in China, Tagalog in the Philippines, German in Germany… the app detects your device locale and unlocks your local language automatically.",
  langPremiumNote: "All other world languages come with Premium.",

  pricingTitle: "Fair pricing, wherever you are",
  pricingSub: "Free forever for the essentials. Premium unlocks the full experience.",
  pricingFoundingBanner:
    "Founding Member pricing — locked in for life, limited to the first {slots} people who upgrade.",
  pricingFoundingBadge: "Founding price",
  pricingToggleLabel: "Billing cycle",
  pricingMonthly: "Monthly",
  pricingYearly: "Yearly",
  pricingFreeTitle: "Free",
  pricingFreePrice: "$0",
  pricingFreeF1: "Up to 5 habits",
  pricingFreeF2: "English + your region's language",
  pricingFreeF3: "Daily mood check-in & grounding toolkit",
  pricingPremiumTitle: "Premium",
  pricingLockedForever: "Locked in for as long as you stay subscribed.",
  pricingPremF1: "Unlimited habits",
  pricingPremF2: "All 26 languages + 12 accent themes",
  pricingPremF3: "Advanced weekly insights & PDF reports",
  pricingPremF4: "Extended prompts & exclusive quote library",
  pricingCta: "Become a Founding Member",
  pricingRegionNote:
    "Final price is shown in your local currency at checkout on Huawei AppGallery.",

  privacyBandTitle: "Your data belongs to you.",
  privacyBandBody:
    "Everything lives on your device unless you turn on cloud backup. No ads, no trackers, no selling — and the app server sits outside the Chinese mainland.",
  privacyBandCta: "Read the privacy policy",

  sidebarTitle: "Privacy at a glance",
  sidebarIntro: "The short, honest version of our policy:",
  sidebarPoint1: "Local-first — habits, moods, and reflections stay on your device by default.",
  sidebarPoint2: "Cloud backup is optional and tied to a random account ID, never an ad profile.",
  sidebarPoint3: "HUAWEI ID sign-in requests only your OpenID and email — nothing else.",
  sidebarPoint4: "Delete your account in Settings and every cloud row is erased in one transaction.",
  sidebarPoint5: "No ads, no analytics SDKs, no data sale. Servers are outside the Chinese mainland.",
  sidebarReadEn: "Full policy (English)",
  sidebarReadZh: "完整隐私政策（简体中文）",

  footerTagline: "Small frames, calm days, real change.",
  footerPrivacyEn: "Privacy Policy",
  footerPrivacyZh: "隐私政策（中文）",
  footerSupport: "Support",
  footerServerNote: "App server hosted outside the Chinese mainland.",
  footerRights: "All rights reserved.",

  bridgeVerified: "Verified!",
  bridgeOpening: "Opening Mindset Frames…",
  bridgeManualTitle: "Almost there",
  bridgeManualBody:
    "If the app didn't open automatically, tap the button below. Nothing happens? You may be on a device without the app installed.",
  bridgeOpenApp: "Open Mindset Frames",
  bridgeNoApp: "Don't have the app yet?",
  bridgeGetOnAppGallery: "Get it on AppGallery",
  bridgeExpiredTitle: "This link has expired",
  bridgeExpiredBody:
    "Email links are single-use and expire quickly for your security. Open Mindset Frames and request a fresh one from the sign-in screen.",
  bridgeErrorTitle: "Something's off with this link",
  bridgeErrorBody: "The link is invalid or was already used. Request a new email from the app's sign-in screen.",
  bridgeBackHome: "Back to mindsetframes.online",
  bridgeRecoveryNote: "Continue in the app to finish signing in.",
};

const zh: Copy = {
  navFeatures: "功能",
  navScreens: "界面",
  navLanguages: "语言",
  navPricing: "价格",
  navPrivacy: "隐私",
  navDownload: "下载",

  heroEyebrow: "以心情为先的习惯打卡",
  heroTitleA: "习惯，",
  heroTitleAccent: "从今天的心情开始",
  heroTitleB: "。",
  heroSub:
    "Mindset Frames 将每日心情签到与温和的习惯打卡结合在一起。默认本地存储、由你决定是否云端备份 — 支持 26 种语言。",
  heroOpenApp: "在应用中打开",
  heroBadgeAlt: "前往华为应用市场 AppGallery 探索",
  heroComingSoon: "即将上架 AppGallery",
  heroComingSoonToast: "Mindset Frames 正在华为应用市场最终审核中 — 上架后此按钮将直达应用页面。",
  heroStat1: "26 种语言",
  heroStat2: "本地优先的隐私保护",
  heroStat3: "永无广告",

  featuresTitle: "更平静的坚持方式",
  featuresSub: "没有内疚式提醒，没有喧闹的游戏化。只有一段安静的日常节奏，随你的状态而调整。",
  feat1Title: "每日心情签到",
  feat1Body: "平静、专注、有动力或不知所措 — 整个应用会随之调整主题与语气，陪你到达当下。",
  feat2Title: "温和的连续记录与徽章",
  feat2Body: "未完成的一天不会立刻清零，获得的徽章永不收回。有进步，无压力。",
  feat3Title: "会呼吸的数据洞察",
  feat3Body: "年度热力图、心情与习惯趋势对比、像素日历 — 你的坚持，被温柔地绘制。",
  feat4Title: "26 种语言",
  feat4Body: "英语全球免费，你所在地区的语言首次启动即免费解锁，另有 23+ 种语言随高级版开放。",
  feat5Title: "云端备份，由你决定",
  feat5Body: "使用华为帐号或邮箱登录即可备份与恢复；也可以完全跳过，数据 100% 留在设备上。",
  feat6Title: "隐私优先的设计",
  feat6Body: "本地优先存储、加密会话、严格的帐号隔离，以及一键删除帐号。",
  feat7Title: "运动数据整合",
  feat7Body: "同步 Strava、Google Fit 和华为健康 — 先登录各平台帐号，然后将运动数据捕获到 Mindset Frames 时间线。",
  feat8Title: "智能自适应闹钟",
  feat8Body: "习惯时间不固定？智能闹钟会分析你的真实运动模式，分别设置工作日和周末提醒，并保护连续打卡记录。",
  feat9Title: "分享你的坚持",
  feat9Body: "生成周报或月报，分享到 Twitter/X、Facebook、LinkedIn、WhatsApp、Telegram、微信等平台。",

  screensTitle: "像纸张一样温润，而非催促",
  screensSub: "温暖的米色界面、衬线字体，以及一位真心为你骄傲的小伙伴。",
  screen1Caption: "今日签到",
  screen1Alt: "Mindset Frames 今日页面：心情签到、每日反思提示与习惯列表",
  screen2Caption: "洞察与趋势",
  screen2Alt: "Mindset Frames 洞察页面：完成趋势图、年度热力图与像素日历",
  screen3Caption: "每周节奏",
  screen3Alt: "Mindset Frames 每周页面：签到柱状图与习惯网格",
  screen4Caption: "26 种语言",
  screen4Alt: "Mindset Frames 语言设置页面，列出 26 种语言及免费与高级版分级",

  langTitle: "从第一天起，两种语言免费",
  langSub: "语言，永远不该成为开始的门槛。",
  langFreeEnglish: "英语（美式和英式）— 全球免费",
  langFreeRegional: "你所在地区的语言 — 首次启动免费解锁",
  langFreeRegionalBody:
    "在中国是简体中文，在菲律宾是他加禄语，在德国是德语……应用会识别设备的语言区域，自动免费解锁你的本地语言。",
  langPremiumNote: "其余世界语言随高级版开放。",

  pricingTitle: "无论你在哪里，都有公平的价格",
  pricingSub: "基础功能永久免费，高级版解锁完整体验。",
  pricingFoundingBanner: "创始会员价格 — 终身锁定，仅限前 {slots} 位升级用户。",
  pricingFoundingBadge: "创始价",
  pricingToggleLabel: "计费周期",
  pricingMonthly: "月付",
  pricingYearly: "年付",
  pricingFreeTitle: "免费版",
  pricingFreePrice: "¥0",
  pricingFreeF1: "最多 5 个习惯",
  pricingFreeF2: "英语 + 你所在地区的语言",
  pricingFreeF3: "每日心情签到与舒缓工具箱",
  pricingPremiumTitle: "高级版",
  pricingLockedForever: "只要保持订阅，价格永久锁定。",
  pricingPremF1: "无限习惯数量",
  pricingPremF2: "全部 26 种语言 + 12 款主题",
  pricingPremF3: "高级每周洞察与 PDF 报告",
  pricingPremF4: "扩展提示词与专属语录库",
  pricingCta: "成为创始会员",
  pricingRegionNote: "最终价格将在华为应用市场结账时以当地货币显示。",

  privacyBandTitle: "你的数据属于你。",
  privacyBandBody:
    "除非你主动开启云端备份，所有数据都只保存在你的设备上。无广告、无跟踪、绝不出售数据 — 应用服务器位于中国大陆境外。",
  privacyBandCta: "阅读隐私政策",

  sidebarTitle: "隐私速览",
  sidebarIntro: "我们政策的简短诚实版：",
  sidebarPoint1: "本地优先 — 习惯、心情与反思默认只保存在你的设备上。",
  sidebarPoint2: "云端备份为可选项，仅关联随机帐号 ID，绝不用于广告画像。",
  sidebarPoint3: "华为帐号登录仅请求 OpenID 与邮箱 — 仅此而已。",
  sidebarPoint4: "在设置中删除帐号，所有云端数据将在一次事务中彻底清除。",
  sidebarPoint5: "无广告、无分析 SDK、不出售数据。服务器位于中国大陆境外。",
  sidebarReadEn: "Full policy (English)",
  sidebarReadZh: "完整隐私政策（简体中文）",

  footerTagline: "小小的画框，平静的日子，真实的改变。",
  footerPrivacyEn: "Privacy Policy (EN)",
  footerPrivacyZh: "隐私政策",
  footerSupport: "支持",
  footerServerNote: "应用服务器位于中国大陆境外。",
  footerRights: "保留所有权利。",

  bridgeVerified: "验证成功！",
  bridgeOpening: "正在打开 Mindset Frames…",
  bridgeManualTitle: "就快好了",
  bridgeManualBody: "如果应用没有自动打开，请点按下方按钮。没有反应？这台设备上可能尚未安装应用。",
  bridgeOpenApp: "打开 Mindset Frames",
  bridgeNoApp: "还没有安装应用？",
  bridgeGetOnAppGallery: "前往 AppGallery 获取",
  bridgeExpiredTitle: "此链接已过期",
  bridgeExpiredBody: "出于安全考虑，邮件链接为一次性且很快过期。请打开 Mindset Frames，在登录页重新发送一封。",
  bridgeErrorTitle: "链接似乎有问题",
  bridgeErrorBody: "链接无效或已被使用。请在应用的登录页重新请求一封邮件。",
  bridgeBackHome: "返回 mindsetframes.online",
  bridgeRecoveryNote: "请在应用中继续完成登录。",
};

const dictionaries: Record<Lang, Copy> = { en, zh };

const STORAGE_KEY = "mf.lang";

function detectLang(): Lang {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved === "en" || saved === "zh") return saved;
    return navigator.language.toLowerCase().startsWith("zh") ? "zh" : "en";
  } catch {
    return "en";
  }
}

interface I18nValue {
  lang: Lang;
  t: Copy;
  setLang: (lang: Lang) => void;
}

const I18nContext = createContext<I18nValue | null>(null);

export function LangProvider({ children }: { children: React.ReactNode }) {
  const [lang, setLangState] = useState<Lang>(() => detectLang());

  const setLang = useCallback((next: Lang) => {
    setLangState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* storage unavailable (private mode) — language still switches for the session */
    }
  }, []);

  useEffect(() => {
    document.documentElement.lang = lang === "zh" ? "zh-CN" : "en";
  }, [lang]);

  const value = useMemo<I18nValue>(() => ({ lang, t: dictionaries[lang], setLang }), [lang, setLang]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error("useI18n must be used inside LangProvider");
  return ctx;
}
