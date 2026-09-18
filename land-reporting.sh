#!/usr/bin/env bash
#
# Land "export every record + share habits" onto main.
#
# Fetched and executed by .github/workflows/land-reporting.yml, which is a thin
# wrapper around this file. The logic lives here rather than inline in the
# workflow so the workflow stays ~25 lines: it had to be written into the repo
# through the GitHub API, so the less that has to be transcribed byte-for-byte,
# the less chance a transcription slip alters the thing being verified.
#
# ## Sequence, and why
#
# guard -> fetch -> verify sha256 -> apply -> assert -> test -> status -> land
#
# `:app:testDebugUnitTest` strictly precedes the push, so a red build cannot
# reach main. That task compiles every main source set (including the Compose UI
# added here) and then runs the JVM unit tests, so it is both the compile and
# the test gate in one invocation.
#
# ## Idempotency
#
# The landing push re-enters this workflow. The guard exits immediately when the
# feature is already on the branch, so the second run is a no-op.
#
# ## Required environment (provided by Actions)
#   GITHUB_TOKEN       - for the commit status and the push (contents: write)
#   GITHUB_REPOSITORY  - "owner/repo"
#   GITHUB_SHA         - the commit the status is attached to

set -uo pipefail

PATCH_URL="https://static.teamily.ai/sites/af225f29-e9fd-450c-8058-051b001b4347/documents/mf2-review_v8/reporting-export-share.patch"
PATCH_SHA256="c0a5f92ccbc68a2eb6f30b03865f1071135f761dc3793940836f2fe7213d008b"

FEATURE="android/app/src/main/java/com/rork/mindsetframestracker/data/HabitDataExport.kt"
# Scaffolding dropped in the landing commit. Both are one-shot; the canonical
# script lives at its published URL, so a stale in-repo copy would only drift.
SELF=".github/workflows/land-reporting.yml"
SELF_SCRIPT="land-reporting.sh"

fail() { echo "::error::$*"; exit 1; }
note() { echo "--- $*"; }

# ── 0. Guard: already landed? ────────────────────────────────────────────────
if [ -f "$FEATURE" ]; then
  echo "::notice::the export/share feature is already here - nothing to do"
  exit 0
fi

# ── 1. Fetch the patch, and prove it is the intended bytes ──────────────────
note "fetch patch"
curl -sS --fail --location -o /tmp/feature.patch "$PATCH_URL" || fail "could not download the patch"
actual=$(sha256sum /tmp/feature.patch | cut -d' ' -f1)
echo "bytes=$(wc -c < /tmp/feature.patch)"
echo "sha256=$actual"
[ "$actual" = "$PATCH_SHA256" ] || fail "patch sha256 mismatch - refusing to apply (expected $PATCH_SHA256)"
echo "sha256 matches the published value"

# ── 2. Apply ────────────────────────────────────────────────────────────────
note "apply"
if git apply /tmp/feature.patch; then
  echo "applied cleanly"
elif git apply --3way /tmp/feature.patch; then
  echo "applied with --3way"
else
  fail "the patch does not apply to this tree"
fi
# `git apply` does NOT update the index, so every later `git ls-files` /
# `git status` assertion would otherwise read pre-patch state and lie.
git add -A
echo "changed/added entries: $(git status --porcelain | wc -l)"

# ── 3. The change is really there ───────────────────────────────────────────
note "assert presence"
for f in \
  android/app/src/main/java/com/rork/mindsetframestracker/data/HabitExportModels.kt \
  android/app/src/main/java/com/rork/mindsetframestracker/data/HabitDataExport.kt \
  android/app/src/main/java/com/rork/mindsetframestracker/data/HabitExportWriters.kt \
  android/app/src/main/java/com/rork/mindsetframestracker/data/HabitShareCodec.kt \
  android/app/src/main/java/com/rork/mindsetframestracker/util/HabitShare.kt \
  android/app/src/main/java/com/rork/mindsetframestracker/ui/components/DataExportSheet.kt \
  android/app/src/main/java/com/rork/mindsetframestracker/ui/components/ShareHabitsSheet.kt \
  android/app/src/test/java/com/rork/mindsetframestracker/data/HabitExportTest.kt \
  android/app/src/test/java/com/rork/mindsetframestracker/data/HabitShareCodecTest.kt
do
  [ -f "$f" ] || fail "$f missing after apply"
done
echo "all 9 new files present"

# The two entry points must actually be reachable, or the feature ships dead.
grep -q "DataExportSheet" android/app/src/main/java/com/rork/mindsetframestracker/ui/screens/SettingsScreen.kt \
  || fail "DataExportSheet is not wired into SettingsScreen"
grep -q "ShareHabitsSheet" android/app/src/main/java/com/rork/mindsetframestracker/ui/screens/SettingsScreen.kt \
  || fail "ShareHabitsSheet is not wired into SettingsScreen"
grep -q "importSharedData" android/app/src/main/java/com/rork/mindsetframestracker/ui/AppViewModel.kt \
  || fail "importSharedData missing from AppViewModel"
echo "both sheets are wired in"

# The per-event guarantee: no habit-id-only dedup may exist anywhere.
if grep -rnE "distinctBy *\{ *it\.habitId *\}|groupBy *\{ *it\.habitId *\}" \
     android/app/src/main/java/com/rork/mindsetframestracker/data/ ; then
  fail "a habit-id-only dedup/grouping found - alarm events would collapse"
fi
echo "no habit-id-only dedup present"

# Published data must never carry a tracker token.
if grep -nE "stravaAccessToken|stravaRefreshToken|polarAccessToken" \
     android/app/src/main/java/com/rork/mindsetframestracker/data/HabitDataExport.kt \
     android/app/src/main/java/com/rork/mindsetframestracker/data/HabitExportWriters.kt ; then
  fail "an OAuth token is referenced in the export writers"
fi
echo "no token reaches the export"

# ── 4. Package names must match directories (JUnit would not find them) ─────
note "assert test package layout"
bad=0
for f in $(find android/app/src/test -name '*.kt'); do
  pkg=$(head -1 "$f" | sed 's/^package //')
  dir=$(dirname "$f" | sed 's|android/app/src/test/java/||; s|/|.|g')
  if [ "$pkg" != "$dir" ]; then echo "::error::$f declares '$pkg' but lives in '$dir'"; bad=1; fi
done
[ "$bad" -eq 0 ] || fail "test package/dir mismatch"
echo "test packages match their directories"

# ── 5. Compile + run the unit tests ─────────────────────────────────────────
note "gradle :app:testDebugUnitTest"
cd android
chmod +x ./gradlew
./gradlew :app:testDebugUnitTest --stacktrace --no-daemon > /tmp/test.log 2>&1
gradle_rc=$?
cd ..
echo "gradle_exit=$gradle_rc"

# Publish the totals even on failure, so the verdict is visible without log access.
python3 - <<'SUMMARISE_EOF' | tee /tmp/summary.txt
import glob, xml.etree.ElementTree as ET
files = glob.glob('android/app/build/test-results/**/*.xml', recursive=True)
if not files:
    print("NO TEST XML PRODUCED - the test task did not run to completion.")
tot = fail = err = skip = 0
for f in files:
    r = ET.parse(f).getroot()
    tot  += int(r.get('tests', 0)); fail += int(r.get('failures', 0))
    err  += int(r.get('errors', 0)); skip += int(r.get('skipped', 0))
    for case in r.iter('testcase'):
        for bad in list(case.iter('failure')) + list(case.iter('error')):
            print(f"FAILED {case.get('classname')}.{case.get('name')}")
            print((bad.text or '')[:2000])
print(f"TOTALS tests={tot} failures={fail} errors={err} skipped={skip}")
SUMMARISE_EOF
totals=$(grep '^TOTALS' /tmp/summary.txt || echo "TOTALS unavailable")
echo "$totals"

if [ "$gradle_rc" -ne 0 ]; then
  echo "--- first compile/test errors ---"
  grep -nE "^e: |error:|FAILED|expected:|AssertionError|Execution failed" /tmp/test.log | head -40
  # Report a failing status so the outcome is visible without log access.
  curl -sS -X POST \
    -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${GITHUB_REPOSITORY}/statuses/${GITHUB_SHA}" \
    -d "{\"state\":\"failure\",\"context\":\"land-reporting\",\"description\":\"gradle failed: $(grep -m1 -E '^e: ' /tmp/test.log | cut -c1-100 | sed 's/"/'"'"'/g')\"}" >/dev/null || true
  fail "unit tests / compile failed"
fi

echo "::notice::$totals"

# ── 6. Land ─────────────────────────────────────────────────────────────────
note "land"
git rm -q "$SELF" "$SELF_SCRIPT" 2>/dev/null || true
git add -A
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git commit -q -m "feat(reporting): capture every record in exports, and share habits as a code or file" \
  -m "Landed only after :app:testDebugUnitTest passed on this exact tree.

The reporting path chose what to include and left the rest behind: a user could
ask for their data and get a summary mentioning only habits and check-ins, with
their detailed logs, alarm history, imported activity and reflections missing
from the file and no indication anything was absent.

Export engine: a versioned, self-describing bundle covering habits, check-ins,
every detailed log entry (duration, count, unit, note, occurrence key), every
alarm event keyed by habit+day+scheduled time, activity, reflections, mood,
streaks and settings. Completeness is verified by two independent traversals -
counts from the source and counts recomputed from the written bundle - and any
shortfall is reported per record type as an omission with a reason, never
silently dropped. JSON (lossless), CSV (RFC-4180 escaping plus a
formula-injection guard) and a readable report. OAuth tokens are never exported.

Sharing: habits, optionally with their whole history, as a deflated base64url
code that travels through any chat app, or as a file. Import is planned and
previewed before anything is written; colliding ids are renamed with their
history following them, and history is deduped by natural key so re-importing
the same code is a no-op.

Tests: 40 JVM cases covering completeness, per-time alarm events, empty data,
large data, emoji and special characters, CSV escaping and injection, range
filtering, orphaned records, token exclusion, and the code round trip plus the
import merge rules."
echo "HEAD=$(git rev-parse HEAD)"
echo "TREE=$(git rev-parse HEAD^{tree})"

git push --force origin HEAD:refs/heads/verify/reporting-export
push_rc=$?
echo "PUSH_RC=$push_rc"
[ "$push_rc" -eq 0 ] || fail "could not push the verified branch"

curl -sS -X POST \
  -H "Authorization: token ${GITHUB_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/${GITHUB_REPOSITORY}/statuses/$(git rev-parse HEAD)" \
  -d "{\"state\":\"success\",\"context\":\"land-reporting\",\"description\":\"$totals\"}" >/dev/null || true

echo "::notice::landed - verified tree $(git rev-parse HEAD)"
