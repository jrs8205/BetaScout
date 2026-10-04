package org.jarsi.betascout.data.remote

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's two calls to the BetaScout catalog Worker: the public catalog GET and
 * the opt-in discovery-hint POST. Neither request carries anything identifying
 * the user; see PRIVACY.md.
 */
class CatalogWorkerClient(
    private val baseUrl: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** GET the catalog; null on any failure so the caller can fall back. Cancellation
     *  is not a failure and propagates. */
    suspend fun fetchCatalog(): String? = withContext(io) {
        try {
            open(baseUrl).useCancellable { connection ->
                connection.requestMethod = "GET"
                if (connection.responseCode == 200) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** POST discovery hints; true only on a 2xx answer. Package names are plain
     *  `[A-Za-z0-9_.]` identifiers, so the JSON needs no escaping. */
    suspend fun postHints(packages: List<String>): Boolean = withContext(io) {
        try {
            open("$baseUrl/hints").useCancellable { connection ->
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val body = """{"version":1,"packages":[${
                    packages.joinToString(",") { "\"$it\"" }
                }]}"""
                connection.outputStream.use { it.write(body.toByteArray()) }
                connection.responseCode in 200..299
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
