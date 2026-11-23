package org.jarsi.betascout.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the records two scan runs leave behind are reconciled. Each run writes
 * from its own coroutine after the scan lock is released, so the records can
 * land in either order; what counts is the order of the events, which every
 * record carries.
 */
class ScanOutcomesTest {

    private val account = "a@example.com"

    private fun refusal(order: Long, accountKey: String = account, code: String = "scan_in_progress") =
        ScanRefusal(code = code, accountKey = accountKey, order = order)

    private fun summary(checked: Int = 3, failed: Int = 0, accountKey: String = account) = LastScanInfo(
        at = 1_000L,
        checked = checked,
        joined = 1,
        notJoined = 2,
        failed = failed,
        accountKey = accountKey,
    )

    @Test
    fun `a scan that gets through settles the refusal before it`() {
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordRefusal(prefs, refusal(order = 5))

        ScanOutcomes.recordDone(prefs, account, order = 6, summary = summary())

        assertNull(ScanRefusalPrefs.read(prefs))
        assertEquals(ScanDone(account, order = 6), ScanDonePrefs.read(prefs))
        // The summary takes the place of its scan.
        assertEquals(summary().copy(order = 6), LastScanPrefs.read(prefs))
    }

    @Test
    fun `so does a scan that had nothing to record`() {
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordRefusal(prefs, refusal(order = 5))

        ScanOutcomes.recordDone(prefs, account, order = 6, summary = null)

        assertNull(ScanRefusalPrefs.read(prefs))
    }

    @Test
    fun `a refusal written late, after the scan that answered it, is not brought back`() {
        // A requested scan lost the lock to a background run with nothing due.
        // That run finished afterwards, but its record reached the store first.
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, account, order = 6, summary = null)

        ScanOutcomes.recordRefusal(prefs, refusal(order = 5))

        assertNull(ScanRefusalPrefs.read(prefs))
    }

    @Test
    fun `a scan's late record does not remove a refusal that came after it`() {
        // The other way round: an old run's record arrives after a request made
        // since then was refused. That refusal has not been answered by anyone.
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordRefusal(prefs, refusal(order = 6))

        ScanOutcomes.recordDone(prefs, account, order = 5, summary = null)

        assertEquals(refusal(order = 6), ScanRefusalPrefs.read(prefs))
        assertEquals(ScanDone(account, order = 5), ScanDonePrefs.read(prefs))
    }

    @Test
    fun `an older scan's late record does not replace a newer one`() {
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, account, order = 7, summary = summary(checked = 9))

        ScanOutcomes.recordDone(prefs, account, order = 6, summary = summary(checked = 1))

        assertEquals(ScanDone(account, order = 7), ScanDonePrefs.read(prefs))
        assertEquals(summary(checked = 9).copy(order = 7), LastScanPrefs.read(prefs))
    }

    @Test
    fun `a summary written late, after a later scan with nothing due, is still kept`() {
        // A scan checked five apps and ended. A background run right after it
        // had nothing due — and its record reached the store first. The five
        // apps' summary is still the last real result: dropping it would leave
        // the settings saying "no scan yet".
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, account, order = 2, summary = null)

        ScanOutcomes.recordDone(prefs, account, order = 1, summary = summary(checked = 5))

        assertEquals(summary(checked = 5).copy(order = 1), LastScanPrefs.read(prefs))
        // And the empty run stays the latest scan.
        assertEquals(ScanDone(account, order = 2), ScanDonePrefs.read(prefs))
    }

    @Test
    fun `a late older scan settles the refusals from before it, even when it is not the latest`() {
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordRefusal(prefs, refusal(order = 3))
        // Stored out of order: nothing is known yet of the scan at 4.
        ScanOutcomes.recordDone(prefs, account, order = 9, summary = null)
        assertNull(ScanRefusalPrefs.read(prefs))

        ScanOutcomes.recordRefusal(prefs, refusal(order = 10))
        ScanOutcomes.recordDone(prefs, account, order = 4, summary = summary())

        // The refusal at 10 came after both scans.
        assertEquals(refusal(order = 10), ScanRefusalPrefs.read(prefs))
        assertEquals(ScanDone(account, order = 9), ScanDonePrefs.read(prefs))
    }

    @Test
    fun `a scan with nothing due keeps the last summary and stops it being the latest outcome`() {
        // The summary is still the last real result and stays for the settings
        // card. But a scan has got through since, and the screens must be able
        // to tell: the failure it describes is no longer how things stand.
        val failed = summary(checked = 0, failed = 4)
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, account, order = 5, summary = failed)

        ScanOutcomes.recordDone(prefs, account, order = 6, summary = null)

        assertEquals(failed.copy(order = 5), LastScanPrefs.read(prefs))
        assertEquals(ScanDone(account, order = 6), ScanDonePrefs.read(prefs))
    }

    @Test
    fun `of two refusals the later one stays, whichever is written last`() {
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordRefusal(prefs, refusal(order = 7, code = "scan_blocked"))

        ScanOutcomes.recordRefusal(prefs, refusal(order = 6, code = "scan_in_progress"))

        assertEquals(refusal(order = 7, code = "scan_blocked"), ScanRefusalPrefs.read(prefs))
    }

    @Test
    fun `a scan of one account settles nothing for another`() {
        val ofB = refusal(order = 5, accountKey = "b@example.com")
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordRefusal(prefs, ofB)

        ScanOutcomes.recordDone(prefs, account, order = 6, summary = summary())

        assertEquals(ofB, ScanRefusalPrefs.read(prefs))
    }

    @Test
    fun `no clock decides which came first`() {
        // The summary's time is for showing. A clock that ran ahead and was
        // then set right leaves a summary "from the future" behind; a request
        // refused after it is still the later event.
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(
            prefs,
            account,
            order = 5,
            summary = summary().copy(at = Long.MAX_VALUE / 2),
        )

        ScanOutcomes.recordRefusal(prefs, refusal(order = 6))

        assertEquals(refusal(order = 6), ScanRefusalPrefs.read(prefs))
    }

    @Test
    fun `the highest stored order is known whichever record holds it`() {
        assertEquals(0L, ScanOutcomes.highestOrder(mutablePreferencesOf()))

        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, account, order = 5, summary = null)
        ScanOutcomes.recordRefusal(prefs, refusal(order = 9))

        assertEquals(9L, ScanOutcomes.highestOrder(prefs))

        // A summary can outlive both: an expiry removes the refusal, and the
        // latest-scan record is the newer of the two only at first.
        val onlySummary = mutablePreferencesOf()
        LastScanPrefs.write(onlySummary, summary().copy(order = 12))
        assertEquals(12L, ScanOutcomes.highestOrder(onlySummary))
    }
}
