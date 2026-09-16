# Secret handling — `agconnect-services.json`

## What happened

`android/app/agconnect-services.json` was **committed to the repository** and the
repository is public. That file is Huawei's AppGallery Connect configuration and
it carries live credentials:

| Field | Sensitivity |
|---|---|
| `client.client_secret` | **Secret.** Server-side credential for the AGC project. |
| `client.api_key` | **Secret.** API key for the AGC project. |
| `client.client_id`, `client.app_id`, `client.cp_id`, `client.project_id` | Project identifiers. Not secret by themselves, but they target *your* project. |

Anything in the repository's history is public permanently: deleting the file
now does not un-leak the values that were already pushed. **Rotation is the fix;
removal is only what stops the recurrence.**

## What has been changed in this branch

1. The file is **no longer tracked** (`git rm --cached android/app/agconnect-services.json`).
   It stays on your working machine — nothing local was deleted.
2. `android/.gitignore` now ignores `agconnect-services.json`, so it cannot be
   re-added by accident.
3. `android/app/agconnect-services.json.example` is committed in its place: the
   same structure with every secret and project identifier replaced by a
   `<PLACEHOLDER>`. Copy it and fill in your own values.

The Gradle build already degrades safely without the real file: it falls back to
literals for `huaweiAppId` / `huaweiCpId` (see the `Huawei app identity` block in
`android/app/build.gradle.kts`), so CI keeps compiling.

## What you must do manually

These steps need console access and cannot be done from a code change:

1. **Rotate the leaked credentials.** In AppGallery Connect → your project →
   Project settings → General information, regenerate the **client secret** and
   the **API key**. Treat the previous values as compromised.
2. **Untrack locally and confirm.**
   ```bash
   git rm --cached android/app/agconnect-services.json
   git commit -m "Stop tracking the Huawei AGC config"
   ```
   (Already done on this branch — do it on `main` when you merge.)
3. **Purge it from history.** Rotation is what protects the live project; history
   rewriting is what stops the next person finding it. Either
   [`git filter-repo`](https://github.com/newren/git-filter-repo) or BFG:
   ```bash
   git filter-repo --path android/app/agconnect-services.json --invert-paths
   ```
   Then force-push and tell collaborators to re-clone. Note that this rewrites
   every commit id.
4. **Inject it at build time.** Two options, both keep the file out of git:
   - **CI:** store the file's contents as a repository secret (e.g.
     `AGCONNECT_SERVICES_JSON`, base64-encoded) and write it out in the workflow
     before the Gradle build. The existing `Build` workflow already establishes
     this pattern for the keystore (`KEYSTORE_BASE64`).
   - **Local:** keep the real file in the working tree — it is gitignored, so
     Gradle picks it up automatically.

## Verify

```bash
git ls-files | grep -i agconnect          # must return nothing
git check-ignore -v android/app/agconnect-services.json   # must report the .gitignore rule
```

## Audit note

No other credential files were found in the tree: no keystore, no
`service_role` key, no `google-services.json`, and no `eyJ…`-shaped JWTs outside
of test fixtures. `POLAR_CLIENT_SECRET` is deliberately *not* a `BuildConfig`
field (see the comment in `app/build.gradle.kts`), so it does not ship in the APK.
