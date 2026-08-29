# Huawei IAP Sandbox Testing & Update Upload Guide

## Overview

This guide covers how to set up the Huawei IAP (In-App Purchase) Sandbox
environment for testing **consumable** and **auto-renewable subscription**
products locally before publishing to AppGallery.

---

## Part 1: Sandbox Environment Setup

### 1.1 Prerequisites

- **AppGallery Connect** account with the app registered
- **agconnect-services.json** downloaded and placed at `android/app/agconnect-services.json`
- **Huawei device** (or non-Huawei device with HMS Core sideloaded — our
  code-1 fix now supports this)
- **HMS Core** installed and signed in with a **sandbox test account**

### 1.2 Create Sandbox Test Accounts

1. Go to **AppGallery Connect** > **Users and permissions** > **Sandbox testing** > **Test accounts**
2. Click **Add test account**
3. Enter a valid email address (you'll receive a verification code)
4. Complete verification — the account is now a sandbox tester
5. **Repeat** for any additional testers on your team

> **Important**: The Huawei ID you sign into HMS Core on the test device
> must match one of these sandbox test accounts. Regular Huawei IDs will
> NOT trigger the sandbox payment sheet.

### 1.3 Enable Sandbox on the Test Device

1. Open **HMS Core** app on the device
2. Go to **Me** > **Settings** > **About**
3. Tap the version number **7 times** to unlock developer options
4. A **Sandbox testing** toggle appears — enable it
5. Sign out of HMS Core and sign back in with your **sandbox test account**

> **Verification**: When sandbox is active, purchases show a "SANDBOX"
> watermark on the payment sheet and are never charged.

### 1.4 Configure Products in AppGallery Connect

#### Consumable Products (e.g., tips/coins)

1. Go to **AppGallery Connect** > **My apps** > your app > **Earn** > **In-App Purchases**
2. Click **Add product**
3. Set:
   - **Product type**: Consumable
   - **Product ID**: e.g., `tip_small`, `tip_medium`, `tip_large`
   - **Default price**: your price tier
   - **Product name**: descriptive name
4. Click **Save** then **Activate**

#### Auto-Renewable Subscriptions

1. Same navigation > **Add product**
2. Set:
   - **Product type**: Auto-renewable subscription
   - **Product ID**: `mindset_premium_monthly` or `mindset_premium_yearly`
   - **Subscription group**: create one called "Mindset Frames Premium"
   - **Subscription period**: 1 month or 1 year
   - **Default price**: your price tier
   - **Grace period**: recommended 3 days
   - **Free trial**: optional (e.g., 7-day free trial)
3. Click **Save** then **Activate**

> **Note on sandbox subscriptions**: In sandbox mode, subscription periods
> are compressed:
> - 1 week → 3 minutes
> - 1 month → 5 minutes
> - 1 year → 15 minutes
>
> This lets you test renewal and expiration cycles quickly.

---

## Part 2: Testing Consumable Purchases

### 2.1 The Purchase Flow

```
User taps "Tip" → TipBilling.purchase() → HMS IAP payment sheet
  → Sandbox account "pays" (no real charge)
  → onActivityResult receives PurchaseResultInfo
  → TipBilling consumes the purchase (REQUIRED for consumables)
  → Success toast shown
```

### 2.2 Testing Steps

1. Build and install the debug APK on a device with sandbox enabled
2. Sign into the app with any account (email or Huawei ID)
3. Navigate to the Tip/Support section
4. Tap a tip amount
5. **Expected**: HMS sandbox payment sheet appears with "SANDBOX" tag
6. Complete the purchase
7. **Expected**: `TipBilling.consumePurchase()` runs immediately
8. Check the Huawei HMS logs: `adb logcat -s TipBilling`

### 2.3 Verifying Consumption

Consumable purchases **MUST** be consumed or they block future purchases
of the same product. Our code handles this in two places:

- **Immediate**: `TipBilling.onActivityResult` → consume after success
- **Cleanup**: `TipBilling.consumeUnfinishedPurchases()` at app startup
  catches any orphaned purchases (e.g., process killed mid-flow)

To verify: purchase the same tip product twice in a row. If the second
purchase sheet opens, consumption is working.

---

## Part 3: Testing Auto-Renewable Subscriptions

### 3.1 The Subscription Flow

```
User taps "Go Premium" → SubscriptionBilling.purchase()
  → HMS IAP subscription sheet
  → Sandbox account "subscribes"
  → onActivityResult → SubscriptionResult.Success
  → AppViewModel.grantSubscription() sets isPremium = true
  → Premium features unlock immediately
```

### 3.2 Testing Steps

1. Tap **Go Premium — Monthly** in the Premium sheet
2. **Expected**: HMS sandbox subscription sheet with "SANDBOX" tag
3. Complete the subscription
4. **Expected**: Premium badge appears, all features unlock
5. Wait 5 minutes (sandbox month) — the subscription auto-renews
6. Verify: `SubscriptionBilling.queryActiveSubscription()` still returns
   `RestoreResult.Active`

### 3.3 Testing Subscription Expiration

1. In **AppGallery Connect** > **Sandbox testing** > **Subscriptions**
2. Find the test subscription and cancel it
3. Wait for the current sandbox period to expire
4. Force-close and reopen the app
5. **Expected**: `restoreSubscriptionSilently()` detects no active sub
   and revokes premium (only if `subscriptionProductId` is set)

### 3.4 Testing Restore Purchase

1. Uninstall and reinstall the app (or clear data)
2. Sign in with the same sandbox account
3. Open the Premium sheet
4. Tap **Restore purchase**
5. **Expected**: `SubscriptionBilling.queryActiveSubscription()` finds
   the active sandbox subscription and grants premium

---

## Part 4: Common Issues & Debugging

### "Purchase not available" error

- **Cause**: Product not activated in AppGallery Connect
- **Fix**: Ensure the product status is "Activated" (not draft)

### "Order failed" or error code 1

- **Cause**: HMS Core not in sandbox mode, or signed in with a non-test account
- **Fix**: Re-enable sandbox mode and sign in with the sandbox test account

### Payment sheet doesn't appear

- **Cause**: `agconnect-services.json` mismatch or HMS Core outdated
- **Fix**: Re-download `agconnect-services.json` and update HMS Core

### Subscription won't renew in sandbox

- **Cause**: Sandbox periods are very short — you might have missed it
- **Fix**: Check AppGallery Connect > Sandbox > Subscription history

### Consumable purchase stuck (can't repurchase)

- **Cause**: Previous purchase wasn't consumed
- **Fix**: The app's startup cleanup should handle this. If not, check:
  ```
  adb logcat -s TipBilling | grep "consume"
  ```

---

## Part 5: Uploading as an Update (Avoiding Common Issues)

### 5.1 Pre-Upload Checklist

Before uploading a new version to AppGallery Connect:

- [ ] **Increment `versionCode`** in `android/app/build.gradle.kts`
  ```kotlin
  versionCode = 12  // Must be higher than the previous upload
  versionName = "1.2.0"
  ```

- [ ] **Verify signing config** matches the original upload
  - Same keystore file, same key alias
  - If lost, you'll need to request a key reset from Huawei support

- [ ] **agconnect-services.json** is current
  - Re-download from AppGallery Connect if you changed any API settings
  - The `client/app_id` must match your AppGallery app

- [ ] **Test the release APK/AAB** on a real device before uploading
  ```bash
  ./gradlew assembleRelease
  adb install -r app/build/outputs/apk/release/app-release.apk
  ```

- [ ] **New permissions?** If you added permissions (e.g., Health Connect),
  declare them in the AppGallery submission form under "Permissions"

- [ ] **New IAP products?** Ensure they're created AND activated in
  AppGallery Connect BEFORE uploading the APK that references them

### 5.2 Build the Release Bundle

```bash
cd android
./gradlew bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

Or for APK:
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### 5.3 Upload to AppGallery Connect

1. Go to **AppGallery Connect** > **My apps** > your app
2. Click **Distribute** > **Draft** (or create a new release)
3. Upload the `.aab` or `.apk` file
4. Fill in:
   - **Version description**: what's new in this update
   - **Country/region**: where to distribute
5. Click **Submit for review**

### 5.4 Common Upload Failures

| Error | Cause | Fix |
|-------|-------|-----|
| "Version code must be greater" | Same or lower `versionCode` | Increment in build.gradle.kts |
| "Signing certificate mismatch" | Different keystore | Use the original keystore |
| "Package name mismatch" | `applicationId` changed | Revert to original package name |
| "Missing privacy description" | New permissions without explanation | Add permission descriptions in the submission form |
| "IAP product not found" | Code references a product ID not in AppGallery Connect | Create and activate the product first |

### 5.5 Post-Upload Verification

1. Wait for review approval (usually 1-3 business days)
2. Once approved, install the update from AppGallery on a test device
3. Verify:
   - [ ] Existing users keep their local data after update
   - [ ] Existing premium subscribers stay premium (restore works)
   - [ ] New IAP products appear in the Premium sheet
   - [ ] HMS Core sign-in still works (agconnect-services.json matches)
   - [ ] Activity integrations (Strava, Health) retain their connection state

---

## Part 6: Quick Reference — Product IDs

| Product ID | Type | Description |
|---|---|---|
| `mindset_premium_monthly` | Auto-renewable | Premium monthly |
| `mindset_premium_yearly` | Auto-renewable | Premium yearly |
| `mindset_premium_founding_monthly` | Auto-renewable | Founding member monthly |
| `mindset_premium_founding_yearly` | Auto-renewable | Founding member yearly |
| `tip_small` | Consumable | Small tip |
| `tip_medium` | Consumable | Medium tip |
| `tip_large` | Consumable | Large tip |

---

## Part 7: Environment Variables & Secrets

### Supabase Edge Function Secrets (set via CLI or dashboard)

```bash
supabase secrets set GEMINI_API_KEY="your-gemini-api-key"
supabase secrets set STRAVA_CLIENT_ID="your-strava-client-id"
supabase secrets set STRAVA_CLIENT_SECRET="your-strava-client-secret"
```

### Android Build Config (set in build.gradle.kts or CI env)

```
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=eyJ...
STRAVA_CLIENT_ID=12345
```

These are injected into `BuildConfig` at compile time and are safe to
include in the APK (they're public keys — secrets stay server-side).
