package org.jarsi.betascout.ui.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCaptureTest {

    @Test
    fun `matches only a real play host`() {
        assertTrue(isPlayPageUrl("https://play.google.com/"))
        assertTrue(isPlayPageUrl("https://play.google.com/store/apps"))
    }

    @Test
    fun `the sign-in page carrying play in its continue parameter does not match`() {
        // Capturing on accounts.google.com pages saves a half-established session:
        // any stale SID cookie present mid-flow would be stored as the Play session
        // and immediately fail the first scan with needs-login.
        assertFalse(
            isPlayPageUrl("https://accounts.google.com/ServiceLogin?continue=https://play.google.com/"),
        )
        assertFalse(
            isPlayPageUrl("https://accounts.google.com/v3/signin/identifier?continue=https%3A%2F%2Fplay.google.com"),
        )
    }

    @Test
    fun `look-alike hosts and junk do not match`() {
        assertFalse(isPlayPageUrl("https://play.google.com.evil.example/"))
        assertFalse(isPlayPageUrl("https://evil.example/play.google.com"))
        assertFalse(isPlayPageUrl("not a url"))
        assertFalse(isPlayPageUrl(null))
    }
}
