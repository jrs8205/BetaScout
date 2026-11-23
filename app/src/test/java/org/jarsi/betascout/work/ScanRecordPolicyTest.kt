package org.jarsi.betascout.work

import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID
import org.jarsi.betascout.domain.DataError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRecordPolicyTest {

    @Test
    fun `a run that checked something always becomes the last scan`() {
        assertTrue(recordsLastScan(manual = false, recordEmptyRun = true, checked = 3, failed = 0))
        assertTrue(recordsLastScan(manual = true, recordEmptyRun = true, checked = 3, failed = 0))
        assertTrue(recordsLastScan(manual = true, recordEmptyRun = false, checked = 1, failed = 0))
    }

    @Test
    fun `the Scan button reports even when nothing was due`() {
        // The user pressed a button and looks at the card for the answer.
        assertTrue(recordsLastScan(manual = true, recordEmptyRun = true, checked = 0, failed = 0))
    }

    @Test
    fun `a background run with nothing due keeps the last real result`() {
        assertFalse(recordsLastScan(manual = false, recordEmptyRun = true, checked = 0, failed = 0))
    }

    @Test
    fun `a pull-to-refresh run with nothing due keeps the last real result`() {
        // Pulling is cheap and frequent; each empty pull would otherwise replace
        // the last scan's counts and failure reason with "0 apps checked".
        assertFalse(recordsLastScan(manual = true, recordEmptyRun = false, checked = 0, failed = 0))
    }

    @Test
    fun `a run where every check failed is not an empty run`() {
        // checked counts successful observations only. A first request answered
        // with HTTP 429 leaves checked at 0 with one failure: that run happened
        // and its failure reason is exactly what the card has to show.
        assertTrue(recordsLastScan(manual = true, recordEmptyRun = false, checked = 0, failed = 1))
        assertTrue(recordsLastScan(manual = false, recordEmptyRun = true, checked = 0, failed = 4))
        assertTrue(recordsLastScan(manual = true, recordEmptyRun = true, checked = 0, failed = 2))
    }

    @Test
    fun `a refused scan is recorded under the code the screens have words for`() {
        assertEquals(
            BetaScanWorker.ERROR_SCAN_IN_PROGRESS,
            refusalCodeOf(DataError.ScanInProgress()),
        )
        assertEquals(
            BetaScanWorker.ERROR_SCAN_BLOCKED,
            refusalCodeOf(DataError.ScanBlocked(until = 5_000L)),
        )
    }

    @Test
    fun `any other failure is recorded with its message`() {
        assertEquals("Local storage error", refusalCodeOf(DataError.Local(RuntimeException("boom"))))
        assertEquals("scan_failed", refusalCodeOf(RuntimeException()))
    }

    @Test
    fun `the worker skips its own package read only right after the list did one`() {
        val now = 1_000_000_000_000L
        assertTrue(appsMirrorIsFresh(refreshedAt = now - 2_000, now = now))
        assertTrue(appsMirrorIsFresh(refreshedAt = now, now = now))
    }

    @Test
    fun `a run without a recent list refresh reads the packages itself`() {
        val now = 1_000_000_000_000L
        // No timestamp: the Scan button, the full scan, the periodic worker.
        assertFalse(appsMirrorIsFresh(refreshedAt = 0, now = now))
        // A pull made offline: the run waited for a connection for an hour, and
        // an app installed meanwhile must still join the scan.
        assertFalse(appsMirrorIsFresh(refreshedAt = now - 3_600_000, now = now))
        // The clock moved backwards: the timestamp proves nothing.
        assertFalse(appsMirrorIsFresh(refreshedAt = now + 5_000, now = now))
    }

    @Test
    fun `a session that could not be read is tried again a few times, not for ever`() {
        // A Keystore or disk hiccup: the next start will most likely read it.
        assertTrue(retriesUnreadableSession(attempt = 0))
        assertTrue(retriesUnreadableSession(attempt = 2))
        // Still unreadable after that: for this app the session is gone, and a
        // requested scan must not stay "starting" for good.
        assertFalse(retriesUnreadableSession(attempt = 3))
        assertFalse(retriesUnreadableSession(attempt = 10))
    }

    @Test
    fun `a request carries the sign-in it was made in`() {
        val tags = setOf("org.jarsi.betascout.work.BetaScanWorker", BetaScanWorker.sessionTag(7))

        assertEquals(7L, BetaScanWorker.sessionOfTags(tags))
        assertEquals(null, BetaScanWorker.sessionOfTags(setOf("org.jarsi.betascout.work.BetaScanWorker")))
        assertEquals(null, BetaScanWorker.sessionOfTags(setOf("session:not-a-number")))
    }

    @Test
    fun `a request made in another sign-in is not run for the one signed in now`() {
        assertTrue(requestIsFor(requestedFor = 7, generation = 7))
        // It waited out a sign-out and the next sign-in.
        assertFalse(requestIsFor(requestedFor = 7, generation = 8))
        // An untagged manual request predates the update. A numbered sign-in cannot own it.
        assertFalse(requestIsFor(requestedFor = null, generation = 8))
        assertTrue(requestIsFor(requestedFor = null, generation = 0))
        assertTrue(requestIsFor(requestedFor = 0, generation = 0))
    }

    @Test
    fun `the highest order left in the work record is found among whatever is listed`() {
        // A failed request whose outcome never reached the store carries its
        // order in its result. A new process has to start above it.
        val failed = { order: Long ->
            WorkInfo(
                UUID.randomUUID(),
                WorkInfo.State.FAILED,
                emptySet(),
                workDataOf(
                    BetaScanWorker.KEY_OUTCOME to BetaScanWorker.OUTCOME_UNRECORDED,
                    BetaScanWorker.KEY_ORDER to order,
                ),
            )
        }
        val recorded = WorkInfo(
            UUID.randomUUID(),
            WorkInfo.State.FAILED,
            emptySet(),
            workDataOf(BetaScanWorker.KEY_OUTCOME to BetaScanWorker.OUTCOME_RECORDED),
        )
        val running = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet())

        assertEquals(13L, highestOrderOnRecord(listOf(failed(11), recorded, failed(13), running)))
        assertEquals(0L, highestOrderOnRecord(listOf(recorded, running)))
        assertEquals(0L, highestOrderOnRecord(emptyList()))
    }

    @Test
    fun `a scan asked for from the app is tagged with the sign-in it is asked in`() {
        // There is no way to build the request without one: an untagged request
        // would be run for whoever is signed in when it starts, and its failure
        // could not be shown to anyone.
        val request = BetaScanScheduler.manualScanRequest(sessionGeneration = 7)

        assertEquals(7L, BetaScanWorker.sessionOfTags(request.tags))
        assertTrue(request.workSpec.input.getBoolean(BetaScanWorker.KEY_MANUAL, false))
    }

    @Test
    fun `a long error message is cut before it is stored or sent on`() {
        val code = refusalCodeOf(RuntimeException("x".repeat(5_000)))

        assertEquals(200, code.length)
    }
}
