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

    private fun useCase() = SignOutUseCase(
        cancelScanWork = { calls += "cancelScanWork" },
        withScanLock = { block ->
            calls += "lock:acquired"
            block()
            calls += "lock:released"
        },
        clearObservations = { calls += "clearObservations" },
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
                "clearObservations",
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
            clearObservations = { calls += "clearObservations" },
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
    fun `wipes observations unconditionally so orphaned account keys cannot linger`() = runTest {
        // A failed email capture keys observations by a cookie hash; a re-login
        // mints a new hash, so a per-account wipe would leave the old key's rows
        // behind forever. Sign-out therefore always clears the whole table.
        useCase().signOut()

        assertTrue("clearObservations" in calls)
        assertTrue("clearSession" in calls)
        assertTrue("clearWebViewCookies" in calls)
        assertEquals("reschedule", calls.last())
    }
}
