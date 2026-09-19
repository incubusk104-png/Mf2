package com.rork.mindsetframestracker.data

/**
 * Why cloud features are unavailable in this build, or null when they are fine.
 *
 * ## Why this exists rather than the existing `isConfigured` check
 *
 * `SupabaseSync.isConfigured` already answers "can I talk to Supabase?" and it
 * was the *only* reader of that fact. When it is false the sync path simply
 * returns early, so a build with no `SUPABASE_URL` behaves identically to a
 * build whose network is down: sign-in, backup/restore **and both tracker
 * connections** (which resolve an OAuth client id through a Supabase Edge
 * Function) all quietly do nothing. The user sees buttons that appear to work
 * and an app that never syncs, with no message at any point — the failure is
 * total and completely silent.
 *
 * The CI workflow prints a warning about the missing secret, but that warning
 * is in an Actions log, which is not a place a user of the app will ever look.
 * This function turns the same fact into something the UI can show.
 *
 * Android-free and pure, so the wording and the precedence between the three
 * distinct misconfigurations are unit-testable without a device.
 *
 * @param supabaseUrl `BuildConfig.SUPABASE_URL`, expected to be an `https://`
 *   project URL. Blank in every build that did not receive the CI secret.
 * @param anonKey the public anon key; blank in the same situation.
 */
fun cloudConfigurationWarning(supabaseUrl: String, anonKey: String): String? {
    val url = supabaseUrl.trim()
    // A URL that is present but not https:// is a misconfigured value rather
    // than a missing one (a bare host, a placeholder, a stray token). Naming
    // the expected shape is more actionable than calling both cases "missing".
    return when {
        url.isEmpty() ->
            "Cloud sync is off: this build has no Supabase URL configured, so signing in, " +
                "backup and fitness-tracker connections are unavailable. No data has been lost."

        !url.startsWith("https://") ->
            "Cloud sync is off: the configured Supabase URL is not an https:// address, so " +
                "signing in and backup are unavailable. No data has been lost."

        anonKey.isBlank() ->
            "Cloud sync is off: this build has no Supabase anon key configured, so signing in " +
                "and backup are unavailable. No data has been lost."

        else -> null
    }
}
