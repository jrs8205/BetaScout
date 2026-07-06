package org.jarsi.betascout.data.scrape

/** The user's Google Play web session, as a cookie header for authenticated requests. */
data class PlaySession(val cookieHeader: String)

/**
 * Fetches the raw HTML of `play.google.com/apps/testing/<pkg>` using the user's
 * session. An interface so the HTTP implementation can be swapped for a WebView-DOM
 * one if Google ever renders the page client-side.
 */
fun interface TestingPageSource {
    suspend fun fetch(packageName: String, session: PlaySession): Result<String>
}
