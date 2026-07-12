package org.jarsi.betascout.data.scrape

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.betascout.domain.BetaLinkBuilder
import org.jarsi.betascout.domain.PlaySession

/**
 * Fetches the testing page over HTTP with the user's Play web-session cookies. A
 * browser User-Agent asks for the web opt-in page (the one carrying the join/leave
 * forms). If the session has expired, Google redirects to the sign-in page; the
 * final URL is reported so the scraper can recognise that redirect.
 */
/** A response status that carries no usable testing page (rate limiting, server errors). */
class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")

class HttpTestingPageSource(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val urlFor: (String) -> String = BetaLinkBuilder::testingUrl,
) : TestingPageSource {

    override suspend fun fetch(packageName: String, session: PlaySession): Result<FetchedPage> =
        withContext(io) {
            try {
                val connection = (URL(urlFor(packageName))
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Cookie", session.cookieHeader)
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }
                // Blocking HttpURLConnection IO cannot observe coroutine cancellation:
                // a cancelled scan would otherwise keep holding the scan lock until the
                // read timeout runs out. Disconnecting from a watcher coroutine aborts
                // the blocked connect/read immediately.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
                try {
                    val code = connection.responseCode
                    android.util.Log.d("BetaScout", "fetch $packageName: http=$code url=${connection.url}")
                    if (code !in 200..299 && code != 404) {
                        // Rate limiting and server errors carry no page worth parsing;
                        // treating them as one would fabricate an UNKNOWN observation
                        // that overwrites a good status and skips the failure counters.
                        Result.failure(HttpStatusException(code))
                    } else {
                        val stream = if (code in 200..299) {
                            connection.inputStream
                        } else {
                            // A 404 body is still meaningful: it is the "no testing program" page.
                            connection.errorStream
                        }
                        val html = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        Result.success(FetchedPage(html, finalUrl = connection.url.toString()))
                    }
                } finally {
                    watcher.cancel()
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The IOException a watcher-triggered disconnect provokes must surface
                // as cancellation, not as a scan failure stamped onto the observation.
                ensureActive()
                android.util.Log.d("BetaScout", "fetch $packageName: failed $e")
                Result.failure(e)
            }
        }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7a) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
