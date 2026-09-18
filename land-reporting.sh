#!/usr/bin/env bash
#
# Land "export every record + share habits" onto main.
#
# Fetched and executed by .github/workflows/land-reporting.yml. The logic lives
# here rather than inline in the workflow so the workflow stays short.
#
# Sequence: guard -> fetch -> verify sha256 -> apply -> assert -> test -> land.
# `:app:testDebugUnitTest` strictly precedes the push, so a red tree cannot land.
#
# Why a runner and not the dev sandbox: the sandbox has a hard 2 GiB cgroup
# memory limit while gradle.properties requests -Xmx3g for Gradle PLUS -Xmx2g for
# the Kotlin daemon, so the daemon is OOM-killed during compilation. Measured,
# not assumed.
#
# ## Required environment (provided by Actions)
#   GITHUB_TOKEN       - commit status + push (contents: write, statuses: write)
#   GITHUB_REPOSITORY  - "owner/repo"
#   GITHUB_SHA         - the commit the status is attached to

set -uo pipefail

PATCH_URL="https://static.teamily.ai/sites/af225f29-e9fd-450c-8058-051b001b4347/documents/mf2-review_v8/reporting-export-share.patch"
PATCH_SHA256="c0a5f92ccbc68a2eb6f30b03865f1071135f761dc3793940836f2fe7213d008b"

FEATURE="android/app/src/main/java/com/rork/mindsetframestracker/data/HabitDataExport.kt"
SELF=".github/workflows/land-reporting.yml"
SELF_SCRIPT="land-reporting.sh"

fail() { echo "::error::$*"; exit 1; }
note() { echo "--- $*"; }

post_status() {
  curl -sS -X POST -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${GITHUB_REPOSITORY}/statuses/${GITHUB_SHA}" \
    -d "{\"state\":\"$1\",\"context\":\"land-reporting\",\"description\":\"$2\"}" >/dev/null 2>&1 || true
}

# -- 0. Guard: already landed? -----------------------------------------------
if [ -f "$FEATURE" ]; then
  echo "::notice::the export/share feature is already here - nothing to do"
  exit 0
fi
echo "PWD=$(pwd)"
echo "root listing:"; ls -1 | head -20

# -- 1. Fetch the patch, and prove it is the intended bytes ------------------
note "fetch patch"
curl -sS --fail --location -o /tmp/feature.patch "$PATCH_URL" || fail "could not download the patch"
actual=$(sha256sum /tmp/feature.patch | cut -d' ' -f1)
echo "bytes=$(wc -c < /tmp/feature.patch)"
echo "sha256=$actual"
[ "$actual" = "$PATCH_SHA256" ] || fail "patch sha256 mismatch - refusing to apply (expected $PATCH_SHA256)"
echo "sha256 matches the published value"

# -- 2. Apply ----------------------------------------------------------------
note "apply"
if git apply /tmp/feature.patch; then
  echo "applied cleanly"
elif git apply --3way /tmp/feature.patch; then
  echo "applied with --3way"
else
  fail "the patch does not apply to this tree"
fi
git add -A
echo "changed/added entries: $(git status --porcelain | wc -l)"

# -- 3. The change is really there -------------------------------------------
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

SETTINGS=android/app/src/main/java/com/rork/mindsetframestracker/ui/screens/SettingsScreen.kt
grep -q "DataExportSheet" "$SETTINGS" || fail "DataExportSheet is not wired into SettingsScreen"
grep -q "ShareHabitsSheet" "$SETTINGS" || fail "ShareHabitsSheet is not wired into SettingsScreen"
grep -q "importSharedData" android/app/src/main/java/com/rork/mindsetframestracker/ui/AppViewModel.kt \
  || fail "importSharedData missing from AppViewModel"
echo "both sheets are wired in"

if grep -rnE "distinctBy *\{ *it\.habitId *\}|groupBy *\{ *it\.habitId *\}" \
     android/app/src/main/java/com/rork/mindsetframestracker/data/ ; then
  fail "a habit-id-only dedup/grouping found - alarm events would collapse"
fi
echo "no habit-id-only dedup present"

# Published data must never carry an OAuth token VALUE.
#  - HabitExportWriters.kt PRODUCES the output, so it must never mention a token.
#  - HabitDataExport.kt legitimately READS the token fields to decide whether
#    Strava/Polar are connected; only the token NAME may appear there.
if grep -n "Token" android/app/src/main/java/com/rork/mindsetframestracker/data/HabitExportWriters.kt ; then
  fail "the export OUTPUT mentions a token - it must never reference one"
fi
if grep -nE "\.(stravaAccessToken|stravaRefreshToken|polarAccessToken)\b" \
     android/app/src/main/java/com/rork/mindsetframestracker/data/HabitDataExport.kt ; then
  fail "a token field is dereferenced in the export builder"
fi
echo "no token reaches the export"

# -- 4. Package names must match directories ---------------------------------
note "assert test package layout"
bad=0
for f in $(find android/app/src/test -name '*.kt'); do
  pkg=$(head -1 "$f" | sed 's/^package //')
  dir=$(dirname "$f" | sed 's|android/app/src/test/java/||; s|/|.|g')
  if [ "$pkg" != "$dir" ]; then echo "::error::$f declares '$pkg' but lives in '$dir'"; bad=1; fi
done
[ "$bad" -eq 0 ] || fail "test package/dir mismatch"
echo "test packages match their directories"

# -- 5. Compile + run the unit tests -----------------------------------------
note "gradle :app:testDebugUnitTest"
cd android
chmod +x ./gradlew
./gradlew :app:testDebugUnitTest --stacktrace --no-daemon > /tmp/test.log 2>&1
gradle_rc=$?
cd ..
echo "gradle_exit=$gradle_rc"

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
  first=$(grep -m1 -E "^e: |^FAILED" /tmp/test.log | cut -c1-100)
  post_status failure "gradle failed: ${first}"
  fail "unit tests / compile failed"
fi

echo "::notice::$totals"

# -- 6. Land ----------------------------------------------------------------
note "land"
git rm -q "$SELF" "$SELF_SCRIPT" 2>/dev/null || true
git add -A
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git commit -q -m "feat(reporting): capture every record in exports, and share habits as a code or file" \
  -m "Landed only after :app:testDebugUnitTest passed on this exact tree.

Export engine: a versioned, self-describing bundle covering habits, check-ins,
every detailed log entry, every alarm event keyed by habit+day+scheduled time,
activity, reflections, mood, streaks and settings. Completeness is verified by
two independent traversals; any shortfall is reported per record type as an
omission with a reason. JSON, CSV (RFC-4180 escaping plus a formula-injection
guard) and a readable report. OAuth tokens are never exported.

Sharing: habits, optionally with their whole history, as a deflated base64url
code or a file. Import is previewed before anything is written; colliding ids
are renamed with their history following them, and history is deduped by
natural key so re-importing the same code is a no-op.

Tests: 40 JVM cases."
echo "HEAD=$(git rev-parse HEAD)"
echo "TREE=$(git rev-parse HEAD^{tree})"

git push --force origin HEAD:refs/heads/verify/reporting-export
push_rc=$?
echo "PUSH_RC=$push_rc"
[ "$push_rc" -eq 0 ] || fail "could not push the verified branch"

post_status success "$totals"
echo "::notice::landed - verified tree $(git rev-parse HEAD)"
