package org.jarsi.betascout.data.scrape

import kotlinx.coroutines.delay
import org.jarsi.betascout.domain.BetaObservation
import org.jarsi.betascout.domain.PlaySession

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

    suspend fun scrape(
        packages: List<String>,
        session: PlaySession,
        onProgress: suspend (index: Int, total: Int, packageName: String) -> Unit = { _, _, _ -> },
    ): ScrapeOutcome {
        val observations = mutableListOf<BetaObservation>()
        packages.forEachIndexed { index, packageName ->
            if (index > 0) delayFn(crawlDelayMillis)
            onProgress(index + 1, packages.size, packageName)
            android.util.Log.d("BetaScout", "scrape ${index + 1}/${packages.size}: $packageName fetching")
            // A failed fetch (e.g. transient network error) is skipped, leaving the
            // previous observation in place so a blip doesn't overwrite a good status.
            val fetched = source.fetch(packageName, session)
            android.util.Log.d(
                "BetaScout",
                "scrape $packageName: fetched=${fetched.getOrNull()?.html?.length ?: "FAIL ${fetched.exceptionOrNull()}"}",
            )
            val page = fetched.getOrNull() ?: return@forEachIndexed
            // A redirect to accounts.google.com is the authoritative signed-out signal;
            // the sign-in page's HTML markers have changed shape before.
            if (isSignInRedirect(page.finalUrl)) {
                android.util.Log.d("BetaScout", "scrape $packageName: redirected to sign-in, stopping")
                return ScrapeOutcome(observations, needsLogin = true)
            }
            val result = TestingPageParser.parse(page.html)
            android.util.Log.d(
                "BetaScout",
                "scrape $packageName: status=${result.liveStatus} membership=${result.membership} needsLogin=${result.needsLogin}",
            )
            if (result.needsLogin) {
                return ScrapeOutcome(observations, needsLogin = true)
            }
            observations += BetaObservation(
                accountKey = session.accountKey,
                packageName = packageName,
                liveStatus = result.liveStatus,
                observedMembership = result.membership,
                checkedAt = clock(),
            )
        }
        return ScrapeOutcome(observations, needsLogin = false)
    }

    private fun isSignInRedirect(finalUrl: String?): Boolean {
        if (finalUrl == null) return false
        val host = runCatching { java.net.URI(finalUrl).host }.getOrNull() ?: return false
        return host.equals("accounts.google.com", ignoreCase = true)
    }
}
