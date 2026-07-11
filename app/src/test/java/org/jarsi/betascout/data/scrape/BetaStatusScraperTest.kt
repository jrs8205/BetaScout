package org.jarsi.betascout.data.scrape

import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership
import org.jarsi.betascout.domain.PlaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val JOINED_HTML =
    """<html><body><form id="leaveForm"><button>Leave</button></form></body></html>"""
private const val OPEN_HTML =
    """<html><body><form id="joinForm"><button>Become a tester</button></form></body></html>"""
private const val LOGGED_OUT_HTML =
    """<html><body><form id="gaia_loginform"></form></body></html>"""

private val session = PlaySession(accountEmail = "user@example.com", cookieHeader = "SID=abc")

class BetaStatusScraperTest {

    private fun scraper(source: TestingPageSource, now: Long = 1000L) =
        BetaStatusScraper(source, clock = { now }, delayFn = {})

    @Test
    fun `scrapes each package into an observation stamped with the clock`() = runTest {
        val source = TestingPageSource { pkg, _ ->
            Result.success(FetchedPage(if (pkg == "com.a") JOINED_HTML else OPEN_HTML))
        }

        val outcome = scraper(source, now = 555L).scrape(listOf("com.a", "com.b"), session)

        assertFalse(outcome.needsLogin)
        val a = outcome.observations.single { it.packageName == "com.a" }
        assertEquals(ObservedMembership.JOINED, a.observedMembership)
        assertEquals(555L, a.checkedAt)
        val b = outcome.observations.single { it.packageName == "com.b" }
        assertEquals(LiveBetaStatus.OPEN, b.liveStatus)
        assertEquals(ObservedMembership.NOT_JOINED, b.observedMembership)
    }

    @Test
    fun `stops and flags re-login when a page shows the session is signed out`() = runTest {
        val fetched = mutableListOf<String>()
        val source = TestingPageSource { pkg, _ ->
            fetched += pkg
            Result.success(FetchedPage(if (pkg == "com.first") LOGGED_OUT_HTML else OPEN_HTML))
        }

        val outcome = scraper(source).scrape(listOf("com.first", "com.second"), session)

        assertTrue(outcome.needsLogin)
        assertEquals(listOf("com.first"), fetched)
        assertTrue(outcome.observations.isEmpty())
    }

    @Test
    fun `stops and flags re-login when the fetch was redirected to the accounts sign-in`() = runTest {
        // An expired session redirects to accounts.google.com. The sign-in HTML has
        // changed shape before, so the redirect target is the authoritative signal
        // regardless of what the page body looks like.
        val source = TestingPageSource { _, _ ->
            Result.success(
                FetchedPage(
                    html = "<html><body><div>Some future sign-in layout.</div></body></html>",
                    finalUrl = "https://accounts.google.com/v3/signin/identifier?continue=https%3A%2F%2Fplay.google.com",
                ),
            )
        }

        val outcome = scraper(source).scrape(listOf("com.a", "com.b"), session)

        assertTrue(outcome.needsLogin)
        assertTrue(outcome.observations.isEmpty())
    }

    @Test
    fun `a fetch failure skips that package but keeps scraping the rest`() = runTest {
        val source = TestingPageSource { pkg, _ ->
            if (pkg == "com.bad") Result.failure(RuntimeException("network"))
            else Result.success(FetchedPage(OPEN_HTML))
        }

        val outcome = scraper(source).scrape(listOf("com.bad", "com.good"), session)

        assertEquals(listOf("com.good"), outcome.observations.map { it.packageName })
        assertFalse(outcome.needsLogin)
    }

    @Test
    fun `records the reason for each failed fetch`() = runTest {
        val source = TestingPageSource { pkg, _ ->
            when (pkg) {
                "com.timeout" -> Result.failure(java.net.SocketTimeoutException("read timed out"))
                "com.dns" -> Result.failure(java.net.UnknownHostException("play.google.com"))
                else -> Result.success(FetchedPage(OPEN_HTML))
            }
        }

        val outcome = scraper(source)
            .scrape(listOf("com.timeout", "com.ok", "com.dns"), session)

        assertEquals(
            mapOf(
                "com.timeout" to "SocketTimeoutException: read timed out",
                "com.dns" to "UnknownHostException: play.google.com",
            ),
            outcome.failures,
        )
        assertEquals(listOf("com.ok"), outcome.observations.map { it.packageName })
    }

    @Test
    fun `stops the whole run on a rate-limit response`() = runTest {
        val fetched = mutableListOf<String>()
        val source = TestingPageSource { pkg, _ ->
            fetched += pkg
            if (pkg == "com.first") Result.failure(HttpStatusException(429))
            else Result.success(FetchedPage(OPEN_HTML))
        }

        val outcome = scraper(source).scrape(listOf("com.first", "com.second"), session)

        // Continuing into a throttled session would only grow the account risk;
        // unchecked packages stay due and a later run retries them.
        assertEquals(listOf("com.first"), fetched)
        assertEquals("HttpStatusException: HTTP 429", outcome.failures["com.first"])
        assertTrue(outcome.observations.isEmpty())
    }

    @Test
    fun `stops the whole run when Google blocks with 403`() = runTest {
        val fetched = mutableListOf<String>()
        val source = TestingPageSource { pkg, _ ->
            fetched += pkg
            Result.failure(HttpStatusException(403))
        }

        scraper(source).scrape(listOf("com.first", "com.second"), session)

        assertEquals(listOf("com.first"), fetched)
    }

    @Test
    fun `aborts the run after repeated consecutive failures`() = runTest {
        val fetched = mutableListOf<String>()
        val source = TestingPageSource { pkg, _ ->
            fetched += pkg
            Result.failure(java.net.SocketTimeoutException("read timed out"))
        }

        val outcome = scraper(source).scrape((1..10).map { "com.app$it" }, session)

        // Five failures in a row mean the network or Google is degraded; the rest
        // of the list would just pile up more timeouts without observations.
        assertEquals(5, fetched.size)
        assertEquals(5, outcome.failures.size)
    }

    @Test
    fun `a success resets the consecutive-failure abort counter`() = runTest {
        val source = TestingPageSource { pkg, _ ->
            if (pkg.startsWith("com.bad")) Result.failure(java.net.SocketTimeoutException("t"))
            else Result.success(FetchedPage(OPEN_HTML))
        }
        // Four failures, a success, then four failures: never five in a row.
        val packages = (1..4).map { "com.bad$it" } + "com.ok" + (5..8).map { "com.bad$it" }

        val outcome = scraper(source).scrape(packages, session)

        assertEquals(8, outcome.failures.size)
        assertEquals(listOf("com.ok"), outcome.observations.map { it.packageName })
    }

    @Test
    fun `reports progress before each fetch`() = runTest {
        val progress = mutableListOf<String>()
        val source = TestingPageSource { _, _ -> Result.success(FetchedPage(OPEN_HTML)) }

        scraper(source).scrape(listOf("com.a", "com.b"), session) { index, total, pkg ->
            progress += "$index/$total $pkg"
        }

        assertEquals(listOf("1/2 com.a", "2/2 com.b"), progress)
    }

    @Test
    fun `applies a crawl delay between requests but not before the first`() = runTest {
        val delays = mutableListOf<Long>()
        val source = TestingPageSource { _, _ -> Result.success(FetchedPage(OPEN_HTML)) }
        val scraper = BetaStatusScraper(
            source, clock = { 0L }, crawlDelayMillis = 2_500, delayFn = { delays += it },
        )

        scraper.scrape(listOf("com.a", "com.b", "com.c"), session)

        assertEquals(listOf(2_500L, 2_500L), delays)
    }
}
