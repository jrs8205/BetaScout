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

private val session = PlaySession("SID=abc")

class BetaStatusScraperTest {

    private fun scraper(source: TestingPageSource, now: Long = 1000L) =
        BetaStatusScraper(source, clock = { now }, delayFn = {})

    @Test
    fun `scrapes each package into an observation stamped with the clock`() = runTest {
        val source = TestingPageSource { pkg, _ ->
            Result.success(if (pkg == "com.a") JOINED_HTML else OPEN_HTML)
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
            Result.success(if (pkg == "com.first") LOGGED_OUT_HTML else OPEN_HTML)
        }

        val outcome = scraper(source).scrape(listOf("com.first", "com.second"), session)

        assertTrue(outcome.needsLogin)
        assertEquals(listOf("com.first"), fetched)
        assertTrue(outcome.observations.isEmpty())
    }

    @Test
    fun `a fetch failure skips that package but keeps scraping the rest`() = runTest {
        val source = TestingPageSource { pkg, _ ->
            if (pkg == "com.bad") Result.failure(RuntimeException("network"))
            else Result.success(OPEN_HTML)
        }

        val outcome = scraper(source).scrape(listOf("com.bad", "com.good"), session)

        assertEquals(listOf("com.good"), outcome.observations.map { it.packageName })
        assertFalse(outcome.needsLogin)
    }

    @Test
    fun `applies a crawl delay between requests but not before the first`() = runTest {
        val delays = mutableListOf<Long>()
        val source = TestingPageSource { _, _ -> Result.success(OPEN_HTML) }
        val scraper = BetaStatusScraper(
            source, clock = { 0L }, crawlDelayMillis = 2_500, delayFn = { delays += it },
        )

        scraper.scrape(listOf("com.a", "com.b", "com.c"), session)

        assertEquals(listOf(2_500L, 2_500L), delays)
    }
}
