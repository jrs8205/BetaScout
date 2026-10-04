package org.jarsi.betascout.data.scrape

import java.net.URL
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.testutil.Reply
import org.jarsi.betascout.testutil.TinyHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpTestingPageSourceTest {

    private val server = TinyHttpServer()

    private val session = PlaySession(accountEmail = "user@example.com", cookieHeader = "SID=abc")

    @Before
    fun startServer() = server.start()

    @After
    fun stopServer() = server.stop()

    private fun source() = HttpTestingPageSource(
        urlFor = { pkg -> "http://127.0.0.1:${server.port}/apps/testing/$pkg" },
    )

    @Test
    fun `a successful page load is returned as a fetched page`() = runTest {
        server.status = 200
        server.body = """<html><body><form id="joinForm"></form></body></html>"""

        val page = source().fetch("com.example", session).getOrThrow()

        assertEquals(server.body, page.html)
    }

    @Test
    fun `the 404 no-program page still counts as a page`() = runTest {
        server.status = 404
        server.body = "<html><body>The requested URL was not found.</body></html>"

        val page = source().fetch("com.example", session).getOrThrow()

        assertEquals(server.body, page.html)
    }

    @Test
    fun `rate limiting is a failure, not a page`() = runTest {
        // A typical 429 has no meaningful body; parsing it would fabricate an
        // UNKNOWN observation that overwrites a good one.
        server.status = 429
        server.body = ""

        val result = source().fetch("com.example", session)

        val error = result.exceptionOrNull()
        assertTrue("expected HttpStatusException, was $error", error is HttpStatusException)
        assertEquals("HTTP 429", error!!.message)
    }

    @Test
    fun `cancelling aborts an in-flight fetch instead of waiting out the read timeout`() {
        // Real time on purpose (no runTest): the blocking HttpURLConnection read is
        // real IO, and the point is that cancellation must not be gated on its
        // 15-second timeout — that stall keeps the scan lock held after a cancel.
        server.respond = false

        runBlocking {
            lateinit var job: Job
            val elapsed = measureTimeMillis {
                job = launch(Dispatchers.IO) { source().fetch("com.example", session) }
                delay(300)
                job.cancel()
                job.join()
            }

            assertTrue("cancellation waited ${elapsed}ms for the fetch", elapsed < 5_000)
        }
    }

    @Test
    fun `a throttling answer whose body cannot be read still reports the status`() = runTest {
        // Google's 429/403 is what arms the one-hour scan cooldown. If reading the
        // (worthless) error body fails — broken chunk framing here, a stalled read
        // in the field — the result must still be HttpStatusException(429), not a
        // generic IOException the scraper does not recognise as a block.
        server.handler = {
            Reply(
                429,
                raw = "HTTP/1.1 429 Too Many Requests\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Connection: close\r\n\r\n" +
                    "not-a-chunk\r\n",
            )
        }

        val error = source().fetch("com.example", session).exceptionOrNull()

        assertTrue("expected HttpStatusException, was $error", error is HttpStatusException)
        assertEquals(429, (error as HttpStatusException).code)
    }

    @Test
    fun `a server error is a failure, not a page`() = runTest {
        server.status = 503
        server.body = "<html>Service Unavailable</html>"

        val result = source().fetch("com.example", session)

        assertTrue(result.exceptionOrNull() is HttpStatusException)
    }

    @Test
    fun `a redirect on the same host is followed and the final url reported`() = runTest {
        server.handler = { request ->
            if (request.path == "/apps/testing/com.example") {
                Reply(302, headers = mapOf("Location" to "http://127.0.0.1:${server.port}/moved"))
            } else {
                Reply(200, """<html><body><form id="joinForm"></form></body></html>""")
            }
        }

        val page = source().fetch("com.example", session).getOrThrow()

        assertEquals("http://127.0.0.1:${server.port}/moved", page.finalUrl)
        assertEquals(listOf("/apps/testing/com.example", "/moved"), server.requests.map { it.path })
        assertEquals("SID=abc", server.requests.last().headers["cookie"])
    }

    @Test
    fun `a redirect to a foreign host is not followed and the session cookie stays home`() = runTest {
        // Android's HttpURLConnection re-sends every request header on a cross-host
        // redirect, Cookie included; PRIVACY.md promises the Google session goes to
        // Google only, so anything but the same host or *.google.com stops here.
        server.handler = {
            Reply(302, headers = mapOf("Location" to "http://localhost:${server.port}/elsewhere"))
        }

        val result = source().fetch("com.example", session)

        val error = result.exceptionOrNull()
        assertTrue("expected HttpStatusException, was $error", error is HttpStatusException)
        assertEquals(302, (error as HttpStatusException).code)
        assertEquals(listOf("/apps/testing/com.example"), server.requests.map { it.path })
    }

    @Test
    fun `the default redirect policy keeps the Google session on Google over https`() {
        val from = URL("https://play.google.com/apps/testing/com.example?hl=en")

        assertTrue(redirectKeepsSession(from, URL("https://accounts.google.com/v3/signin/identifier")))
        assertTrue(redirectKeepsSession(from, URL("https://play.google.com/store/apps/details?id=x")))
        assertFalse(redirectKeepsSession(from, URL("https://play.google.com.evil.example/")))
        assertFalse(redirectKeepsSession(from, URL("https://evil.example/google.com")))
        assertFalse(redirectKeepsSession(from, URL("http://accounts.google.com/plaintext")))
    }
}
