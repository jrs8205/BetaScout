package org.jarsi.betascout.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutUseCaseTest {

    private val calls = mutableListOf<String>()

    private fun useCase(accountKey: String? = "user@example.com") = SignOutUseCase(
        cancelScanWork = { calls += "cancelScanWork" },
        withScanLock = { block ->
            calls += "lock:acquired"
            block()
            calls += "lock:released"
        },
        currentAccountKey = { accountKey },
        clearObservations = { calls += "clearObservations:$it" },
        clearSession = { calls += "clearSession" },
        clearLastScan = { calls += "clearLastScan" },
        clearWebViewCookies = { calls += "clearWebViewCookies" },
        rescheduleBackgroundScans = { calls += "reschedule" },
    )

    @Test
    fun `wipes account data inside the scan lock and reschedules only at the very end`() = runTest {
        useCase().signOut()

        assertEquals(
            listOf(
                "cancelScanWork",
                "lock:acquired",
                "clearObservations:user@example.com",
                "clearSession",
                "clearLastScan",
                "lock:released",
                "clearWebViewCookies",
                // Last on purpose: a fresh periodic request can start immediately,
                // and by now the session is gone so the run is a no-op.
                "reschedule",
            ),
            calls,
        )
    }

    @Test
    fun `skips the observation wipe when no account is signed in`() = runTest {
        useCase(accountKey = null).signOut()

        assertTrue(calls.none { it.startsWith("clearObservations") })
        assertTrue("clearSession" in calls)
        assertTrue("clearWebViewCookies" in calls)
        assertEquals("reschedule", calls.last())
    }
}
