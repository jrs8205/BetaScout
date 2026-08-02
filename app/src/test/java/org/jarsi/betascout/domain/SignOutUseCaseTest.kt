package org.jarsi.betascout.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutUseCaseTest {

    private val calls = mutableListOf<String>()

    private fun useCase(accountKey: String? = "user@example.com") = SignOutUseCase(
        cancelScanWork = { calls += "cancelScanWork" },
        awaitScanIdle = { calls += "awaitScanIdle" },
        rescheduleBackgroundScans = { calls += "reschedule" },
        currentAccountKey = { accountKey },
        clearObservations = { calls += "clearObservations:$it" },
        clearSession = { calls += "clearSession" },
        clearLastScan = { calls += "clearLastScan" },
        clearWebViewCookies = { calls += "clearWebViewCookies" },
    )

    @Test
    fun `clears account data only after scan work is cancelled and the scan lock is free`() = runTest {
        useCase().signOut()

        assertEquals(
            listOf(
                "cancelScanWork",
                "awaitScanIdle",
                "reschedule",
                "clearObservations:user@example.com",
                "clearSession",
                "clearLastScan",
                "clearWebViewCookies",
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
    }
}
