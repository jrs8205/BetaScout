package org.jarsi.betascout.ui.account

import org.jarsi.betascout.data.settings.SessionStanding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tapping the "sign in again" notification opens the sign-in page — for a user
 * who is signed out. The notification can outlive the problem it reports, so
 * the stored state is read first, and that read can fail.
 */
class SignInFromNoticeTest {

    @Test
    fun `the page opens for a user whose session expired or who is signed out`() {
        assertTrue(opensSignInPage(SessionStanding.EXPIRED, busy = false))
        assertTrue(opensSignInPage(SessionStanding.SIGNED_OUT, busy = false))
    }

    @Test
    fun `the page does not open over a session that is there`() {
        // Signed in again from the app since the notification was posted.
        assertFalse(opensSignInPage(SessionStanding.SIGNED_IN, busy = false))
    }

    @Test
    fun `a state that cannot be read opens nothing`() {
        // Not the same as signed out. The session may be perfectly good; a
        // sign-in page opened over it could store the WebView's cookies as a
        // new sign-in and start a scan nobody asked for.
        assertFalse(opensSignInPage(standing = null, busy = false))
    }

    @Test
    fun `the page does not open while a sign-out is still clearing up`() {
        assertFalse(opensSignInPage(SessionStanding.SIGNED_OUT, busy = true))
        assertFalse(opensSignInPage(SessionStanding.EXPIRED, busy = true))
    }
}
