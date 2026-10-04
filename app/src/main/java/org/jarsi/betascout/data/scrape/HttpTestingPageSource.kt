package org.jarsi.betascout.data.scrape

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jarsi.betascout.data.remote.useCancellable
import org.jarsi.betascout.domain.BetaLinkBuilder
import org.jarsi.betascout.domain.PlaySession

/** A response status that carries no usable testing page (rate limiting, server
 *  errors, or a redirect that must not be followed). */
class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")

/**
 * The only redirects a request carrying the Google session may follow: same
 * scheme (no https→http downgrade of the cookies) and a Google host. Android's
 * HttpURLConnection would otherwise re-send the Cookie header to whatever host
 * a redirect names — PRIVACY.md promises the session goes to Google only.
 */
internal fun redirectKeepsSession(from: URL, to: URL): Boolean {
    if (!to.protocol.equals(from.protocol, ignoreCase = true)) return false
    val host = to.host.lowercase()
    return host == from.host.lowercase() ||
        host == "google.com" ||
        host.endsWith(".google.com")
}

/**
 * Fetches the testing page over HTTP with the user's Play web-session cookies. A
 * browser User-Agent asks for the web opt-in page (the one carrying the join/leave
 * forms). If the session has expired, Google redirects to the sign-in page; the
 * final URL is reported so the scraper can recognise that redirect. Redirects are
 * followed by hand so the session cookie can be kept on Google hosts.
 */
class HttpTestingPageSource(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val urlFor: (String) -> String = BetaLinkBuilder::scrapeUrl,
    private val followRedirect: (from: URL, to: URL) -> Boolean = ::redirectKeepsSession,
) : TestingPageSource {

    override suspend fun fetch(packageName: String, session: PlaySession): Result<FetchedPage> =
        withContext(io) {
            try {
                fetchFollowingRedirects(packageName, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.d("BetaScout", "fetch $packageName: failed $e")
                Result.failure(e)
            }
        }

    private suspend fun fetchFollowingRedirects(
        packageName: String,
        session: PlaySession,
    ): Result<FetchedPage> {
        var url = URL(urlFor(packageName))
        var hops = 0
        while (true) {
            val response = open(url, session).useCancellable(::read)
            val location = response.redirectTo
            if (location == null) {
                android.util.Log.d("BetaScout", "fetch $packageName: http=${response.code} url=$url")
                return response.toResult(url)
            }
            if (++hops > MAX_REDIRECTS || !followRedirect(url, location)) {
                android.util.Log.d("BetaScout", "fetch $packageName: redirect to $location refused")
                return Result.failure(HttpStatusException(response.code))
            }
            url = location
        }
    }

    private fun open(url: URL, session: PlaySession): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Cookie", session.cookieHeader)
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }

    private class Response(val code: Int, val html: String, val redirectTo: URL?) {
        fun toResult(url: URL): Result<FetchedPage> =
            if (code !in 200..299 && code != 404) {
                // Rate limiting and server errors carry no page worth parsing;
                // treating them as one would fabricate an UNKNOWN observation
                // that overwrites a good status and skips the failure counters.
                Result.failure(HttpStatusException(code))
            } else {
                Result.success(FetchedPage(html, finalUrl = url.toString()))
            }
    }

    private fun read(connection: HttpURLConnection): Response {
        val code = connection.responseCode
        if (code in REDIRECT_CODES) {
            val location = connection.getHeaderField("Location")
                ?: return Response(code, "", redirectTo = null)
            return Response(code, "", redirectTo = URL(connection.url, location))
        }
        val stream = when {
            code in 200..299 -> connection.inputStream
            // A 404 body is still meaningful: it is the "no testing program" page.
            code == 404 -> connection.errorStream
            // Every other status is reported as is, without touching its body: a
            // 429/403 is what arms the scan cooldown, and a body read that fails
            // (broken framing, stalled read) must not turn it into a generic
            // IOException the scraper does not recognise as a block.
            else -> null
        }
        val html = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return Response(code, html, redirectTo = null)
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7a) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
