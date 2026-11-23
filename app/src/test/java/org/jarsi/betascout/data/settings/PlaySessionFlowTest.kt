package org.jarsi.betascout.data.settings

import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaySessionFlowTest {

    private val encrypted = StoredSession(email = "User@Example.com", cookie = "keystore:v1:iv:ct")

    private fun isEncrypted(value: String) = value.startsWith("keystore:")

    /** For the tests that are not about trying again: the pause never ends. */
    private val noRetry: suspend (Long) -> Unit = { awaitCancellation() }

    @Test
    fun `an unchanged stored session is decrypted only once`() = runTest {
        // Every DataStore edit (scan counters, cooldowns, reported packages) re-emits
        // the whole Preferences snapshot; three ViewModels collect this flow on Main,
        // so each emission used to cost three AndroidKeyStore round-trips there.
        var decrypts = 0

        val sessions = flowOf(encrypted, encrypted, encrypted)
            .toPlaySessions(::isEncrypted, { decrypts++; "SID=abc" }, UnconfinedTestDispatcher(testScheduler), noRetry)
            .toList()

        assertEquals(1, sessions.size)
        assertEquals("SID=abc", sessions.single()!!.cookieHeader)
        assertEquals(1, decrypts)
    }

    @Test
    fun `decryption runs on the io dispatcher, not the collector's thread`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "settings-io") }
        var decryptThread: String? = null
        try {
            runBlocking {
                flowOf(encrypted)
                    .toPlaySessions(
                        isEncrypted = ::isEncrypted,
                        decrypt = { decryptThread = Thread.currentThread().name; "SID=abc" },
                        io = executor.asCoroutineDispatcher(),
                        pause = noRetry,
                    )
                    .first()
            }
        } finally {
            executor.shutdown()
        }

        // The coroutine debug agent suffixes thread names with " @coroutine#N".
        assertTrue("decrypted on $decryptThread", decryptThread!!.startsWith("settings-io"))
    }

    @Test
    fun `a legacy plaintext cookie is still a session and is not sent to the cipher`() = runTest {
        var decrypts = 0

        val session = flowOf(StoredSession("a@b.c", "SID=plain"))
            .toPlaySessions(::isEncrypted, { decrypts++; null }, UnconfinedTestDispatcher(testScheduler), noRetry)
            .first()

        assertEquals("SID=plain", session!!.cookieHeader)
        assertEquals("a@b.c", session.accountEmail)
        assertEquals(0, decrypts)
    }

    @Test
    fun `an unreadable encrypted cookie means signed out`() = runTest {
        val session = flowOf(encrypted)
            .toPlaySessions(::isEncrypted, { null }, UnconfinedTestDispatcher(testScheduler), noRetry)
            .first()

        assertNull(session)
    }

    @Test
    fun `no stored cookie means signed out`() = runTest {
        val session = flowOf(StoredSession("a@b.c", null))
            .toPlaySessions(::isEncrypted, { "never" }, UnconfinedTestDispatcher(testScheduler), noRetry)
            .first()

        assertNull(session)
    }

    // A cookie the Keystore would not give up.

    @Test
    fun `a stored cookie that could not be read is asked for again, until the session is there`() = runTest {
        // The stored values do not change, so nothing would ever make this
        // flow look at them again. A collector that met the Keystore at a bad
        // moment — a view model that lives as long as its screen — would go on
        // believing nobody is signed in, while another screen, which read a
        // moment later, shows the account.
        var decrypts = 0
        val pauses = mutableListOf<Long>()

        val sessions = flowOf(encrypted)
            .toPlaySessions(
                isEncrypted = ::isEncrypted,
                decrypt = { if (decrypts++ < 2) null else "SID=abc" },
                io = UnconfinedTestDispatcher(testScheduler),
                pause = { pauses += it },
            )
            .toList()

        // Signed out for the moment, as before; then the session.
        assertEquals(2, sessions.size)
        assertNull(sessions[0])
        assertEquals("SID=abc", sessions[1]!!.cookieHeader)
        assertEquals(3, decrypts)
        assertEquals(listOf(0L, 1L), pauses)
    }

    @Test
    fun `a cookie that still cannot be read is not waited for once another session is stored`() = runTest {
        val newer = StoredSession(email = "user@example.com", cookie = "keystore:v1:new", generation = 2)
        val decrypted = mutableListOf<String>()

        val sessions = flowOf(encrypted, newer)
            .toPlaySessions(
                isEncrypted = ::isEncrypted,
                decrypt = { stored ->
                    decrypted += stored
                    if (stored == newer.cookie) "SID=new" else null
                },
                io = UnconfinedTestDispatcher(testScheduler),
                pause = noRetry,
            )
            .toList()

        assertEquals("SID=new", sessions.last()!!.cookieHeader)
        assertEquals(2L, sessions.last()!!.generation)
        assertEquals(listOf(encrypted.cookie, newer.cookie), decrypted)
    }

    @Test
    fun `nothing stored, or a plain-text cookie, is never asked for again`() = runTest {
        val pauses = mutableListOf<Long>()

        val signedOut = flowOf(StoredSession("a@b.c", null))
            .toPlaySessions(::isEncrypted, { "never" }, UnconfinedTestDispatcher(testScheduler), { pauses += it })
            .toList()
        val plain = flowOf(StoredSession("a@b.c", "SID=plain"))
            .toPlaySessions(::isEncrypted, { null }, UnconfinedTestDispatcher(testScheduler), { pauses += it })
            .toList()

        assertEquals(listOf(null), signedOut)
        assertEquals("SID=plain", plain.single()!!.cookieHeader)
        assertEquals(emptyList<Long>(), pauses)
    }
}
