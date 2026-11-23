package org.jarsi.betascout.ui.scan

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.jarsi.betascout.data.settings.LastScanInfo
import org.jarsi.betascout.data.settings.ScanDone
import org.jarsi.betascout.data.settings.ScanDonePrefs
import org.jarsi.betascout.data.settings.ScanOutcomes
import org.jarsi.betascout.data.settings.ScanRefusal
import org.jarsi.betascout.data.settings.ScanSequence
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.work.BetaScanWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanNoticeTest {

    private val signedIn = ScanUiState(signedIn = true)

    private fun lastScan(
        checked: Int,
        failed: Int,
        reason: String? = null,
        at: Long = 1_000L,
        accountKey: String? = "a@example.com",
        order: Long = 3,
    ) = LastScanInfo(
        order = order,
        at = at,
        checked = checked,
        joined = 0,
        notJoined = 0,
        failed = failed,
        failureReason = reason,
        accountKey = accountKey,
    )

    private fun refusal(code: String, order: Long = 2, accountKey: String = "a@example.com") =
        ScanRefusal(code = code, accountKey = accountKey, order = order)

    private fun done(order: Long = 3, accountKey: String = "a@example.com") =
        ScanDone(accountKey = accountKey, order = order)

    private val session = PlaySession("a@example.com", "SID=abc", generation = 4)

    private fun failed(
        outcome: String? = BetaScanWorker.OUTCOME_UNRECORDED,
        code: String? = BetaScanWorker.ERROR_SCAN_BLOCKED,
        order: Long = 7,
        sessionGeneration: Long? = 4,
    ) = FailedRequest(sessionGeneration = sessionGeneration, outcome = outcome, code = code, order = order)

    // Whose record it is.

    @Test
    fun `a stored summary is shown only to the account it belongs to`() {
        val ofA = lastScan(checked = 0, failed = 5, accountKey = "a@example.com")

        assertEquals(ofA, visibleLastScan(ofA, accountKey = "a@example.com"))
        // A's session expired and B signed in; or a run for A finished after B
        // had signed in and stored its summary under B's nose.
        assertNull(visibleLastScan(ofA, accountKey = "b@example.com"))
        assertNull(visibleLastScan(ofA, accountKey = null))
        assertNull(visibleLastScan(null, accountKey = "a@example.com"))
    }

    @Test
    fun `a summary without an owner is shown to nobody`() {
        // 0.6.9 did not record the owner, and being signed in at the update
        // proves nothing: A's session may have expired and B signed in while
        // still on 0.6.9, which left A's summary in place. Until a scan of the
        // signed-in account's own is recorded, there is nothing to show.
        val legacy = lastScan(checked = 0, failed = 5, accountKey = null)

        assertNull(visibleLastScan(legacy, accountKey = "b@example.com"))
        assertNull(visibleLastScan(legacy, accountKey = null))
    }

    @Test
    fun `a stored refusal is shown only to the account it belongs to`() {
        val ofA = refusal("scan_failed", accountKey = "a@example.com")

        assertEquals(ofA, visibleRefusal(ofA, accountKey = "a@example.com"))
        assertNull(visibleRefusal(ofA, accountKey = "b@example.com"))
        assertNull(visibleRefusal(ofA, accountKey = null))
        assertNull(visibleRefusal(null, accountKey = "a@example.com"))
    }

    @Test
    fun `the record of the latest scan is shown only to the account it belongs to`() {
        val ofA = done(accountKey = "a@example.com")

        assertEquals(ofA, visibleDone(ofA, accountKey = "a@example.com"))
        assertNull(visibleDone(ofA, accountKey = "b@example.com"))
        assertNull(visibleDone(ofA, accountKey = null))
        assertNull(visibleDone(null, accountKey = "a@example.com"))
    }

    @Test
    fun `the state shows the stored outcomes as the signed-in account's`() {
        val summary = lastScan(checked = 0, failed = 5)
        val refused = refusal("scan_failed")
        val latest = done()

        val asA = ScanUiState(signedIn = true)
            .withOutcomesOf("a@example.com", summary, refused, latest)
        assertEquals("a@example.com", asA.accountKey)
        assertEquals(summary, asA.lastScan)
        assertEquals(refused, asA.refusal)
        assertEquals(latest, asA.done)

        // Somebody else, or nobody: the same records, none of them shown.
        val asB = asA.withOutcomesOf("b@example.com", summary, refused, latest)
        assertEquals("b@example.com", asB.accountKey)
        assertNull(asB.lastScan)
        assertNull(asB.refusal)
        assertNull(asB.done)
        val signedOut = asA.withOutcomesOf(null, summary, refused, latest)
        assertNull(signedOut.accountKey)
        assertNull(signedOut.refusal)
    }

    // What the latest recorded scan says.

    @Test
    fun `a latest scan in which no check succeeded is reported`() {
        // The worker finishes such a run normally (it did run and recorded its
        // summary), so nothing but these numbers tells it from a clean one.
        assertEquals(
            ScanNotice.RunFailed(ScanRunFailure(failed = 1, reason = "HTTP 429")),
            signedIn.copy(lastScan = lastScan(checked = 0, failed = 1, reason = "HTTP 429"))
                .mainScreenNotice(),
        )
        assertEquals(
            ScanNotice.RunFailed(ScanRunFailure(failed = 3, reason = null)),
            signedIn.copy(lastScan = lastScan(checked = 0, failed = 3)).mainScreenNotice(),
        )
    }

    @Test
    fun `a scan with nothing due, or with some successes, is not a failure`() {
        assertNull(signedIn.mainScreenNotice())
        assertNull(signedIn.copy(lastScan = lastScan(checked = 0, failed = 0)).mainScreenNotice())
        // Partly failed: the statuses did update; the details are in settings.
        assertNull(
            signedIn.copy(lastScan = lastScan(checked = 5, failed = 2, reason = "timeout"))
                .mainScreenNotice(),
        )
    }

    @Test
    fun `a later scan that got through takes the retry promise away`() {
        val afterFailedPull = signedIn.copy(lastScan = lastScan(checked = 0, failed = 5, "timeout"))
        val afterBackgroundRun = afterFailedPull.copy(lastScan = lastScan(checked = 5, failed = 0))

        assertEquals(
            ScanNotice.RunFailed(ScanRunFailure(failed = 5, reason = "timeout")),
            afterFailedPull.mainScreenNotice(),
        )
        assertNull(afterBackgroundRun.mainScreenNotice())
    }

    @Test
    fun `nothing about a scan is reported to a signed-out user`() {
        val signedOut = ScanUiState(
            signedIn = false,
            lastScan = lastScan(checked = 0, failed = 5, reason = "timeout"),
        )

        assertNull(signedOut.mainScreenNotice())
        assertNull(signedOut.copy(lastScan = null, refusal = refusal("scan_failed")).mainScreenNotice())
    }

    // A scan the user asked for that did not run.

    @Test
    fun `a scan that could not start is reported with its error`() {
        assertEquals(
            ScanNotice.Error("scan_failed"),
            signedIn.copy(refusal = refusal("scan_failed")).mainScreenNotice(),
        )
    }

    @Test
    fun `while a scan runs the progress strip speaks, not an older outcome`() {
        val state = signedIn.copy(
            busy = true,
            refusal = refusal("scan_failed"),
            lastScan = lastScan(checked = 0, failed = 2),
        )

        assertNull(state.mainScreenNotice())
    }

    @Test
    fun `a scan that lost the lock is reported afterwards, without claiming a scan still runs`() {
        val lostLock = signedIn.copy(refusal = refusal(BetaScanWorker.ERROR_SCAN_IN_PROGRESS))

        // The lock was held by something that never shows as a running scan (a
        // single app's re-check), or the other scan is over by now: either way
        // the requested scan did not happen and the user is told — in the past
        // tense, which stays true however long the line is up.
        assertEquals(ScanNotice.NotStarted, lostLock.mainScreenNotice())
        assertEquals(ScanNotice.NotStarted, lostLock.refusalNotice())

        // While the other scan visibly runs, the main screen shows the progress
        // strip, and the settings card may say "a scan is running": it is.
        assertNull(lostLock.copy(busy = true).mainScreenNotice())
        assertEquals(ScanNotice.OtherScanRunning, lostLock.copy(busy = true).refusalNotice())
    }

    @Test
    fun `a rate-limited scan says so while the cooldown lasts, and that it did not run afterwards`() {
        val blockedRun = signedIn.copy(refusal = refusal(BetaScanWorker.ERROR_SCAN_BLOCKED))

        assertEquals(
            ScanNotice.Error(BetaScanWorker.ERROR_SCAN_BLOCKED),
            blockedRun.copy(blocked = true).mainScreenNotice(),
        )
        // "Google is rate limiting right now" an hour after it stopped would be
        // false. But the scan the user asked for still did not run — the work
        // may have been refused with the app closed — and they are still told.
        assertEquals(ScanNotice.NotStarted, blockedRun.copy(blocked = false).mainScreenNotice())
    }

    @Test
    fun `a later scan with nothing due ends the failure line, and the summary stays`() {
        // A full scan failed on every page while the earlier observations were
        // still fresh. The user pulls: nothing is due, the run gets through.
        // That run records no summary, so the stored one still says "5 failed"
        // — which is right for the settings card and wrong as the answer to
        // the refresh the user has just made.
        val failed = lastScan(checked = 0, failed = 5, reason = "timeout", order = 3)
        val afterFailedScan = signedIn.copy(lastScan = failed, done = done(order = 3))
        val afterEmptyPull = afterFailedScan.copy(done = done(order = 4))

        assertEquals(
            ScanNotice.RunFailed(ScanRunFailure(failed = 5, reason = "timeout")),
            afterFailedScan.mainScreenNotice(),
        )
        assertNull(afterEmptyPull.mainScreenNotice())
        assertNull(afterEmptyPull.latestRunFailure())
        assertEquals(failed, afterEmptyPull.lastScan)
    }

    @Test
    fun `a new request that waits is not reported as a scan that runs`() {
        // An earlier request lost the lock. The user tries again, offline: the
        // new work waits for a connection. It makes the state busy, and read
        // against that the old refusal would say "a scan is already running"
        // while nothing scans. With a new request in flight the old one's
        // refusal has nothing more to say.
        val lostLock = signedIn.copy(refusal = refusal(BetaScanWorker.ERROR_SCAN_IN_PROGRESS))
        val retryWaiting = lostLock.copy(busy = true, requested = true)

        assertNull(retryWaiting.refusalNotice())
        assertNull(retryWaiting.mainScreenNotice())
        // The other case stays: no request of ours in flight, and a scan runs.
        assertEquals(ScanNotice.OtherScanRunning, lostLock.copy(busy = true).refusalNotice())
    }

    @Test
    fun `a refusal is reported whatever the clock says about the last summary`() {
        // The store keeps a refusal only until a scan gets through after it;
        // nothing is worked out here from times. A summary dated in the future
        // (the clock ran ahead, then was set right) hides nothing.
        val state = signedIn.copy(
            refusal = refusal(BetaScanWorker.ERROR_SCAN_IN_PROGRESS),
            lastScan = lastScan(checked = 4, failed = 0, at = Long.MAX_VALUE / 2, order = 1),
            done = done(order = 1),
        )

        assertEquals(ScanNotice.NotStarted, state.mainScreenNotice())
        assertEquals(ScanNotice.NotStarted, state.refusalNotice())
    }

    @Test
    fun `a summary that reached the store after a later empty scan is not the latest outcome`() {
        // Written late, kept as the last real result — but a scan has got
        // through after it, and its failure is not how things stand.
        val state = signedIn.copy(
            lastScan = lastScan(checked = 0, failed = 5, order = 1),
            done = done(order = 2),
        )

        assertNull(state.mainScreenNotice())
    }

    // When the outcome never reached the store.

    @Test
    fun `a failed request whose outcome could not be stored is still reported`() {
        // The settings file could not be written when the run tried to record
        // why it did not run. WorkManager lists the request as failed, and that
        // is all there is: without a stand-in the progress strip just goes.
        val standIn = unrecordedFailure(listOf(failed()), session, done = null)

        assertEquals(
            ScanRefusal(BetaScanWorker.ERROR_SCAN_BLOCKED, "a@example.com", order = 7),
            standIn,
        )
        val state = signedIn.withOutcomesOf("a@example.com", null, null, null, unrecorded = standIn)
        assertEquals(ScanNotice.NotStarted, state.mainScreenNotice())
    }

    @Test
    fun `a run that broke off without a word is reported as ended badly`() {
        // No outcome, no code: the run never got as far as saying anything.
        val standIn = unrecordedFailure(
            listOf(failed(outcome = null, code = null, order = 0)),
            session,
            done = null,
        )

        assertEquals(
            ScanNotice.Error(BetaScanWorker.ERROR_UNRECORDED),
            signedIn.withOutcomesOf("a@example.com", null, null, null, standIn).mainScreenNotice(),
        )
    }

    @Test
    fun `a failed request that recorded its outcome, or ended on purpose, needs no stand-in`() {
        // Recorded: the store has it, and the store is what counts. Quiet: it
        // found nobody left to tell (signed out, or another sign-in).
        assertNull(unrecordedFailure(listOf(failed(outcome = BetaScanWorker.OUTCOME_RECORDED)), session, null))
        assertNull(unrecordedFailure(listOf(failed(outcome = BetaScanWorker.OUTCOME_QUIET)), session, null))
        assertNull(unrecordedFailure(emptyList(), session, null))
    }

    @Test
    fun `a failed request is reported only in the session it was made in`() {
        // Made before a sign-out; whoever is signed in now did not ask for it —
        // be it someone else or the same account again.
        assertNull(unrecordedFailure(listOf(failed(sessionGeneration = 3)), session, null))
        // A request that does not say whose it was is nobody's.
        assertNull(unrecordedFailure(listOf(failed(sessionGeneration = null)), session, null))
        assertNull(unrecordedFailure(listOf(failed()), session = null, done = null))
    }

    @Test
    fun `only the imported session sees an unrecorded outcome of its untagged request`() {
        val imported = session.copy(generation = 0)
        val legacy = failed(sessionGeneration = null)
        assertEquals(legacy.code, unrecordedFailure(listOf(legacy), imported, null)?.code)
        assertNull(unrecordedFailure(listOf(legacy), session, null))
        assertNull(unrecordedFailure(listOf(legacy.copy(outcome = null)), imported, null))
    }

    @Test
    fun `a scan that got through afterwards retires the stand-in`() {
        val request = listOf(failed(order = 7))

        assertNull(unrecordedFailure(request, session, done(order = 8)))
        // Not one from before it, and not another account's.
        assertEquals(7L, unrecordedFailure(request, session, done(order = 6))?.order)
        assertEquals(7L, unrecordedFailure(request, session, done(order = 8, accountKey = "b@example.com"))?.order)
    }

    @Test
    fun `after a restart a scan that gets through still retires the stand-in of the process before`() {
        // The whole way round. The earlier process could not store its outcome:
        // the failed request carries its order, the store still ends at 10. The
        // new process is told of that order from the work record before it
        // gives out one of its own, so the background scan that gets through
        // next is placed after the request, not level with it.
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, "a@example.com", order = 10, summary = null)
        val earlier = ScanSequence()
        val request = failed(order = earlier.orderOf(earlier.ticket(), prefs, heldElsewhere = 0))
        val restarted = ScanSequence()

        val gotThrough = restarted.orderOf(restarted.ticket(), prefs, heldElsewhere = request.order)
        ScanOutcomes.recordDone(prefs, "a@example.com", gotThrough, summary = null)

        assertNull(unrecordedFailure(listOf(request), session, ScanDonePrefs.read(prefs)))
    }

    @Test
    fun `of a stored refusal and a stand-in the later one is shown`() {
        val stored = refusal("scan_in_progress", order = 5)
        val earlier = refusal(BetaScanWorker.ERROR_UNRECORDED, order = 4)
        val later = refusal(BetaScanWorker.ERROR_UNRECORDED, order = 6)

        assertEquals(stored, shownRefusal(stored, earlier))
        assertEquals(later, shownRefusal(stored, later))
        assertEquals(stored, shownRefusal(stored, null))
        assertEquals(later, shownRefusal(null, later))
        assertNull(shownRefusal(null, null))
        // A stand-in whose place is not known gives way to what is stored.
        assertEquals(stored, shownRefusal(stored, later.copy(order = 0)))
    }

    @Test
    fun `the cooldown is over when the wall clock says so`() {
        val until = 1_000_000_000_000L

        assertEquals(true, cooldownActive(blockedUntil = until, now = until - 1))
        // Judged against the wall clock, like the scan's own decision — not by a
        // timer that stops counting while the phone sleeps.
        assertEquals(false, cooldownActive(blockedUntil = until, now = until))
        assertEquals(false, cooldownActive(blockedUntil = until, now = until + 3_600_000))
        assertEquals(false, cooldownActive(blockedUntil = null, now = until))
    }
}
