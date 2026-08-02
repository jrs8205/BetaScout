package org.jarsi.betascout.ui.scan

import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun info(state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state, emptySet())

class ScanWorkSelectionTest {

    @Test
    fun `an active generation is preferred over a finished one listed first`() {
        // getWorkInfosForUniqueWorkFlow guarantees no order: the previous run's
        // SUCCEEDED info can precede the current RUNNING one.
        val finished = info(WorkInfo.State.SUCCEEDED)
        val running = info(WorkInfo.State.RUNNING)

        assertEquals(running, pickRelevantScanWork(listOf(finished, running)))
    }

    @Test
    fun `an enqueued generation counts as active`() {
        val cancelled = info(WorkInfo.State.CANCELLED)
        val enqueued = info(WorkInfo.State.ENQUEUED)

        assertEquals(enqueued, pickRelevantScanWork(listOf(cancelled, enqueued)))
    }

    @Test
    fun `finished history is ignored when the watched run is unknown`() {
        // After process death lastActiveScanId is gone; picking an arbitrary
        // finished leftover could resurface a stale FAILED outcome. The real
        // last-scan summary is persisted separately, so showing nothing here
        // is honest.
        val first = info(WorkInfo.State.SUCCEEDED)
        val second = info(WorkInfo.State.CANCELLED)

        assertNull(pickRelevantScanWork(listOf(first, second)))
    }

    @Test
    fun `the generation last seen active wins once everything is finished`() {
        // An old FAILED run can precede the just-finished SUCCEEDED one in the
        // unordered list; the UI must keep following the run it watched, not
        // resurface the old failure after a success.
        val oldFailed = info(WorkInfo.State.FAILED)
        val justFinished = info(WorkInfo.State.SUCCEEDED)

        assertEquals(
            justFinished,
            pickRelevantScanWork(listOf(oldFailed, justFinished), preferredId = justFinished.id),
        )
    }

    @Test
    fun `returns null when there is no work at all`() {
        assertNull(pickRelevantScanWork(emptyList()))
    }
}
