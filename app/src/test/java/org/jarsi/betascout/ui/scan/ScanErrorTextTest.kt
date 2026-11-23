package org.jarsi.betascout.ui.scan

import org.jarsi.betascout.R
import org.jarsi.betascout.work.BetaScanWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanErrorTextTest {

    @Test
    fun `the known scan errors have their own explanation`() {
        // The main screen shows these after a pull: without the text a pull made
        // during Google's cooldown looks exactly like a successful refresh.
        assertEquals(
            R.string.account_scan_blocked,
            scanErrorRes(BetaScanWorker.ERROR_SCAN_BLOCKED),
        )
        assertEquals(
            R.string.account_scan_in_progress,
            scanErrorRes(BetaScanWorker.ERROR_SCAN_IN_PROGRESS),
        )
    }

    @Test
    fun `an outcome that could not be recorded has words of its own, not a code on screen`() {
        assertEquals(
            R.string.scan_unrecorded,
            scanErrorRes(BetaScanWorker.ERROR_UNRECORDED),
        )
    }

    @Test
    fun `any other error falls back to the generic text with the message`() {
        assertNull(scanErrorRes("scan_failed"))
        assertNull(scanErrorRes("java.io.IOException: timeout"))
    }
}
