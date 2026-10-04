package org.jarsi.betascout.data.remote

import java.net.ServerSocket
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.testutil.Reply
import org.jarsi.betascout.testutil.TinyHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CatalogWorkerClientTest {

    private val server = TinyHttpServer()

    @Before
    fun startServer() = server.start()

    @After
    fun stopServer() = server.stop()

    private fun client() = CatalogWorkerClient(baseUrl = "http://127.0.0.1:${server.port}")

    @Test
    fun `fetchCatalog returns the body of a 200 answer`() = runTest {
        server.body = """{"programs":[]}"""

        assertEquals("""{"programs":[]}""", client().fetchCatalog())
        assertEquals("/", server.requests.single().path)
    }

    @Test
    fun `fetchCatalog returns null on any other status`() = runTest {
        server.status = 404
        server.body = "catalog not found"

        assertNull(client().fetchCatalog())
    }

    @Test
    fun `fetchCatalog returns null when the worker is unreachable`() = runTest {
        val closedPort = ServerSocket(0).use { it.localPort }

        assertNull(CatalogWorkerClient(baseUrl = "http://127.0.0.1:$closedPort").fetchCatalog())
    }

    @Test
    fun `cancelling an in-flight catalog fetch propagates instead of becoming a null result`() {
        // Real time on purpose: the blocking HttpURLConnection read is real IO. A
        // cancelled list screen must neither wait out the 10 s read timeout nor get
        // a null back — null means "fall back to the cache", which would start a
        // 600 KB parse for a screen that is gone.
        server.respond = false
        var returned = false

        runBlocking {
            lateinit var job: Job
            val elapsed = measureTimeMillis {
                job = launch(Dispatchers.IO) {
                    client().fetchCatalog()
                    returned = true
                }
                delay(300)
                job.cancel()
                job.join()
            }
            assertTrue("cancellation waited ${elapsed}ms for the fetch", elapsed < 5_000)
        }
        assertFalse("a cancelled fetch must not complete with a result", returned)
    }

    @Test
    fun `postHints sends the packages as JSON and reports a 2xx answer`() = runTest {
        server.status = 204

        val ok = client().postHints(listOf("com.a", "com.b"))

        assertTrue(ok)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/hints", request.path)
        assertEquals("""{"version":1,"packages":["com.a","com.b"]}""", request.body)
        assertTrue(request.headers["content-type"]!!.startsWith("application/json"))
    }

    @Test
    fun `postHints reports false when the worker refuses`() = runTest {
        server.handler = { Reply(429) }

        assertFalse(client().postHints(listOf("com.a")))
    }

    @Test
    fun `cancelling an in-flight hint post propagates instead of becoming false`() {
        server.respond = false
        var returned = false

        runBlocking {
            lateinit var job: Job
            val elapsed = measureTimeMillis {
                job = launch(Dispatchers.IO) {
                    client().postHints(listOf("com.a"))
                    returned = true
                }
                delay(300)
                job.cancel()
                job.join()
            }
            assertTrue("cancellation waited ${elapsed}ms for the post", elapsed < 5_000)
        }
        assertFalse("a cancelled post must not complete with a result", returned)
    }
}
