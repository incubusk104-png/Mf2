# Huawei Health Kit — Setup, Configuration & Sandbox Testing Guide

## Overview

This guide walks you through **everything** you need to do in your
[AppGallery Connect](https://developer.huawei.com/consumer/en/console)
developer account to enable Huawei Health Kit for the **Mindset Frames**
app (`com.rork.mindsetframestracker`). After completing this guide your app
will be able to read step counts from Huawei Health on your test device —
**without uploading the APK to AppGallery first**.

### What Health Kit Does in This App

- Reads **today's step count** from the Huawei Health app on the user's device
- Books those steps as an activity record on any fitness habit (walk, run, hike, etc.)
- Auto-sync option pulls steps silently each time the app launches
- **Free for all users** — no premium subscription required

### Prerequisites

| Requirement | Details |
|---|---|
| **Huawei Developer account** | Registered at [developer.huawei.com](https://developer.huawei.com) |
| **AppGallery Connect project** | With your app (`com.rork.mindsetframestracker`) already registered |
| **Test device** | Huawei phone with HMS Core, OR any Android phone with HMS Core sideloaded |
| **Huawei Health app** | Installed on the test device and tracking steps |
| **Android Studio** | To build and install the debug APK |
| **Signing keystore** | The same keystore you used when registering the app in AGC |

---

## Part 1: Enable Health Kit API in AppGallery Connect

### Step 1.1 — Open Your Project in AppGallery Connect

1. Go to [AppGallery Connect Console](https://developer.huawei.com/consumer/en/console)
2. Click **My projects**
3. Select your project (the one containing `com.rork.mindsetframestracker`)
4. You should see your app listed under the project

### Step 1.2 — Enable the Health Kit API

1. In the left sidebar, click **Manage APIs**
2. You'll see a long list of HMS APIs (Account Kit, Push Kit, IAP, etc.)
3. **Find "Health Kit"** in the list — use the search box if needed
4. Toggle the switch **ON** to enable it
5. If you see a popup asking you to confirm, click **Enable**

> **Screenshot reference**: The Manage APIs page shows a toggle next to each
> API name. When enabled, the toggle turns blue/green.

### Step 1.3 — Apply for Health Kit Data Access

After enabling the API, you need to **apply for specific data scopes**
(this is a Huawei policy requirement for health data):

1. Stay in **AppGallery Connect** > your project
2. In the left sidebar, click **Health Kit** (it appears after you enable the API)
   - If you don't see it in the sidebar, try: **Develop** > **Health Kit**
   - Or look for an **"Apply for Health Kit"** button on the API page
3. Click **Apply for Health Kit**
4. You'll see a form with data scope checkboxes. **Check these scopes**:

   | Scope | Required? | Why |
   |---|---|---|
   | **Step count (read)** | ✅ Yes | Our app reads `DT_CONTINUOUS_STEPS_DELTA` |
   | ~~Calories (read)~~ | Optional | Not used yet, but useful for future features |
   | ~~Heart rate (read)~~ | Optional | Not used yet |
   | ~~Distance (read)~~ | Optional | Not used yet |

5. Fill in:
   - **Reason for application**: e.g., "The app tracks daily fitness habits
     and needs to read step counts from Huawei Health to log walking/running
     activity automatically."
   - **Privacy policy URL**: Your app's privacy policy link
6. Click **Submit**
7. **Wait for approval** — Huawei reviews Health Kit applications (usually
   1-3 business days). You'll receive an email when approved.

> ⚠️ **Important**: Health Kit data access requires Huawei's approval.
> You **cannot** read health data until this application is approved.
> However, you can proceed with all other setup steps while waiting.

### Step 1.4 — Verify Health Kit Is Enabled

After approval:

1. Go back to **Manage APIs**
2. Confirm **Health Kit** toggle is ON
3. Go to **Health Kit** in the sidebar
4. You should see your approved data scopes listed with a green
   "Approved" status

---

## Part 2: SHA-256 Signing Certificate Fingerprint

Huawei uses the **SHA-256 fingerprint** of your signing certificate to
verify that API calls are coming from your authentic app — not a modified
copy. This is **required** for Health Kit (and all HMS APIs) to work.

### Step 2.1 — Get Your Debug Keystore SHA-256

For **local testing with debug builds**, you need the debug keystore
fingerprint:

```bash
# Default debug keystore location (macOS/Linux):
keytool -list -v \
  -keystore ~/.android/debug.keystore \
  -alias androiddebugkey \
  -storepass android \
  -keypass android

# Default debug keystore location (Windows):
keytool -list -v ^
  -keystore "%USERPROFILE%\.android\debug.keystore" ^
  -alias androiddebugkey ^
  -storepass android ^
  -keypass android
```

Look for the line that says:

```
SHA256: XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX
```

**Copy the entire SHA-256 value** (including the colons).

### Step 2.2 — Get Your Release Keystore SHA-256

For the **production/release APK**, use your release keystore:

```bash
keytool -list -v \
  -keystore /path/to/your/release-keystore.jks \
  -alias your-key-alias
# Enter your keystore password when prompted
```

Copy the SHA-256 value.

### Step 2.3 — Add the Fingerprint(s) to AppGallery Connect

1. Go to **AppGallery Connect** > **My projects** > your project
2. Click **Project settings** (gear icon in the top-left area)
3. Scroll down to **App information**
4. Find the field **SHA-256 certificate fingerprint**
5. **Paste your SHA-256** value (with colons, e.g., `A1:B2:C3:...`)
6. Click the **+** or **Add** button to save it
7. **Add BOTH fingerprints** if you have debug and release keystores:
   - Debug SHA-256 (for local testing)
   - Release SHA-256 (for AppGallery uploads)
8. Click **Save**

> ⚠️ **Critical**: If the SHA-256 fingerprint in AGC doesn't match the
> keystore that signed your APK, **all HMS API calls will fail silently**.
> This is the #1 cause of "Health Kit not working" issues.

### Step 2.4 — Re-download agconnect-services.json

After adding the fingerprint, the `agconnect-services.json` file is
regenerated with updated configuration:

1. Still on the **Project settings** page
2. Scroll down to **App information**
3. Click the **agconnect-services.json** download button
4. Save the file and place it at:
   ```
   android/app/agconnect-services.json
   ```
5. **Replace** the existing file (if any)

> The build system automatically copies this file into the APK's assets
> folder during compilation (configured in `build.gradle.kts`).

---

## Part 3: agconnect-services.json Configuration

### Step 3.1 — Verify the File Contents

Open `android/app/agconnect-services.json` and verify these critical fields:

```json
{
  "client": {
    "app_id": "YOUR_APP_ID",
    "client_id": "YOUR_CLIENT_ID",
    "package_name": "com.rork.mindsetframestracker",
    ...
  },
  ...
}
```

| Field | Must match |
|---|---|
| `client.app_id` | Your app's numeric ID from AGC |
| `client.client_id` | Your app's OAuth client ID from AGC |
| `client.package_name` | `com.rork.mindsetframestracker` — exactly |

### Step 3.2 — How the App Loads It

The app's `HuaweiServicesConfig.kt` class:

1. Reads `agconnect-services.json` from the APK's `assets/` folder at startup
2. Validates `client.app_id`, `client.client_id`, and `client.package_name`
3. Initializes the AGConnect SDK
4. Sets `isConfigured = true` on success

If anything is wrong, the app shows a helpful error message in the
Settings screen and falls back to email-only mode — it never crashes.

### Step 3.3 — Troubleshooting Config Issues

| Symptom | Likely Cause | Fix |
|---|---|---|
| "No agconnect-services.json bundled in the APK" | File missing from `android/app/` | Re-download from AGC and place it there |
| "Missing client/app_id or client/client_id" | Corrupted or incomplete JSON | Re-download a fresh copy from AGC |
| "package_name doesn't match" | Wrong app selected in AGC | Download the JSON for the correct app |
| AGConnect initialization exception | Outdated JSON after API changes | Re-download after enabling Health Kit |

---

## Part 4: Sandbox Testing on Your Device

### Step 4.1 — Create Sandbox Test Accounts

1. Go to **AppGallery Connect** > **Users and permissions** > **Sandbox testing** > **Test accounts**
2. Click **Add test account**
3. Enter a valid email address
4. Complete the email verification
5. The account is now a sandbox tester

> **Tip**: Use the same sandbox test accounts you created for IAP testing
> (see `HUAWEI_IAP_SANDBOX_TESTING.md`). The same accounts work for both.

### Step 4.2 — Enable Sandbox Mode on the Test Device

1. Open the **HMS Core** app on your test device
2. Go to **Me** > **Settings** > **About**
3. Tap the version number **7 times** to unlock developer options
4. A **Sandbox testing** toggle appears — **enable it**
5. Sign **out** of HMS Core
6. Sign back **in** with your **sandbox test account** email

> **Verification**: When sandbox mode is active, IAP purchases will
> show a "SANDBOX" watermark. For Health Kit, sandbox mode ensures the
> test account has the proper authorization scope.

### Step 4.3 — Install Huawei Health on the Test Device

Health Kit reads data **from the Huawei Health app**. Make sure it's set up:

1. Install **Huawei Health** from AppGallery (or sideload it)
2. Open Huawei Health and complete the initial setup
3. **Walk around** to generate some step data (or wait a few minutes —
   the phone's built-in pedometer accumulates steps automatically)
4. Verify: Open Huawei Health > **Health** tab > you should see today's
   step count > 0

> **Without step data in Huawei Health, the app will return `null`
> (no data available) when reading steps. This is expected, not an error.**

### Step 4.4 — Build and Install the Debug APK

```bash
cd android

# Clean build
./gradlew clean

# Build the debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Make sure** the debug keystore's SHA-256 fingerprint is registered
> in AGC (Step 2.3 above). If it's not, HMS APIs will reject every call.

### Step 4.5 — Test the Health Kit Connection

1. Open the **Mindset Frames** app on your test device
2. Go to **Settings** (or the activity sync section)
3. Find the **Huawei Health** connection option
4. Tap **Connect**
5. **Expected**: The Huawei Health Kit authorization screen appears,
   asking you to grant permission to read step data
6. Tap **Allow** / **Authorize**
7. **Expected**: You see "Huawei Health connected." confirmation

### Step 4.6 — Test Step Sync

1. After connecting, go to any fitness habit (Walk, Run, Hike, etc.)
2. Trigger a manual sync (or wait for auto-sync on next app launch)
3. **Expected**: "Today's steps synced from Huawei Health." message
4. The habit's activity record should show today's step count

### Step 4.7 — Testing on Non-Huawei Devices (Sideloaded HMS)

If you're testing on a Samsung, Pixel, or other non-Huawei device:

1. Sideload **HMS Core** APK (download from Huawei's official site)
2. Sideload **Huawei Health** APK
3. Sign into both with your sandbox test account
4. The app has a **"code 1" fix** (`HuaweiAuthClient.kt`) that detects
   sideloaded HMS Core via a package-manager probe, so the "HMS not
   available" error won't block you

> **Known limitation**: Some HMS features may be flaky on sideloaded
> devices. If Health Kit authorization fails, try on a native Huawei
> device first to confirm it's not a code issue.

---

## Part 5: Verifying Your Premium/Upgrade Plan Integration

The Health Kit integration in Mindset Frames is **free for all users**
(not behind the premium paywall). However, if you're testing the full
upgrade flow alongside Health Kit:

### Step 5.1 — Test the Complete Flow

1. **Fresh install** the debug APK on your sandbox-enabled device
2. Sign in with your sandbox test account (via Huawei ID)
3. Connect Huawei Health Kit (step sync should work immediately)
4. Purchase the premium subscription (sandbox — no real charge)
5. Verify: both Health Kit sync AND premium features work together
6. Force-close and reopen the app
7. Verify: auto-sync runs on startup (check logcat)
8. Verify: premium subscription is still active (restore works)

### Step 5.2 — Subscription + Health Kit Sandbox Timeline

In sandbox mode, subscription periods are compressed:

| Real Period | Sandbox Period |
|---|---|
| 1 week | 3 minutes |
| 1 month | 5 minutes |
| 1 year | 15 minutes |

Use this to test:
- Health Kit sync while premium is active
- Health Kit sync after premium expires (should still work — it's free)
- Re-subscribing and verifying sync isn't disrupted

---

## Part 6: Debugging & Common Issues

### 6.1 — Essential Logcat Commands

```bash
# Watch all Huawei Health Kit logs
adb logcat -s HuaweiHealthKitClient

# Watch Huawei auth + config logs
adb logcat -s HuaweiAuthClient HuaweiServicesConfig

# Watch everything Huawei-related
adb logcat | grep -iE "huawei|hms|hihealth|agconnect|healthkit"

# Watch the app's Health Kit + ViewModel sync logs
adb logcat -s HuaweiHealthKitClient AppViewModel

# Full verbose HMS logging
adb logcat | grep -iE "HMS|HiHealth|HealthKit|AGConnect"
```

### 6.2 — Common Error Codes & Fixes

| Symptom | Error in Logcat | Cause | Fix |
|---|---|---|---|
| Auth screen doesn't appear | `requestAuthorization failed` | Health Kit API not enabled in AGC | Enable it in Manage APIs (Step 1.2) |
| Auth screen shows but fails | `parseAuthResult failed` | Health Kit data scope not approved | Check Health Kit application status (Step 1.3) |
| "No step data available" | `readTodaySummation failed` | No steps in Huawei Health today | Walk around or check Huawei Health app |
| "No step data available" | `readTodaySteps unavailable` | HMS Core not properly initialized | Re-download agconnect-services.json (Step 2.4) |
| Silent failure (no error, no data) | No relevant logs | SHA-256 fingerprint mismatch | Add debug keystore fingerprint in AGC (Step 2.3) |
| "Huawei sign-in isn't set up" | `agconnect-services.json not configured` | Missing or invalid config file | Re-download and place in `android/app/` |
| "HMS not available (code 1)" | `HMS availability check returned code 1` | Non-Huawei device without sideloaded HMS | Install HMS Core APK |
| Auth works but no step data | `readTodaySummation` returns empty | User didn't grant step-read scope | Disconnect and reconnect Health Kit |

### 6.3 — Health Kit Error Code Reference

| HMS Error Code | Meaning | Action |
|---|---|---|
| `50005` | Health Kit not enabled | Enable in AGC Manage APIs |
| `50007` | Data scope not authorized | Apply for Health Kit data scope |
| `50009` | User denied authorization | User must tap "Allow" on the consent screen |
| `50010` | App not authorized | SHA-256 mismatch or AGC config issue |
| `907135000` | HMS Core not installed | Install/update HMS Core on device |
| `907135003` | HMS Core outdated | Update HMS Core from AppGallery |

### 6.4 — Nuclear Reset (When Nothing Works)

If you've tried everything and Health Kit still isn't working:

1. **Clear HMS Core data**: Settings > Apps > HMS Core > Clear Data
2. **Clear Huawei Health data**: Settings > Apps > Huawei Health > Clear Data
3. **Uninstall and reinstall** your debug APK
4. **Re-download** `agconnect-services.json` from AGC
5. **Rebuild** the APK from scratch: `./gradlew clean assembleDebug`
6. **Re-enable sandbox mode** in HMS Core (Step 4.2)
7. **Re-sign in** with the sandbox test account
8. Try connecting Health Kit again

---

## Part 7: Step-by-Step Checklist (Quick Reference)

Use this checklist to track your progress:

### AppGallery Connect Console

- [ ] **Manage APIs** → Health Kit toggle is ON
- [ ] **Health Kit** → Applied for data scopes (step count read at minimum)
- [ ] **Health Kit** → Application status is "Approved"
- [ ] **Project settings** → Debug SHA-256 fingerprint added
- [ ] **Project settings** → Release SHA-256 fingerprint added
- [ ] **Project settings** → Downloaded fresh `agconnect-services.json`

### Local Project

- [ ] `agconnect-services.json` placed at `android/app/agconnect-services.json`
- [ ] Verified JSON contains correct `app_id`, `client_id`, `package_name`
- [ ] Built debug APK successfully (`./gradlew assembleDebug`)

### Test Device

- [ ] HMS Core installed and updated
- [ ] Sandbox test account created in AGC
- [ ] HMS Core sandbox mode enabled (tapped version 7 times)
- [ ] Signed into HMS Core with sandbox test account
- [ ] Huawei Health installed and showing step data
- [ ] Debug APK installed on device
- [ ] Health Kit connection successful in app
- [ ] Step sync returns data

### Full Integration Test

- [ ] Fresh install → Sign in → Connect Health Kit → Steps sync
- [ ] Auto-sync works on app restart
- [ ] Premium purchase works alongside Health Kit
- [ ] Health Kit still works after premium expires
- [ ] Disconnect and reconnect Health Kit works

---

## Part 8: Code Architecture Reference

For developers modifying the Health Kit integration, here's how the
pieces connect:

```
┌─────────────────────────────────────────────────────────┐
│                    MainActivity.kt                       │
│  onActivityResult(HEALTH_AUTH_REQUEST_CODE)              │
│    → HuaweiHealthKitClient.parseAuthResult()            │
│    → AppViewModel.onHealthKitAuthResult(granted)        │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│               AppViewModel.kt                            │
│  onHealthKitAuthResult() — saves connected state         │
│  syncHealthKitToHabit() — reads steps + saves record     │
│  runAutoSync() — auto-sync on app launch                 │
│  disconnectHealthKit() — clears connection state          │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│           HuaweiHealthKitClient.kt                       │
│  requestAuthorization() — launches consent screen        │
│  parseAuthResult() — processes consent result            │
│  readTodaySteps() — DataController.readTodaySummation    │
│  syncTodayToHabit() — reads steps + saves ActivityRecord │
│  supportedActivityIconIds — set of trackable icon IDs    │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│           HuaweiServicesConfig.kt                        │
│  initialize() — reads agconnect-services.json            │
│  isConfigured — true when AGConnect SDK is ready          │
│  lastError — human-readable reason if init failed        │
└─────────────────────────────────────────────────────────┘
```

### Files You'll Need to Touch

| File | What's There |
|---|---|
| `android/app/agconnect-services.json` | AGC config — download from console |
| `HuaweiHealthKitClient.kt` | Health Kit read/auth logic |
| `HuaweiServicesConfig.kt` | AGConnect initialization |
| `HuaweiAuthClient.kt` | Huawei ID sign-in (separate from Health Kit) |
| `AppViewModel.kt` | UI state management for Health Kit connection |
| `MainActivity.kt` | `onActivityResult` routing for Health Kit auth |
| `AndroidManifest.xml` | App permissions and queries |
| `build.gradle.kts` | HMS SDK dependencies |

---

## Part 9: What Happens When You Upload to AppGallery

Once local testing is complete and you're ready to upload:

1. **Switch to release keystore** — the release SHA-256 must be in AGC
2. **Build the release AAB/APK**:
   ```bash
   cd android
   ./gradlew bundleRelease
   ```
3. **Re-download** `agconnect-services.json` one final time (in case
   anything changed during testing)
4. **Upload to AppGallery Connect** > Distribute > create new release
5. **Declare Health Kit usage** in the submission form:
   - Under "Permissions used", mention Health Kit step reading
   - Link your privacy policy that covers health data access
6. **Submit for review**

> Health Kit access that was approved in sandbox carries over to
> production — you don't need to re-apply. But Huawei's app review
> may ask additional questions about health data usage in your app's
> privacy policy.

---

## Appendix: HMS SDK Versions in This Project

| Dependency | Version | Purpose |
|---|---|---|
| `com.huawei.hms:health` | 6.11.0.300 | Health Kit (step reading) |
| `com.huawei.hms:base` | 6.11.0.300 | HMS Core base framework |
| `com.huawei.hms:iap` | 6.11.0.300 | In-App Purchases |
| `com.huawei.hms:hwid` | (via libs catalog) | Account Kit (Huawei ID sign-in) |
| `com.huawei.agconnect:agconnect-core` | (via libs catalog) | AGConnect SDK |

These are declared in `android/app/build.gradle.kts`.
