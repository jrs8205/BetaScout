package org.jarsi.betascout.data.scrape

import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures model the states of the authenticated testing page, keyed on the same
 * markers the reference app parses (gaia_loginform / joinForm / leaveForm / greenBox)
 * plus the status vocabulary. They are refined against a real capture on device.
 */
class TestingPageParserTest {

    @Test
    fun `a logged-out page asks for login`() {
        val html = """
            <html><body>
              <form id="gaia_loginform" action="https://accounts.google.com/signin"></form>
            </body></html>
        """.trimIndent()

        val result = TestingPageParser.parse(html)

        assertTrue(result.needsLogin)
        assertEquals(ObservedMembership.UNKNOWN, result.membership)
    }

    @Test
    fun `the modern accounts sign-in page asks for login without the legacy marker`() {
        // Google's current sign-in flow (accounts.google.com/v3/signin/identifier)
        // carries no gaia_loginform element; the stable marker on that page is the
        // identifier input field.
        val html = """
            <html><body>
              <form method="post" action="https://accounts.google.com/v3/signin/identifier?flowName=GlifWebSignIn">
                <input type="email" id="identifierId" name="identifier" autocomplete="username">
                <button>Next</button>
              </form>
            </body></html>
        """.trimIndent()

        val result = TestingPageParser.parse(html)

        assertTrue(result.needsLogin)
        assertEquals(ObservedMembership.UNKNOWN, result.membership)
        assertEquals(LiveBetaStatus.UNKNOWN, result.liveStatus)
    }

    @Test
    fun `an open program the user has not joined shows a join form`() {
        val html = """
            <html><body>
              <div class="greenBox">You are not yet a tester of this app.</div>
              <form id="joinForm" action="/apps/testing/com.example"><button>Become a tester</button></form>
            </body></html>
        """.trimIndent()

        val result = TestingPageParser.parse(html)

        assertFalse(result.needsLogin)
        assertEquals(LiveBetaStatus.OPEN, result.liveStatus)
        assertEquals(ObservedMembership.NOT_JOINED, result.membership)
    }

    @Test
    fun `a program the user is already a tester of shows a leave form`() {
        val html = """
            <html><body>
              <div class="greenBox">You are a tester.</div>
              <form id="leaveForm" action="/apps/testing/com.example"><button>Leave the program</button></form>
            </body></html>
        """.trimIndent()

        val result = TestingPageParser.parse(html)

        assertEquals(ObservedMembership.JOINED, result.membership)
        assertEquals(LiveBetaStatus.OPEN, result.liveStatus)
    }

    @Test
    fun `a full program is reported full and not joined`() {
        val html = """
            <html><body>
              <div class="greenBox">This testing program is full. Check back later.</div>
            </body></html>
        """.trimIndent()

        val result = TestingPageParser.parse(html)

        assertEquals(LiveBetaStatus.FULL, result.liveStatus)
        assertEquals(ObservedMembership.NOT_JOINED, result.membership)
    }

    @Test
    fun `a closed program is reported closed`() {
        val html = """
            <html><body>
              <div>This app is no longer accepting new testers.</div>
            </body></html>
        """.trimIndent()

        val result = TestingPageParser.parse(html)

        assertEquals(LiveBetaStatus.CLOSED, result.liveStatus)
        assertEquals(ObservedMembership.NOT_JOINED, result.membership)
    }

    @Test
    fun `a not-found page means the app has no testing program`() {
        val html = """
            <html><body>
              <div>The requested URL was not found on this server.</div>
            </body></html>
        """.trimIndent()

        val result = TestingPageParser.parse(html)

        assertEquals(LiveBetaStatus.NO_PROGRAM, result.liveStatus)
        assertEquals(ObservedMembership.UNKNOWN, result.membership)
    }

    @Test
    fun `a generic Play Store app page means the app has no testing program`() {
        val html = """
            <html>
              <head>
                <meta property="og:url" content="https://play.google.com/store/apps/details?id=com.example">
              </head>
              <body>
                <div>Google Play</div>
                <h1>Example App</h1>
                <section>About this app</section>
                <section>Data safety</section>
              </body>
            </html>
        """.trimIndent()

        val result = TestingPageParser.parse(html)

        assertEquals(LiveBetaStatus.NO_PROGRAM, result.liveStatus)
        assertEquals(ObservedMembership.UNKNOWN, result.membership)
    }

    @Test
    fun `a testing page that also links to the store is not misread as no-program`() {
        // Real testing pages link to the store listing; a store link plus testing
        // vocabulary must stay inconclusive (retryable), never a hard NO_PROGRAM.
        val html = """
            <html>
              <head>
                <meta property="og:url" content="https://play.google.com/store/apps/details?id=com.example">
              </head>
              <body>
                <div>Google Play</div>
                <p>Join the testing program to become a tester of this app.</p>
              </body>
            </html>
        """.trimIndent()

        val result = TestingPageParser.parse(html)

        assertEquals(LiveBetaStatus.UNKNOWN, result.liveStatus)
    }

    @Test
    fun `a store page with only one store section stays inconclusive`() {
        val html = """
            <html><body>
              <div>Google Play</div>
              <section>About this app</section>
            </body></html>
        """.trimIndent()

        assertEquals(LiveBetaStatus.UNKNOWN, TestingPageParser.parse(html).liveStatus)
    }

    @Test
    fun `being a tester takes precedence over a full message`() {
        val html = """
            <html><body>
              <div class="greenBox">You are a tester. This testing program is full.</div>
              <form id="leaveForm" action="/apps/testing/com.example"><button>Leave</button></form>
            </body></html>
        """.trimIndent()

        assertEquals(ObservedMembership.JOINED, TestingPageParser.parse(html).membership)
    }

    @Test
    fun `a blank body is inconclusive so it can be retried`() {
        val result = TestingPageParser.parse("   ")

        assertEquals(LiveBetaStatus.UNKNOWN, result.liveStatus)
        assertEquals(ObservedMembership.UNKNOWN, result.membership)
        assertFalse(result.needsLogin)
    }

    @Test
    fun `an unrecognized page is inconclusive rather than assumed missing`() {
        val html = "<html><body><div>Some unexpected layout with no known markers.</div></body></html>"

        assertEquals(LiveBetaStatus.UNKNOWN, TestingPageParser.parse(html).liveStatus)
    }
}
