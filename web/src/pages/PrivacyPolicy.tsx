import { PolicyLayout, type PolicySection } from "@/components/PolicyLayout";
import { site } from "@/lib/site";

const sections: PolicySection[] = [
  {
    heading: "1. Who we are",
    paragraphs: [
      `"Mindset Frames" is a mood-first habit tracking app for Android (package name ${site.packageName}), published on Huawei AppGallery by ${site.developerName} ("we", "us", "our"). This Privacy Policy explains what data the Mindset Frames app and this website process, why, and the choices you have.`,
      `You can reach us anytime at ${site.supportEmail}.`,
    ],
  },
  {
    heading: "2. The short version",
    bullets: [
      "Mindset Frames is local-first: your habits, check-ins, moods, and reflections are stored on your device by default.",
      "Cloud backup is optional and only starts after you create an account and sign in.",
      "We show no ads, embed no advertising or analytics SDKs, and never sell or share your data for marketing.",
      "You can permanently delete your account and all cloud data from inside the app at any time.",
      "The app server is hosted outside the Chinese mainland.",
    ],
    paragraphs: [],
  },
  {
    heading: "3. Data stored on your device",
    paragraphs: [
      "The app keeps your habits, daily check-ins, mood history, one-line reflections, earned badges, companion avatar, and settings in private app storage on your device. This data never leaves your device unless you enable cloud backup. Uninstalling the app removes it.",
    ],
  },
  {
    heading: "4. Data processed when you enable cloud backup",
    paragraphs: ["If you choose to create an account, we process the minimum needed to run backup and restore:"],
    bullets: [
      "Email sign-in: your email address and a password. The password is stored only as a salted hash by our authentication provider — we never see or store it in plain text.",
      "HUAWEI ID sign-in: with your consent, the minimal scopes only — your OpenID identifier and email address. The sign-in is verified server-side with Huawei's account service. We do not access your contacts, phone number, or any other HUAWEI ID profile data.",
      "Backup content: your habits, check-in dates, mood history, and app settings, linked to a random account identifier.",
      "A random per-install device identifier (UUID) used for sync bookkeeping. It is not an advertising ID and is not derived from any hardware identifier.",
    ],
  },
  {
    heading: "5. Purposes and legal bases",
    paragraphs: [
      "We process account data to provide backup, restore, and multi-device sync (performance of a contract), to keep your account secure (legitimate interest), and only after your explicit action to sign in (consent, which you may withdraw by signing out or deleting the account). We do not profile you, and we make no automated decisions about you.",
    ],
  },
  {
    heading: "6. Where your data is stored",
    paragraphs: [
      "Cloud data is stored in a managed PostgreSQL database operated by Supabase, hosted on servers located outside the Chinese mainland. All data in transit is protected with TLS 1.2 or higher. On your device, session tokens are sealed with a hardware-backed key in the Android Keystore.",
    ],
  },
  {
    heading: "7. Third-party services",
    paragraphs: ["We rely on two service providers, each receiving only what is necessary:"],
    bullets: [
      "Supabase (authentication and database hosting) — receives your account email or Huawei-derived account identity and your encrypted-in-transit backup content.",
      "Huawei Account Kit (optional HUAWEI ID sign-in) — processes your HUAWEI ID sign-in under Huawei's own privacy statement; we receive only the verified OpenID and email.",
    ],
  },
  {
    heading: "8. Retention and deletion",
    paragraphs: [
      "Cloud data is retained while your account exists. Choosing \"Delete account\" in Settings permanently erases every cloud record you own — habits, check-ins, moods, settings, and the account itself — in a single server-side transaction. Local data stays on your device until you clear the app's storage or uninstall.",
    ],
  },
  {
    heading: "9. Your rights",
    paragraphs: [
      "Depending on your region (including the GDPR in the EEA/UK and PIPL-style rights elsewhere), you have the right to access, correct, export, restrict, or delete your personal data, and to withdraw consent at any time. Most of these are self-service inside the app; for anything else, email us and we will respond within 30 days. You may also lodge a complaint with your local supervisory authority.",
    ],
  },
  {
    heading: "10. Children",
    paragraphs: [
      "Mindset Frames is not directed at children under 16, and we do not knowingly collect personal data from children. If you believe a child has created an account, contact us and we will delete it. Minors should use the app's optional account features only with a guardian's consent.",
    ],
  },
  {
    heading: "11. App permissions",
    paragraphs: ["The app requests only what its features need:"],
    bullets: [
      "Notifications — for the daily reminders, streak alerts, and weekly recaps you configure. All are generated on-device; there is no push server.",
      "Internet & network state — used solely for the optional cloud backup.",
      "Boot completed — to re-schedule your reminders after a device restart.",
      "No camera, microphone, location, contacts, or storage access beyond saving a share image you explicitly export.",
    ],
  },
  {
    heading: "12. Security",
    paragraphs: [
      "Every cloud row is isolated per account with strict row-level security; sign-in tokens are encrypted at rest on your device; HUAWEI ID sign-ins are verified server-side against Huawei's account service before any account is issued; and all connections use HTTPS. No method of storage is 100% secure, but we design so that even our own server code can access only what it must.",
    ],
  },
  {
    heading: "13. Changes to this policy",
    paragraphs: [
      "If we change this policy, the new version will be posted on this page with an updated effective date. Material changes will also be announced inside the app before they take effect.",
    ],
  },
  {
    heading: "14. Contact",
    paragraphs: [
      `Developer: ${site.developerName}`,
      `App: Mindset Frames (${site.packageName})`,
      `Email: ${site.supportEmail}`,
      `Website: ${site.domain}`,
    ],
  },
];

const sectionsZh: PolicySection[] = [
  {
    heading: "1. 我们是谁",
    paragraphs: [
      `"Mindset Frames" 是一款为 Android 打造的、以情绪优先的习惯打卡应用（包名 ${site.packageName}），由 ${site.developerName}（"我们"）在华为应用市场发布。本隐私政策说明 Mindset Frames 应用及本网站处理哪些数据、原因是什么，以及您可以做出的选择。`,
      `您可以随时通过 ${site.supportEmail} 与我们联系。`,
    ],
  },
  {
    heading: "2. 简要说明",
    bullets: [
      "Mindset Frames 采用本地优先设计：您的习惯、打卡记录、心情与感想默认仅保存在您的设备上。",
      "云备份为可选功能，只有在您创建账户并登录后才会启用。",
      "我们不展示广告，不接入任何广告或统计分析 SDK，也绝不会为营销目的出售或共享您的数据。",
      "您可以随时在应用内永久删除您的账户及所有云端数据。",
      "应用服务器托管在中国大陆以外地区。",
    ],
    paragraphs: [],
  },
  {
    heading: "3. 存储在您设备上的数据",
    paragraphs: [
      "应用会将您的习惯、每日打卡、心情记录、单行感想、已获得的徽章、伙伴形象及设置保存在设备的私有存储空间中。除非您启用云备份，这些数据不会离开您的设备。卸载应用会将其一并删除。",
    ],
  },
  {
    heading: "4. 启用云备份时处理的数据",
    paragraphs: ["如果您选择创建账户，我们仅处理运行备份与恢复所必需的最少数据："],
    bullets: [
      "邮箱登录：您的邮箱地址和密码。密码仅以加盐哈希形式由我们的身份验证服务商存储——我们从不以明文形式查看或保存密码。",
      "华为账号登录：在您同意的前提下，仅获取必要的最小权限范围——您的 OpenID 标识符及邮箱地址。登录信息会在服务器端通过华为账号服务进行验证。我们不会访问您的通讯录、电话号码或华为账号中的其他个人资料。",
      "备份内容：您的习惯、打卡日期、心情记录及应用设置，与一个随机账户标识符相关联。",
      "用于同步管理的随机安装设备标识符（UUID）。该标识符不是广告 ID，也不来源于任何硬件标识符。",
    ],
  },
  {
    heading: "5. 处理目的与法律依据",
    paragraphs: [
      "我们处理账户数据是为了提供备份、恢复及多设备同步服务（履行合同所需），并为保障账户安全（合法利益），且仅在您主动登录之后进行（基于同意，您可随时通过退出登录或删除账户撤回同意）。我们不会对您进行画像分析，也不会对您做出任何自动化决策。",
    ],
  },
  {
    heading: "6. 数据存储位置",
    paragraphs: [
      "云端数据存储在由 Supabase 托管的 PostgreSQL 数据库中，服务器位于中国大陆以外地区。传输中的所有数据均通过 TLS 1.2 及以上协议进行加密保护。在您的设备上，登录会话令牌通过 Android Keystore 中的硬件级密钥进行加密封存。",
    ],
  },
  {
    heading: "7. 第三方服务",
    paragraphs: ["我们仅依赖两家服务提供商，且每家仅接收必要的信息："],
    bullets: [
      "Supabase（身份验证与数据库托管）——接收您的账户邮箱或基于华为账号衍生的账户身份，以及传输过程中已加密的备份内容。",
      "华为账号服务（可选的华为账号登录）——按照华为自身的隐私声明处理您的华为账号登录信息；我们仅接收经过验证的 OpenID 与邮箱地址。",
    ],
  },
  {
    heading: "8. 数据保留与删除",
    paragraphs: [
      "只要您的账户存在，云端数据就会被保留。在设置中选择\"删除账户\"将通过单次服务器端事务永久清除您拥有的所有云端记录——包括习惯、打卡记录、心情数据、设置及账户本身。本地数据会保留在您的设备上，直到您清除应用存储或卸载应用。",
    ],
  },
  {
    heading: "9. 您的权利",
    paragraphs: [
      "根据您所在地区的适用法律（包括欧洲经济区/英国的 GDPR，以及其他地区的类似个人信息保护规定），您有权访问、更正、导出、限制处理或删除您的个人数据，并可随时撤回同意。其中大部分操作可在应用内自助完成；如有其他需求，请发邮件联系我们，我们将在 30 天内回复。您也可以向当地监管机构投诉。",
    ],
  },
  {
    heading: "10. 儿童",
    paragraphs: [
      "Mindset Frames 并非面向 16 岁以下儿童设计，我们也不会在明知情况下收集儿童的个人数据。如果您认为某位儿童创建了账户，请联系我们，我们将予以删除。未成年人应在监护人同意的情况下使用应用的可选账户功能。",
    ],
  },
  {
    heading: "11. 应用权限",
    paragraphs: ["应用仅请求其功能所需的权限："],
    bullets: [
      "通知——用于您所设置的每日提醒、连续打卡提醒及每周总结，均在设备端生成，不涉及任何推送服务器。",
      "网络与网络状态——仅用于可选的云备份功能。",
      "开机自启动——用于在设备重启后重新安排您的提醒。",
      "不会访问摄像头、麦克风、位置信息、通讯录，也不会访问除您主动导出分享图片以外的其他存储权限。",
    ],
  },
  {
    heading: "12. 安全措施",
    paragraphs: [
      "每一条云端数据均通过严格的行级安全策略按账户隔离；登录令牌在您的设备上加密存储；华为账号登录会在服务器端通过华为账号服务验证后才会创建账户；所有连接均使用 HTTPS。没有任何存储方式能做到绝对安全，但我们的设计确保即使是我们自己的服务器代码，也只能访问其必需的最小范围数据。",
    ],
  },
  {
    heading: "13. 政策变更",
    paragraphs: [
      "如果我们变更本政策，新版本将发布在本页面并更新生效日期。重大变更还将在应用内于生效前进行公告。",
    ],
  },
  {
    heading: "14. 联系方式",
    paragraphs: [
      `开发者：${site.developerName}`,
      `应用：Mindset Frames（${site.packageName}）`,
      `邮箱：${site.supportEmail}`,
      `网站：${site.domain}`,
    ],
  },
];

export default function PrivacyPolicy() {
  return (
    <PolicyLayout
      title="Privacy Policy"
      updatedLabel={`Effective date: ${site.privacyEffectiveDate} · Mindset Frames`}
      backLabel="Back to mindsetframes.online"
      sections={sections}
      zhTitle="隐私政策"
      zhUpdatedLabel={`生效日期：${site.privacyEffectiveDate} · Mindset Frames`}
      zhSections={sectionsZh}
    />
  );
}
