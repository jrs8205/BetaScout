package org.jarsi.betascout.ui.appdetail

import org.jarsi.betascout.domain.DataError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDetailCheckErrorTest {

    @Test
    fun `a rejected re-check while a scan runs is surfaced as scan-in-progress`() {
        assertEquals(
            CheckStatusError.SCAN_IN_PROGRESS,
            checkStatusErrorOf(DataError.ScanInProgress()),
        )
    }

    @Test
    fun `a rejected re-check during the Google-block cooldown is surfaced as blocked`() {
        assertEquals(
            CheckStatusError.SCAN_BLOCKED,
            checkStatusErrorOf(DataError.ScanBlocked(until = 99L)),
        )
    }

    @Test
    fun `a dead session produces no check error because the sign-in prompt takes over`() {
        assertNull(checkStatusErrorOf(DataError.NeedsLogin()))
    }

    @Test
    fun `a re-check started with a session that is no longer current shows nothing and clears nothing`() {
        // Unlike NeedsLogin this must not be treated as a dead session: the account
        // may simply have changed, and clearing the stored session would sign the
        // new account out.
        assertNull(checkStatusErrorOf(DataError.StaleSession()))
    }

    @Test
    fun `any other failure is surfaced as a generic check failure`() {
        assertEquals(
            CheckStatusError.FAILED,
            checkStatusErrorOf(DataError.Local(RuntimeException("boom"))),
        )
    }
}
