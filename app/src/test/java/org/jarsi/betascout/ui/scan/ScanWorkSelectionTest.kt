package org.jarsi.betascout.ui.scan

import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun info(state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state, emptySet())

class ScanWorkSelectionTest {

    // What WorkManager's reports mean for this screen.

    @Test
    fun `the run under way is reported, whatever finished runs are listed around it`() {
        // getWorkInfosForUniqueWorkFlow guarantees no order: the previous run's
        // SUCCEEDED info can precede the current RUNNING one.
        val tracker = ScanWorkTracker()
        val running = info(WorkInfo.State.RUNNING)

        val report = tracker.onReport(
            listOf(info(WorkInfo.State.SUCCEEDED), running, info(WorkInfo.State.FAILED)),
        )

        assertEquals(running, report.active)
    }

    @Test
    fun `a run waiting to start counts as under way`() {
        val tracker = ScanWorkTracker()
        val enqueued = info(WorkInfo.State.ENQUEUED)

        assertEquals(enqueued, tracker.onReport(listOf(info(WorkInfo.State.CANCELLED), enqueued)).active)
    }

    @Test
    fun `finished runs alone mean nothing is under way`() {
        // How a finished run ended is not read from these reports at all: a run
        // that was refused records that itself (see ScanRefusal), so nothing
        // depends on which finished run a report happens to list, or on anyone
        // having watched it.
        val tracker = ScanWorkTracker()

        assertNull(
            tracker.onReport(listOf(info(WorkInfo.State.FAILED), info(WorkInfo.State.SUCCEEDED))).active,
        )
        assertNull(tracker.onReport(emptyList()).active)
    }

    @Test
    fun `the history WorkManager reports first is not a scan that began`() {
        // On a cold start the user can pull before WorkManager has answered at
        // all. Its first answer, arriving during the pull's package read, lists
        // runs that ended before the app was even opened: counting those as a
        // new round would drop the pull's scan without a word.
        val tracker = ScanWorkTracker()

        val report = tracker.onReport(
            listOf(info(WorkInfo.State.SUCCEEDED), info(WorkInfo.State.CANCELLED)),
        )

        assertFalse(report.newRound)
    }

    @Test
    fun `a scan already under way in the first report is a round`() {
        val tracker = ScanWorkTracker()

        val report = tracker.onReport(
            listOf(info(WorkInfo.State.SUCCEEDED), info(WorkInfo.State.RUNNING)),
        )

        assertTrue(report.newRound)
    }

    @Test
    fun `every later scan generation is a round, even one never seen running`() {
        val tracker = ScanWorkTracker()
        val old = info(WorkInfo.State.SUCCEEDED)
        tracker.onReport(listOf(old))

        // The same list again: nothing began.
        assertFalse(tracker.onReport(listOf(old)).newRound)
        // WorkManager's updates are conflated: a run enqueued and cancelled
        // between two of them shows up only as a finished one — with an id that
        // was not there before.
        assertTrue(tracker.onReport(listOf(old, info(WorkInfo.State.CANCELLED))).newRound)
    }

    // When a pull may start its scan.

    @Test
    fun `a pull made during a scan never starts one, even if that scan is cancelled meanwhile`() {
        // The pull's own list refresh can be slow. If the user cancels the
        // running scan while it is still going, the state is free again by the
        // time the pull would enqueue — and the scan just cancelled would start
        // over, as far as the user can tell.
        val scanning = ScanUiState(signedIn = true, busy = true)
        val free = ScanUiState(signedIn = true)

        assertFalse(mayStartPullScan(atPull = scanning, atEnqueue = free))
    }

    @Test
    fun `a scan started and cancelled while the pull waited invalidates the pull`() {
        // The pull's package read is slow; meanwhile the user opens settings,
        // starts a scan and cancels it. Free at the pull and free again at the
        // end: two snapshots cannot tell that a scan came and went in between,
        // and the pull would put a new scan in the place of the cancelled one.
        // The round can tell.
        val free = ScanUiState(signedIn = true, sessionGeneration = 1, scanRound = 3)

        assertFalse(mayStartPullScan(atPull = free, atEnqueue = free.copy(scanRound = 4)))
        assertTrue(mayStartPullScan(atPull = free, atEnqueue = free))
    }

    @Test
    fun `a pull is checked again right before the scan is enqueued`() {
        val free = ScanUiState(signedIn = true, sessionGeneration = 1)

        assertTrue(mayStartPullScan(atPull = free, atEnqueue = free))
        // Another scan started while the list was refreshing.
        assertFalse(mayStartPullScan(atPull = free, atEnqueue = free.copy(busy = true)))
        // The session went away while the list was refreshing.
        assertFalse(mayStartPullScan(atPull = free, atEnqueue = free.copy(signedIn = false)))
        // Signed out at the pull: signing in meanwhile does not turn that pull
        // into a scan request.
        assertFalse(mayStartPullScan(atPull = ScanUiState(signedIn = false), atEnqueue = free))
    }

    @Test
    fun `a pull starts the quick scan only when signed in and no scan is in flight`() {
        assertTrue(ScanUiState(signedIn = true).canStartQuickScan())
        // Signed out: the worker would have nobody to scan for.
        assertFalse(ScanUiState(signedIn = false).canStartQuickScan())
        // A manual run is already going.
        assertFalse(ScanUiState(signedIn = true, busy = true).canStartQuickScan())
        // The periodic worker holds the scan lock.
        assertFalse(
            ScanUiState(signedIn = true, busy = true, backgroundScanning = true).canStartQuickScan(),
        )
        // The periodic worker is running but has not taken the lock yet (it
        // refreshes the installed apps first): busy is still false, and a manual
        // run started now would race it for the lock.
        assertFalse(
            ScanUiState(signedIn = true, backgroundScanning = true).canStartQuickScan(),
        )
        // A cancelled run is still unwinding inside the lock.
        assertFalse(
            ScanUiState(signedIn = true, busy = true, cancelling = true).canStartQuickScan(),
        )
    }

    @Test
    fun `a scan can be asked for by button only once the session is known here`() {
        // The account screen shows the scan buttons as soon as its own view
        // model has read the session. This one reads the session for itself and
        // can be a moment behind; a request made in that moment could not be
        // tied to a sign-in.
        assertFalse(ScanUiState(signedIn = false).canRequestScan())
        assertTrue(ScanUiState(signedIn = true).canRequestScan())
        // As before: not on top of a scan in flight or one still unwinding.
        assertFalse(ScanUiState(signedIn = true, busy = true).canRequestScan())
        assertFalse(ScanUiState(signedIn = true, busy = true, cancelling = true).canRequestScan())
    }

    @Test
    fun `a pull made in one sign-in never starts a scan in the next, even as the same account`() {
        // Signed out and in again as the same account while the pull was
        // reading the installed apps, and WorkManager has not reported the
        // sign-in's own scan yet: the account is the same and so is the round.
        // The request would carry the new sign-in's tag, and the run's check
        // of that tag would let it through.
        val atPull = ScanUiState(signedIn = true, accountKey = "a@example.com", sessionGeneration = 4)

        assertTrue(mayStartPullScan(atPull, atEnqueue = atPull))
        assertFalse(mayStartPullScan(atPull, atEnqueue = atPull.copy(sessionGeneration = 5)))
        // No sign-in known at the pull: nothing to tie the request to.
        assertFalse(
            mayStartPullScan(
                atPull = atPull.copy(sessionGeneration = null),
                atEnqueue = atPull.copy(sessionGeneration = null),
            ),
        )
    }

    @Test
    fun `a pull made as one account never starts a scan as another`() {
        // Signed out and in again as someone else while the pull was reading
        // the installed apps: both snapshots allow a scan, and nothing scanned
        // in between. But the person now signed in did not pull.
        val atPull = ScanUiState(signedIn = true, accountKey = "a@example.com", sessionGeneration = 4)

        assertTrue(mayStartPullScan(atPull, atEnqueue = atPull))
        assertFalse(mayStartPullScan(atPull, atEnqueue = atPull.copy(accountKey = "b@example.com")))
    }
}
