package org.jarsi.betascout.data.scrape

import kotlinx.coroutines.delay
import org.jarsi.betascout.domain.BetaObservation

/** Result of scraping a batch of packages. */
data class ScrapeOutcome(
    val observations: List<BetaObservation>,
    val needsLogin: Boolean,
)

/**
 * Scrapes the testing page for a batch of packages and turns each into a
 * [BetaObservation]. A crawl delay is applied between requests to keep the request
 * rate — and account risk — low. If a page shows the session is no longer signed in,
 * scraping stops immediately and flags that a re-login is needed.
 */
class BetaStatusScraper(
    private val source: TestingPageSource,
    private val clock: () -> Long,
    private val crawlDelayMillis: Long = 3_000,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun scrape(packages: List<String>, session: PlaySession): ScrapeOutcome {
        val observations = mutableListOf<BetaObservation>()
        packages.forEachIndexed { index, packageName ->
            if (index > 0) delayFn(crawlDelayMillis)
            // A failed fetch (e.g. transient network error) is skipped, leaving the
            // previous observation in place so a blip doesn't overwrite a good status.
            val html = source.fetch(packageName, session).getOrNull() ?: return@forEachIndexed
            val result = TestingPageParser.parse(html)
            if (result.needsLogin) {
                return ScrapeOutcome(observations, needsLogin = true)
            }
            observations += BetaObservation(
                packageName = packageName,
                liveStatus = result.liveStatus,
                observedMembership = result.membership,
                checkedAt = clock(),
            )
        }
        return ScrapeOutcome(observations, needsLogin = false)
    }
}
