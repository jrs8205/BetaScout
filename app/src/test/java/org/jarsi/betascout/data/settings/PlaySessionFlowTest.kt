package org.jarsi.betascout.data.settings

import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
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

    @Test
    fun `an unchanged stored session is decrypted only once`() = runTest {
        // Every DataStore edit (scan counters, cooldowns, reported packages) re-emits
        // the whole Preferences snapshot; three ViewModels collect this flow on Main,
        // so each emission used to cost three AndroidKeyStore round-trips there.
        var decrypts = 0

        val sessions = flowOf(encrypted, encrypted, encrypted)
            .toPlaySessions(::isEncrypted, { decrypts++; "SID=abc" }, UnconfinedTestDispatcher(testScheduler))
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
            .toPlaySessions(::isEncrypted, { decrypts++; null }, UnconfinedTestDispatcher(testScheduler))
            .first()

        assertEquals("SID=plain", session!!.cookieHeader)
        assertEquals("a@b.c", session.accountEmail)
        assertEquals(0, decrypts)
    }

    @Test
    fun `an unreadable encrypted cookie means signed out`() = runTest {
        val session = flowOf(encrypted)
            .toPlaySessions(::isEncrypted, { null }, UnconfinedTestDispatcher(testScheduler))
            .first()

        assertNull(session)
    }

    @Test
    fun `no stored cookie means signed out`() = runTest {
        val session = flowOf(StoredSession("a@b.c", null))
            .toPlaySessions(::isEncrypted, { "never" }, UnconfinedTestDispatcher(testScheduler))
            .first()

        assertNull(session)
    }
}
