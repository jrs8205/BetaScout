package org.jarsi.betascout.data.scrape

import kotlinx.coroutines.delay
import org.jarsi.betascout.domain.BetaObservation
import org.jarsi.betascout.domain.PlaySession

/** Result of scraping a batch of packages. [failures] maps each package whose
 *  page fetch failed to a short reason, so a run that degrades into mass failures
 *  (timeouts, rate limiting) stays diagnosable per app. */
data class ScrapeOutcome(
    val observations: List<BetaObservation>,
    val needsLogin: Boolean,
    val failures: Map<String, String> = emptyMap(),
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
        val failures = linkedMapOf<String, String>()
        var consecutiveFailures = 0
        packages.forEachIndexed { index, packageName ->
            if (index > 0) delayFn(crawlDelayMillis)
            onProgress(index + 1, packages.size, packageName)
            android.util.Log.d("BetaScout", "scrape ${index + 1}/${packages.size}: $packageName fetching")
            // A failed fetch (e.g. transient network error) leaves the previous
            // observation in place so a blip doesn't overwrite a good status, but the
            // reason is recorded so mass failures stay diagnosable.
            val fetched = source.fetch(packageName, session)
            android.util.Log.d(
                "BetaScout",
                "scrape $packageName: fetched=${fetched.getOrNull()?.html?.length ?: "FAIL ${fetched.exceptionOrNull()}"}",
            )
            val page = fetched.getOrNull()
            if (page == null) {
                val cause = fetched.exceptionOrNull()
                failures[packageName] = cause.toShortReason()
                val status = (cause as? HttpStatusException)?.code
                if (status == 429 || status == 403) {
                    // Google is throttling or blocking this session: every further
                    // request only grows the account risk. Unchecked packages stay
                    // due and a later run retries them.
                    android.util.Log.d("BetaScout", "scrape $packageName: HTTP $status, stopping the run")
                    return ScrapeOutcome(observations, needsLogin = false, failures = failures)
                }
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    // The network or Google is degraded: hammering through the rest
                    // of the list would add timeouts, not observations.
                    android.util.Log.d(
                        "BetaScout",
                        "scrape: $consecutiveFailures consecutive failures, aborting the run",
                    )
                    return ScrapeOutcome(observations, needsLogin = false, failures = failures)
                }
                return@forEachIndexed
            }
            consecutiveFailures = 0
            // A redirect to accounts.google.com is the authoritative signed-out signal;
            // the sign-in page's HTML markers have changed shape before.
            if (isSignInRedirect(page.finalUrl)) {
                android.util.Log.d("BetaScout", "scrape $packageName: redirected to sign-in, stopping")
                return ScrapeOutcome(observations, needsLogin = true, failures = failures)
            }
            val result = TestingPageParser.parse(page.html)
            android.util.Log.d(
                "BetaScout",
                "scrape $packageName: status=${result.liveStatus} membership=${result.membership} needsLogin=${result.needsLogin}",
            )
            if (result.needsLogin) {
                return ScrapeOutcome(observations, needsLogin = true, failures = failures)
            }
            observations += BetaObservation(
                accountKey = session.accountKey,
                packageName = packageName,
                liveStatus = result.liveStatus,
                observedMembership = result.membership,
                checkedAt = clock(),
            )
        }
        return ScrapeOutcome(observations, needsLogin = false, failures = failures)
    }

    private fun Throwable?.toShortReason(): String =
        if (this == null) {
            "unknown"
        } else {
            listOfNotNull(javaClass.simpleName.ifBlank { null }, message)
                .joinToString(": ")
                .ifBlank { "unknown" }
        }

    private fun isSignInRedirect(finalUrl: String?): Boolean {
        if (finalUrl == null) return false
        val host = runCatching { java.net.URI(finalUrl).host }.getOrNull() ?: return false
        return host.equals("accounts.google.com", ignoreCase = true)
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
