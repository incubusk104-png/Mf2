# HUAWEI ID sign-in — setup and "isn't available" troubleshooting

This is the runbook for making **Sign in with HUAWEI ID** work, and for
diagnosing it when it doesn't.

---

## 1. What you were seeing, and why

The app showed:

> Huawei sign-in isn't set up yet (No agconnect-services.json bundled in the APK).
> Use email sign-in instead.

That message is accurate, and it is **not** a device problem or a bug in
Android code. The chain of causes:

1. `android/app/agconnect-services.json` used to be **committed** to this
   repository. It carries live credentials (`client_secret`, `api_key`), and the
   repository is public — so it was removed from git and gitignored. See
   `SECURITY.md`; the leaked values still need rotating.
2. Removing it from git was correct, but **nothing replaced it at build time**.
   The Gradle build only ever looked for a *local* file
   (`android/app/agconnect-services.json`), and CI had no way to supply one. So
   every APK built from CI — including the one on your phone — shipped with **no
   Huawei configuration inside it**.
3. The app then correctly reported that no config was bundled and disabled
   Huawei sign-in.

So the fix has two halves: give the build a way to receive the file (done in
code — see §3), and actually supply it (a manual step — see §4).

### The second, quieter bug that made this hard to diagnose

The build also had **hardcoded `app_id` / `cp_id` fallbacks**
(`118642709` / `30063000033888672`) that were injected into the manifest as
`com.huawei.hms.client.appid` whenever no config file was found.

That meant an APK with **no** Huawei config still advertised a **real** Huawei
app identity to HMS Core. The app looked configured from HMS's side while having
no credentials — which is why the failure surfaced as a vague "isn't set up"
message instead of an obvious packaging error, and why Huawei IAP would fail
separately with error `60002` (`ORDER_STATE_IAP_NOT_ACTIVATED`).

Those fallbacks are now **removed**. A build with no config gets a blank app id
and says so loudly instead of silently impersonating a configured app.

---

## 2. Symptom → cause, at a glance

| What you see | Cause | Fix |
|---|---|---|
| "Huawei sign-in isn't available — this app build was compiled **without** a Huawei config" | The APK was built without `agconnect-services.json`. | §4 — supply the file; §5 — supply it in CI. |
| "…the Huawei config bundled with this build **couldn't be loaded**" | A config *was* bundled but AGConnect couldn't initialise it (malformed file, or `package_name` mismatch). | Check `package_name` matches `com.mindsetframes.habittracker`; re-download the file from AGC. |
| Picker opens then **instantly closes**, no error | This build's **SHA-256 signing certificate fingerprint** isn't registered in AGC, or Account Kit isn't enabled for the app. | §6 register the fingerprint; §7 enable Account Kit. |
| Huawei **IAP** fails with `60002` | Blank `com.huawei.hms.client.appid` in the manifest — i.e. no config at build time. | §4/§5. |
| Email sign-in works, Huawei doesn't | Expected when the build has no Huawei config. | §4/§5/§6. |

The in-app link **"Huawei sign-in not working? View diagnostics"** shows the
on-device log with the exact reason plus this build's fingerprint — use it
before guessing.

---

## 3. What changed in the build (already implemented)

`android/app/build.gradle.kts` now resolves the config from the **first**
source that supplies one:

| Order | Source | Use case |
|---|---|---|
| 1 | `android/app/agconnect-services.json` (local file) | Local/dev builds. Gitignored. |
| 2 | `AGCONNECT_SERVICES_JSON` env var (raw JSON) | Scripts, one-off builds. |
| 3 | `AGCONNECT_SERVICES_JSON_BASE64` env var (base64) | **CI** — repository secret. |
| 4 | `-Pagconnect.servicesJson=<json>` Gradle property | Ad-hoc overrides. |

Whatever is resolved is **written into the APK's assets** by the
`copyAgconnectServices` task, and the same value drives the manifest's
`appid`/`cpid` meta-data — so the two can no longer disagree. A build with no
config logs a prominent warning and bundles nothing (it will not leave a stale
config from a previous build behind).

Only `app_id` and `cp_id` (non-secret identifiers) are ever printed. The
`client_secret` / `api_key` are never logged.

---

## 4. Get the file and supply it locally

1. Sign in to [AppGallery Connect](https://developer.huawei.com/consumer/en/service/josp/agc/index.html).
2. Select your project → **Project settings → General information → App information**.
3. Under **App configuration**, click **Download** next to
   `agconnect-services.json`.
4. Place it here:

   ```
   android/app/agconnect-services.json
   ```

   That path is gitignored — it will not be committed. Confirm with:

   ```bash
   git status --short          # must NOT list agconnect-services.json
   ```

5. Verify the file's `client.package_name` is exactly
   `com.mindsetframes.habittracker` (the app's `applicationId`). A mismatch
   silently disables sign-in.

Then build and check the log — you should see:

```
Huawei AGC config resolved from local android/app/agconnect-services.json (app_id=…, cp_id=…)
```

---

## 5. Supply it in CI (required for APKs built by GitHub Actions)

APKs built by the `Build` workflow will have **no** Huawei sign-in until you add
the config as a repository secret.

1. Base64-encode the file (one line, no wrapping):

   ```bash
   base64 -w0 android/app/agconnect-services.json     # Linux
   base64 -i android/app/agconnect-services.json      # macOS
   ```

2. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `AGCONNECT_SERVICES_JSON_BASE64`
   - Value: the base64 string from step 1.

3. Re-run the `Build` workflow. The new step
   **"Check Huawei AGC config is present"** confirms it, and the Gradle log
   prints `Huawei AGC config resolved from AGCONNECT_SERVICES_JSON_BASE64 env`.

Without the secret the workflow still succeeds but emits a warning — check the
run log for `::warning::Missing AGCONNECT_SERVICES_JSON_BASE64`.

---

## 6. Register this build's SHA-256 fingerprint in AGC

AGC only lets sign-in complete for certificate fingerprints you have registered.
**Debug and release builds are signed with different keys and need separate
entries** if you test both.

**From CI** — the `Print release signing SHA-256` step prints the exact
fingerprint of the keystore that run used, straight into the Actions log:

```
SHA256: AA:BB:CC:…
```

**Locally**:

```bash
cd android && ./gradlew signingReport
```

Copy the `SHA-256` line for the variant you are testing, then in AGC go to
**Project settings → General information → App information → SHA-256 certificate
fingerprint → Add**, paste it, and save.

> Changes in AGC can take up to ~30 minutes to propagate. A sign-in that still
> fails immediately right after adding the fingerprint may simply need time.

The app shows this build's own fingerprint under **"View diagnostics"**, so you
can compare the two without rebuilding.

---

## 7. Enable Account Kit for the app

In AGC: **Build → Account Kit → Enable**. Without this toggle, sign-in is
rejected server-side and the picker closes instantly, which looks identical to a
fingerprint mismatch.

Also confirm **Project settings → General information → App information** shows
your app under a project whose Account Kit service is enabled.

---

## 8. Verify it works

1. Install an APK built **after** supplying the config (§4 or §5).
2. Open the auth sheet → tick the privacy consent → **Sign in with HUAWEI ID**.
3. The HUAWEI ID account picker should appear and stay open.
4. On success the app exchanges the Huawei-signed ID token through the
   `huawei-auth` Supabase Edge Function (see
   `backend/functions/huawei-auth/index.ts`), which verifies it before a session
   is issued — so a forged token can't reach another user's data.

If it still fails, open **"Huawei sign-in not working? View diagnostics"**, tap
**Copy**, and send that text: it records the build source, whether the config
loaded, the fingerprint, and how long the sign-in took to come back.

---

## 9. Still required, and not a code change

- **Rotate the leaked AGC credentials.** `client_secret` and `api_key` were
  public in this repository's history. Rotate them in
  AGC → **Project settings → General information**, then follow the purge steps
  in `SECURITY.md`. Removing the file did not un-leak the values.
- **Register the fingerprint (§6) and enable Account Kit (§7).** Both need
  console access.
- **`supabase db push`** to apply
  `backend/supabase/migrations/20260917000000_habit_alarm_message.sql` if you
  want motivational alarm messages to sync across devices.
