package com.rork.mindsetframestracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "this build has no cloud configured" warning (D10).
 *
 * ## The bug behind these tests
 *
 * `SupabaseSync.isConfigured` was the only reader of whether a Supabase URL was
 * compiled in. When it was false the sync path returned early — so sign-in,
 * backup/restore **and both tracker connections** (which resolve an OAuth client
 * id through a Supabase Edge Function) all silently did nothing. Settings hid
 * both cloud cards entirely, so there was no explanation anywhere in the app.
 *
 * The only notice of the missing secret was a warning in the CI log, which is not
 * a place a user of the app will ever look.
 */
class CloudConfigurationWarningTest {

    private val url = "https://abcdefg.supabase.co"
    private val key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature"

    @Test
    fun `a fully configured build has nothing to warn about`() {
        assertNull(cloudConfigurationWarning(url, key))
    }

    @Test
    fun `a blank url warns, because that is the state every secret-less build is in`() {
        // The actual shipping case: no CI secret, so the field is an empty string.
        val warning = cloudConfigurationWarning("", key)
        assertTrue(warning != null && warning.isNotBlank())
    }

    @Test
    fun `the warning says the user's data is safe`() {
        // The one thing a user needs to know when a feature is missing is whether
        // they have lost anything. A message that only names a configuration
        // problem leaves that unanswered, which is how "I don't know" happens.
        val warning = cloudConfigurationWarning("", "")!!
        assertTrue("must not read as data loss", warning.contains("No data has been lost"))
    }

    @Test
    fun `the warning names signing in and backup, not an internal field name`() {
        val warning = cloudConfigurationWarning("", key)!!
        assertTrue(warning.contains("signing in"))
        assertTrue(warning.contains("backup"))
        assertFalse("no raw config keys in user-facing copy", warning.contains("SUPABASE"))
    }

    @Test
    fun `a whitespace-only url counts as missing`() {
        assertTrue(cloudConfigurationWarning("   ", key) != null)
    }

    @Test
    fun `a non-https url is reported as malformed, not as missing`() {
        // A bare host or a placeholder is a different mistake with a different
        // remedy, so it must not be described as absent.
        val warning = cloudConfigurationWarning("abcdefg.supabase.co", key)!!
        assertTrue(warning.contains("not an https://"))
    }

    @Test
    fun `a missing anon key warns even when the url is fine`() {
        val warning = cloudConfigurationWarning(url, "")!!
        assertTrue(warning.contains("anon key"))
    }

    @Test
    fun `whitespace around a valid configuration does not trigger a warning`() {
        // BuildConfig values are interpolated from CI variables and pick up
        // stray whitespace; that must not read as "unconfigured".
        assertNull(cloudConfigurationWarning("  $url  ", "  $key  "))
    }

    @Test
    fun `the two missing pieces are distinguished from each other`() {
        val noUrl = cloudConfigurationWarning("", key)!!
        val noKey = cloudConfigurationWarning(url, "")!!
        assertEquals("different causes, different messages", false, noUrl == noKey)
    }
}
