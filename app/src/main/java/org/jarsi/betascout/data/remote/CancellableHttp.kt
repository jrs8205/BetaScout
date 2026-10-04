package org.jarsi.betascout.data.remote

import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Runs [block] against this connection with coroutine cancellation wired in.
 * Blocking HttpURLConnection IO cannot observe cancellation on its own: a
 * cancelled caller would otherwise stay parked until the connect/read timeout
 * runs out (and, for a scan, keep holding the scan lock). A watcher coroutine
 * disconnects the socket the moment the caller is cancelled, and the IOException
 * that disconnect provokes is surfaced as the cancellation it really is — never
 * as a failure or fallback result.
 *
 * Must be called on an IO dispatcher; the connection is disconnected on exit.
 */
internal suspend fun <T> HttpURLConnection.useCancellable(block: (HttpURLConnection) -> T): T =
    coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                disconnect()
            }
        }
        try {
            block(this@useCancellable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ensureActive()
            throw e
        } finally {
            watcher.cancel()
            disconnect()
        }
    }
