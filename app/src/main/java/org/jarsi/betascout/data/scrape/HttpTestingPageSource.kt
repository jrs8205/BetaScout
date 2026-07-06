package org.jarsi.betascout.data.scrape

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jarsi.betascout.domain.BetaLinkBuilder
import org.jarsi.betascout.domain.PlaySession

/**
 * Fetches the testing page over HTTP with the user's Play web-session cookies. A
 * browser User-Agent asks for the web opt-in page (the one carrying the join/leave
 * forms). If the session has expired, Google redirects to the sign-in page, whose
 * HTML the parser recognises as "needs login".
 */
class HttpTestingPageSource(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : TestingPageSource {

    override suspend fun fetch(packageName: String, session: PlaySession): Result<String> =
        withContext(io) {
            try {
                val connection = (URL(BetaLinkBuilder.testingUrl(packageName))
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Cookie", session.cookieHeader)
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }
                try {
                    val code = connection.responseCode
                    android.util.Log.d("BetaScout", "fetch $packageName: http=$code url=${connection.url}")
                    val stream = if (code in 200..299) {
                        connection.inputStream
                    } else {
                        // A 404 body is still meaningful: it is the "no testing program" page.
                        connection.errorStream
                    }
                    Result.success(stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
