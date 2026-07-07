package org.jarsi.betascout.data.scrape

import org.jarsi.betascout.domain.PlaySession

/**
 * One fetched testing page: the HTML body plus the URL the request ended at after
 * redirects. The final URL matters because an expired session redirects to
 * accounts.google.com, which is a stronger sign-in signal than any page marker.
 */
data class FetchedPage(
    val html: String,
    val finalUrl: String? = null,
)

/**
 * Fetches the raw HTML of `play.google.com/apps/testing/<pkg>` using the user's
 * session. An interface so the HTTP implementation can be swapped for a WebView-DOM
 * one if Google ever renders the page client-side.
 */
fun interface TestingPageSource {
    suspend fun fetch(packageName: String, session: PlaySession): Result<FetchedPage>
}
