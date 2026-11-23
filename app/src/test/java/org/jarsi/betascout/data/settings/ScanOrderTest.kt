package org.jarsi.betascout.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.ui.scan.FailedRequest
import org.jarsi.betascout.ui.scan.unrecordedFailure
import org.jarsi.betascout.work.BetaScanWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The place of an outcome the store would not take. It travels in the request's
 * work result instead, and a place of 0 there means "not known" — such a
 * stand-in is never retired by a later scan. So it must be known whenever it
 * can be: the process needs the store only to find out where it starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanOrderTest {

    private val session = PlaySession("user@example.com", "SID=plain", generation = 1)

    private fun TestScope.settings(store: DataStore<Preferences>) =
        SettingsRepository(store, SessionCookieCipher(), io = StandardTestDispatcher(testScheduler))

    @Test
    fun `an outcome the store takes neither as a record nor as a read still gets its place`() = runTest {
        // The process has recorded a scan before, so it knows where it counts
        // from. Then the store fails altogether: the refusal cannot be
        // written, and the read that used to be made for its order fails too.
        val store = FlakyStore()
        val settings = settings(store)
        store.edit { SessionPrefs.signIn(it, "user@example.com", "SID=plain") }
        settings.saveScanDone(session, settings.scanTicket(), summary = null, ordersOnRecord = 0)
        val refused = settings.scanTicket()
        store.failingWrites = true
        store.failingReads = Int.MAX_VALUE
        val written = runCatching { settings.saveScanRefusal(session, "scan_failed", refused, ordersOnRecord = 0) }

        val order = settings.scanOrder(refused, ordersOnRecord = 0)

        assertTrue("was $written", written.exceptionOrNull() is IOException)
        assertEquals(2L, order)
    }

    @Test
    fun `a scan that gets through afterwards retires the stand-in of that outcome`() = runTest {
        // The whole way round: with its place known, the failed request is
        // answered by the background scan that gets through once the store
        // works again. With a place of 0 it would stay on screen.
        val store = FlakyStore()
        val settings = settings(store)
        store.edit { SessionPrefs.signIn(it, "user@example.com", "SID=plain") }
        settings.saveScanDone(session, settings.scanTicket(), summary = null, ordersOnRecord = 0)
        val refused = settings.scanTicket()
        store.failingWrites = true
        store.failingReads = Int.MAX_VALUE
        val request = FailedRequest(
            sessionGeneration = session.generation,
            outcome = BetaScanWorker.OUTCOME_UNRECORDED,
            code = "scan_failed",
            order = settings.scanOrder(refused, ordersOnRecord = 0) ?: 0L,
        )

        store.failingWrites = false
        store.failingReads = 0
        settings.saveScanDone(session, settings.scanTicket(), summary = null, ordersOnRecord = request.order)
        val done = settings.scanDone.first()

        assertEquals(ScanDone("user@example.com", order = 3), done)
        assertNull(unrecordedFailure(listOf(request), session, done))
    }

    @Test
    fun `recovering work history places a new completion above an older failed request`() = runTest {
        val store = FlakyStore()
        val settings = settings(store)
        store.edit {
            SessionPrefs.signIn(it, session.accountEmail, session.cookieHeader)
            ScanOutcomes.recordDone(it, session.accountKey, 10, null)
        }
        val failure = FailedRequest(session.generation, BetaScanWorker.OUTCOME_UNRECORDED, "scan_failed", 14)
        suspend fun runScan(history: suspend () -> Long) {
            val base = settings.priorScanOrder(history) ?: return
            settings.saveScanDone(session, settings.scanTicket(), summary = null, ordersOnRecord = base)
        }

        runScan { throw IOException("work history unavailable") }
        runScan { 14 }
        val done = settings.scanDone.first()
        assertNull(unrecordedFailure(listOf(failure), session, done))
        assertEquals(15L, done?.order)
        assertEquals(14L, settings.priorScanOrder { error("the base was already established") })
    }

    @Test
    fun `before the process has counted anything, an unreadable store leaves the place unknown`() = runTest {
        // Nothing to go by: where this process starts is not settled, and
        // cannot be without the store.
        val store = FlakyStore(failingReads = Int.MAX_VALUE)

        assertNull(settings(store).scanOrder(1, ordersOnRecord = 0))
    }
}
