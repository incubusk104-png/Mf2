/**
 * freeTier — the shared declarations behind the free plan's habit cap.
 *
 * ## What was wrong before
 *
 * `MAX_FREE_HABITS` was declared twice — `const MAX_FREE_HABITS = 5` in this
 * Edge Function and `const val MAX_FREE_HABITS = 5` in the Android `Models.kt`.
 * Two declarations of one business number is a promise to let them drift, and it
 * had already drifted in the worst way: the copy *here* was declared and then
 * never referenced at all, so `POST /habits` and `POST /habits/sync` accepted
 * unlimited creates while the client cheerfully showed "5 of 5 active habits".
 *
 * ## Where the number lives now
 *
 * One editable declaration: **`freeHabitsMax` in
 * `android/gradle/libs.versions.toml`**. Everything else is derived from it:
 *
 *  * the **Android client** — `app/build.gradle.kts` reads the catalog entry and
 *    emits `BuildConfig.MAX_FREE_HABITS`, which `data/Models.kt` exposes as
 *    `MAX_FREE_HABITS`. No Kotlin literal remains.
 *  * **this function** — reads its `MAX_FREE_HABITS` environment variable at
 *    cold start, which the deployer sets from that same catalog entry.
 *
 * ## Why the number still appears below, and what stops it drifting
 *
 * An Edge Function is deployed as its own bundle with no access to the
 * repository, so it cannot read the catalog at runtime — a literal has to exist
 * here as the fallback for a deployment that sets no environment variable.
 * Leaving it as a bare `5` would just be the old duplicated declaration wearing
 * a comment, so it is a **named** constant and its agreement with the catalog is
 * *enforced*, not documented: `FreeHabitLimitTest`
 * (`android/app/src/test/java/.../data/FreeHabitLimitTest.kt`) parses both files
 * and fails the build if they disagree. `:app:testDebugUnitTest` runs in
 * `.github/workflows/build.yml`, so a one-sided edit cannot reach `main`.
 *
 * Change the limit in the catalog, run the unit tests, and update
 * `FREE_HABITS_FALLBACK` here to match — and nowhere else.
 */

/**
 * The cap this deployment falls back to when no environment variable is set.
 *
 * A deployment-time override of the same name (`MAX_FREE_HABITS`) takes
 * precedence, which is the normal 12-factor path for changing behaviour per
 * environment without shipping code. The fallback exists so an un-injected
 * deployment keeps enforcing the *published* limit instead of defaulting to
 * zero, which would hard-block every free user on the way in.
 *
 * Must equal `freeHabitsMax` in `android/gradle/libs.versions.toml`.
 * `FreeHabitLimitTest` checks that, and names both files when they disagree.
 */
export const FREE_HABITS_FALLBACK = 5;

/** The limit this deployment enforces, overridable per environment. */
export const MAX_FREE_HABITS: number = (() => {
  const raw = Deno.env.get("MAX_FREE_HABITS");
  const parsed = raw === undefined ? NaN : Number(raw);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : FREE_HABITS_FALLBACK;
})();

/**
 * The wire code a cap refusal carries, so the client can raise its paywall.
 *
 * A string constant rather than an inline literal at each refusal site: the
 * Android client matches on it (`SupabaseSync` → the existing
 * `LimitReachedPaywallState`) through its own `HABIT_LIMIT_CODE`, so a typo in
 * one of two places would turn a correct refusal into a generic error the user
 * cannot act on. A wire string is the one thing on this boundary that genuinely
 * cannot be shared across the two languages, which is why it is declared once
 * per side and pinned by a test on the Kotlin half
 * (`habitLimitCodeMatchesTheServerWireContract`).
 */
export const HABIT_LIMIT_CODE = "habit_limit";
