package org.jarsi.betascout.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.domain.PlaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan screens read how a scan ended from the settings store, with
 * collectors that are started once and live as long as the screen's view
 * model. One failed read of the store must not be the end of that: what a
 * later scan records has to reach the same collectors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsWatchTest {

    @Test
    fun `the scan reader never interprets a failed cooldown read as permission to fetch`() = runTest {
        val store = FlakyStore()
        val settings = settings(store)
        settings.setScanBlockedUntil(1234L)
        store.failingReads = 1
        val failure = runCatching { settings.readScanBlockedUntil() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1234L, settings.readScanBlockedUntil())
    }

    private val refusal = ScanRefusal(code = "scan_blocked", accountKey = "user@example.com", order = 3)

    private fun TestScope.settings(store: DataStore<Preferences>) =
        SettingsRepository(store, SessionCookieCipher(), io = StandardTestDispatcher(testScheduler))

    /** Everything [flow] delivers while the test goes on. */
    private fun <T> TestScope.watch(flow: Flow<T>): Pair<List<T>, () -> Unit> {
        val seen = mutableListOf<T>()
        val job = launch { flow.collect { seen += it } }
        return seen to { job.cancel() }
    }

    @Test
    fun `a refusal recorded after a failed read reaches the watcher that was already there`() = runTest {
        // The screen starts watching while the store cannot be read. The store
        // recovers, the user asks for a scan, the scan is refused and says so
        // in the store.
        val store = FlakyStore(failingReads = 1)
        val (seen, stop) = watch(settings(store).scanRefusal)
        advanceUntilIdle()

        store.edit { ScanRefusalPrefs.write(it, refusal) }
        advanceUntilIdle()
        stop()

        assertEquals(refusal, seen.lastOrNull())
    }

    @Test
    fun `a failed read is not taken for a refusal that has gone away`() = runTest {
        // A refusal is on screen; then one read fails. Nothing was settled:
        // the line must not blink out, and the watch goes on.
        val store = FlakyStore()
        store.edit { ScanRefusalPrefs.write(it, refusal) }
        val (seen, stop) = watch(settings(store).scanRefusal)
        advanceUntilIdle()

        store.failReaders()
        advanceUntilIdle()
        val later = refusal.copy(code = "scan_in_progress", order = 4)
        store.edit { ScanRefusalPrefs.write(it, later) }
        advanceUntilIdle()
        stop()

        assertEquals(listOf(refusal, later), seen)
    }

    @Test
    fun `the other outcomes of a scan are watched the same way`() = runTest {
        val store = FlakyStore(failingReads = 3)
        val settings = settings(store)
        val (summaries, stopSummaries) = watch(settings.lastScan)
        val (latest, stopLatest) = watch(settings.scanDone)
        val (expiries, stopExpiries) = watch(settings.sessionExpired)
        advanceUntilIdle()

        val summary = LastScanInfo(at = 1_000L, checked = 4, joined = 1, notJoined = 3)
        store.edit { prefs ->
            SessionPrefs.signIn(prefs, "user@example.com", "SID=plain")
            ScanOutcomes.recordDone(prefs, "user@example.com", order = 5, summary = summary)
            SessionPrefs.expire(prefs)
        }
        advanceUntilIdle()
        stopSummaries()
        stopLatest()
        stopExpiries()

        assertEquals(summary.copy(accountKey = "user@example.com", order = 5), summaries.lastOrNull())
        assertEquals(ScanDone("user@example.com", order = 5), latest.lastOrNull())
        assertEquals(true, expiries.lastOrNull())
    }

    @Test
    fun `a failed read shows as signed out for the moment, and the session comes back`() = runTest {
        // Whoever asks once (first()) is answered at once, as before: nobody
        // readable is signed in. A screen that goes on watching gets the
        // session when the store reads again — without it the scan outcomes
        // above would come back to a screen that believes nobody is signed in.
        val store = FlakyStore(failingReads = 1)
        store.edit { SessionPrefs.signIn(it, "user@example.com", "SID=plain") }
        val (seen, stop) = watch(settings(store).playSession)
        advanceUntilIdle()
        stop()

        assertEquals(
            listOf(null, PlaySession("user@example.com", "SID=plain", generation = 1)),
            seen,
        )
    }

    @Test
    fun `asking once for the session while the store cannot be read is still answered at once`() = runTest {
        val store = FlakyStore(failingReads = 1)
        store.edit { SessionPrefs.signIn(it, "user@example.com", "SID=plain") }

        assertNull(settings(store).playSession.first())
        // Not after a retry pause: no time has passed.
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `the cooldown is answered at once when asked once, and watched past a failed read`() = runTest {
        val store = FlakyStore(failingReads = 2)
        store.edit { it[longPreferencesKey("scan_blocked_until")] = 5_000L }
        val settings = settings(store)

        // The scan's own question, asked once: not blocked as far as can be read.
        assertNull(settings.scanBlockedUntil.first())
        val (seen, stop) = watch(settings.scanBlockedUntil)
        advanceUntilIdle()
        stop()

        assertEquals(listOf(null, 5_000L), seen)
    }
}
