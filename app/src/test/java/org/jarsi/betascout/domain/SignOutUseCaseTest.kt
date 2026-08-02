package org.jarsi.betascout.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun `sign-out runs to completion even when its caller is cancelled mid-way`() = runTest {
        // The account screen's ViewModel can be cleared mid-cleanup (back
        // navigation pops the destination); the wipe must still finish, or the
        // session would be gone while the WebView cookies survive.
        val sessionCleared = CompletableDeferred<Unit>()
        val useCase = SignOutUseCase(
            cancelScanWork = { calls += "cancelScanWork" },
            withScanLock = { block -> block() },
            currentAccountKey = { "user@example.com" },
            clearObservations = { calls += "clearObservations:$it" },
            clearSession = {
                sessionCleared.complete(Unit)
                calls += "clearSession"
            },
            clearLastScan = { calls += "clearLastScan" },
            clearWebViewCookies = {
                delay(1)
                calls += "clearWebViewCookies"
            },
            rescheduleBackgroundScans = { calls += "reschedule" },
        )

        val job = launch { useCase.signOut() }
        sessionCleared.await()
        job.cancel()
        advanceUntilIdle()

        assertTrue("clearWebViewCookies" in calls)
        assertEquals("reschedule", calls.last())
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
