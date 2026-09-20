# Mf2 Palette — Handoff Token Map

**Source of truth:** `android/app/src/main/java/com/rork/mindsetframestracker/ui/theme/Palette.kt` → `object Mf2Palette`

All values below were sampled **verbatim** from the user's reference screenshot
(1080×2400, the dark-mode "Your habits" screen), not invented.

---

## 1. The six habit-card colours (dark mode = exact reference samples)

| Reference swatch | Token | Hex | Where it appears |
|---|---|---|---|
| dark olive green | `Mf2Palette.HabitDark[HabitColor.OLIVE]` | `#283120` | card 1 — To-Do List |
| dark slate blue | `Mf2Palette.HabitDark[HabitColor.SLATE]` | `#1A2E37` | card 2 — Drink Water |
| deep forest green | `Mf2Palette.HabitDark[HabitColor.FOREST]` | `#2B3123` | card 3 — Walk |
| dark indigo / purple | `Mf2Palette.HabitDark[HabitColor.INDIGO]` | `#222431` | card 4 — Sleep Early |
| dark teal | `Mf2Palette.HabitDark[HabitColor.TEAL]` | `#1A2C2C` | card 5 — Stretch |
| dark chocolate brown | `Mf2Palette.HabitDark[HabitColor.CHOCOLATE]` | `#382D1B` | card 6 — Journal |

Light-mode equivalents (already in the app's visual language, seed @ 20 % over cream):

| Token | Hex |
|---|---|
| `HabitLight[OLIVE]` | `#DDDCC6` |
| `HabitLight[FOREST]` | `#D8D8C6` |
| `HabitLight[SLATE]` | `#D1D8D5` |
| `HabitLight[INDIGO]` | `#D7D1D6` |
| `HabitLight[TEAL]` | `#D1DBD2` |
| `HabitLight[CHOCOLATE]` | `#E4D6C5` |

**Use the helpers, not the maps, in UI code:**

```kotlin
Mf2Palette.habitCardBackground(icon.colorHex, isDark)  // card fill
Mf2Palette.habitCardTitleColor(isDark)                 // habit name
Mf2Palette.habitCardMutedColor(isDark)                 // alarm time / subtitle
Mf2Palette.habitCardIconColor(isDark)                  // icon artwork tint
Mf2Palette.habitColorForSeed(seed)                     // -> HabitColor enum
```

`habitColorForSeed` is deterministic (HSV hue bands) and **verified against all 18
seeds in `HabitIconCatalog`** — all six buckets are reached
(chocolate 6, slate 4, indigo 3, olive 2, teal 2, forest 1).

---

## 2. Accent — the muted green

| Role | Token | Hex | Contrast |
|---|---|---|---|
| icon artwork / decorative on dark | `AccentMuted` | `#5E755B` | 2.7–3.1:1 (decorative only — never body text) |
| links, active nav, focus ring, CTA on dark | `AccentBright` | `#7E9A78` | **6.15:1** on `#140F09` ✓ AA |
| pressed / hover lift | `AccentBrightHigh` | `#8CA482` | 7.03:1 ✓ AA |
| text/icon on an accent-filled button | `OnAccent` | `#10130F` | 6.04:1 on `AccentBright` ✓ AA |
| accent on cream light surfaces | `AccentOnLight` | `#41603F` | **6.42:1** on `#FAF3E9` ✓ AA |
| accent container (dark / light) | `AccentContainerDark` `#26301F` / `AccentContainerLight` `#E3EDE0` | | |

> Note: `AccentMuted` is the sampled icon-artwork green. At 2.7:1 it is **only**
> for large decorative shapes — use `AccentBright` for anything textual.

---

## 3. Surfaces & text

| Role | Token | Hex |
|---|---|---|
| app background (dark) | `DarkBackground` | `#140F09` |
| sheet / dialog (dark) | `DarkSheet` | `#1C1610` |
| raised surface (dark) | `DarkSurface` | `#1A140B` |
| input / higher layer (dark) | `DarkSurfaceVariant` | `#2C2416` |
| nav + status chrome (dark) | `DarkChrome` | `#140F09` |
| dividers (dark) | `DarkOutlineVariant` | `#3B3222` |
| **serif headings + primary text (dark)** | `OnDarkHeading` / `OnDark` | `#F7F4EC` |
| habit-card title (dark) | `OnDarkCardTitle` | `#F7F4EC` |
| secondary text (dark) | `OnDarkMuted` | `#A7A49B` |
| card subtitle (dark) | `OnDarkCardMuted` | `#CFCBC0` |
| cream background (light) | `LightBackground` | `#FAF3E9` |
| text on cream | `OnLight` / `OnLightMuted` | `#2B241B` / `#5D5546` |

Semantic: `Success` `#7E9A78`, `Warning` `#D9A44C`, `Error` `#C4796A`, `Info` `#6E93A8`, `Attention` `#E0B054`.

Headings use `DisplayFontFamily` (DM Serif Display, `dm_serif_display.ttf`) via
`AppTypography` — already wired in `Theme.kt`. Keep headings ivory `#F7F4EC`.

---

## 4. Measured contrast (WCAG)

| Pair | Ratio | Level |
|---|---|---|
| ivory `#F7F4EC` on background `#140F09` | 17.34:1 | AAA |
| ivory on each dark habit card | 12.20 – 14.00:1 | AAA |
| secondary `#A7A49B` on background | 7.65:1 | AAA |
| card subtitle `#CFCBC0` on cards | 8.27 – 9.49:1 | AAA |
| accent bright `#7E9A78` on background | 6.15:1 | AA |
| accent on light `#41603F` on cream | 6.42:1 | AA |
| attention `#E0B054` on cards | 6.71 – 7.70:1 | AA |
| accent muted `#5E755B` on cards | 2.66 – 3.06:1 | decorative only |

---

## 5. Rule for the rest of the app

**No `Color(0x…)` literals in screens or components.** Import `Mf2Palette` and
reference a token. `Theme.kt` already aliases its neutral palette into
`Mf2Palette`, so `MaterialTheme.colorScheme` follows the tokens automatically.

Files that still inline hexes and must be migrated (19 files contain `Color(0x`):

`AlarmRingingActivity.kt`, `MainActivity.kt`, `ui/avatar/*`, and under
`ui/components/`: `AlarmActionDialog`, `AlarmConnectOffer`, `ActivityInsightSheet`,
`ActivitySourcePicker`, `AuthMessageBanner`, `AuthPromptSheet`, `BadgeSection`,
`BadgeUnlockOverlay`, `BrandLogos`, `BulkAddHabitsSheet`, `CompanionBubble`,
`CompletionHeatmap`, `DailyGoalShareCard`, `DataExportSheet`,
`HabitActivityToolsRow`, `HabitAlarmOverviewSection`, `HabitPickerGrid`,
`HabitToolInputs`, `HabitTrackingSheet`, `HuaweiSignInButton`,
`IntegrationConsentDialog`, `MilestoneCelebration`, `MinimizedHabitChip`,
`MoodBackdrop`, `MoodPicker`, `MoodPixelsCard`, `PlanCards`, `PremiumSheet`,
`ScreenTimeHabitSheet`, `SetNewPasswordSheet`, `ShareHabitsSheet`,
`SyncStatusBanner`, `ThemeToggle`, `TipBubble`, `TipSheet`.

Priority order: `HabitPickerGrid.kt` (`cardBackground` / `cardTextColor` —
replace with the `habitCard*` helpers so cards use the six reference colours),
then `AlarmActionDialog.kt` + `AlarmRingingActivity.kt`, then sheets and nav.
