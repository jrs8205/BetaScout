package org.jarsi.betascout.data.scrape

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.domain.PlaySession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Minimal single-threaded HTTP server; com.sun.net.httpserver is not on the
 *  android.jar compile classpath, so responses are written by hand. */
private class TinyHttpServer {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    @Volatile var status = 200
    @Volatile var body = ""

    private val thread = Thread {
        try {
            while (true) {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val bytes = body.toByteArray()
                    client.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 $status Status\r\n" +
                                    "Content-Type: text/html\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        write(bytes)
                        flush()
                    }
                }
            }
        } catch (_: Exception) {
            // The server socket was closed by stop(); the thread just ends.
        }
    }

    fun start() = thread.apply { isDaemon = true }.start()

    fun stop() = socket.close()
}

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
    fun `a server error is a failure, not a page`() = runTest {
        server.status = 503
        server.body = "<html>Service Unavailable</html>"

        val result = source().fetch("com.example", session)

        assertTrue(result.exceptionOrNull() is HttpStatusException)
    }
}
