package org.jarsi.betascout.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jarsi.betascout.domain.PlaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A store in memory that, like the real DataStore, hands an update's transform
 * the value current at that moment. (The real, file-backed store is exercised
 * on a device by SessionExpiryTest; on a development machine its atomic file
 * rename is not reliable enough to carry a unit test.)
 */
private class CallerContextStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    /** Number of [updateData] calls so far. */
    var updates = 0

    /** True while a transform is running. */
    var updating = false

    /** A write by someone else that lands right before the next update's
     *  transform is given the current value; runs once. */
    var concurrentWrite: ((Preferences) -> Preferences)? = null

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        updates++
        concurrentWrite?.let { write ->
            concurrentWrite = null
            state.value = write(state.value)
        }
        updating = true
        try {
            val updated = transform(state.value)
            state.value = updated
            return updated
        } finally {
            updating = false
        }
    }
}

/** [this] after one more sign-in, whose cookie is stored as [stored]. */
private fun Preferences.signedIn(stored: String): Preferences =
    toMutablePreferences().also { SessionPrefs.signIn(it, "user@example.com", stored) }

/** The session a run would have read from [prefs]. */
private fun sessionOf(prefs: Preferences, cookie: String) =
    PlaySession("user@example.com", cookie, SessionPrefs.generationOf(prefs))

/**
 * What is written on behalf of a session — its expiry, and what a scan run
 * leaves behind — is written only while that session is the stored one, and the
 * check is made on the very value the update then changes. The check reads no
 * cookie: it must not depend on the Keystore answering.
 */
class SessionStoreThreadTest {

    private val refusal = ScanRefusal(code = "scan_in_progress", accountKey = "user@example.com", order = 1)

    @Test
    fun `the session is checked inside the one update that expires it`() = runBlocking {
        val store = CallerContextStore()
        val first = emptyPreferences().signedIn("enc:SID=abc")
        store.updateData { first }
        val updatesBefore = store.updates
        var changedInsideAnUpdate = false

        val expired = store.editForSession(sessionOf(first, "SID=abc")) {
            changedInsideAnUpdate = store.updating
            SessionPrefs.expire(it)
        }

        assertTrue(expired)
        assertTrue("the change was made outside the update", changedInsideAnUpdate)
        assertEquals(1, store.updates - updatesBefore)
        assertTrue(SessionPrefs.isExpired(store.data.first()))
    }

    @Test
    fun `a sign-in that lands just before the expiry survives it`() = runBlocking {
        // The stored session IS the checked one when the call is made, so a
        // version that reads it first and removes it in a second, unconditional
        // write would go ahead — and wipe the session that arrived in between.
        val store = CallerContextStore()
        val old = emptyPreferences().signedIn("enc:SID=old")
        store.updateData { old }
        store.concurrentWrite = { it.signedIn("enc:SID=new") }

        val expired = store.editForSession(sessionOf(old, "SID=old")) { SessionPrefs.expire(it) }

        assertFalse(expired)
        val prefs = store.data.first()
        assertEquals("enc:SID=new", prefs[SessionPrefs.cookie])
        assertFalse(SessionPrefs.isExpired(prefs))
    }

    @Test
    fun `a session Google rejected is expired even when its stored cookie cannot be read`() = runBlocking {
        // Nothing here can decode what is stored. The session is known by the
        // sign-in it came from, so the Keystore not answering at the end of a
        // scan cannot leave a dead session standing as "signed in".
        val store = CallerContextStore()
        val prefs = emptyPreferences().signedIn("keystore:v1:unreadable-just-now")
        store.updateData { prefs }

        val expired = store.editForSession(sessionOf(prefs, "SID=abc")) { SessionPrefs.expire(it) }

        assertTrue(expired)
        assertEquals(SessionStanding.EXPIRED, SessionPrefs.standing(store.data.first()))
    }

    // What a scan run writes about itself is written for its session only.

    @Test
    fun `a record for the session still signed in is written, in the one update that checks it`() =
        runBlocking {
            val store = CallerContextStore()
            val prefs = emptyPreferences().signedIn("enc:SID=abc")
            store.updateData { prefs }
            val updatesBefore = store.updates

            val written = store.editForSession(sessionOf(prefs, "SID=abc")) {
                ScanRefusalPrefs.write(it, refusal)
            }

            assertTrue(written)
            assertEquals(refusal, ScanRefusalPrefs.read(store.data.first()))
            assertEquals(1, store.updates - updatesBefore)
        }

    @Test
    fun `a record for a session that has ended is not written`() = runBlocking {
        // A requested scan lost the lock to a single app's re-check, and that
        // re-check found the session dead and expired it — which removes what
        // the session's scans left behind. The refused run gets round to its
        // record only now.
        val store = CallerContextStore()
        val old = emptyPreferences().signedIn("enc:SID=old")
        store.updateData { old }
        store.edit { SessionPrefs.expire(it) }

        val written = store.editForSession(sessionOf(old, "SID=old")) {
            ScanRefusalPrefs.write(it, refusal)
        }

        assertFalse(written)
        assertNull(ScanRefusalPrefs.read(store.data.first()))
        // The same account signs in again: no "your scan did not run" from the
        // session before is waiting for it.
        store.edit { SessionPrefs.signIn(it, "user@example.com", "enc:SID=new") }
        assertNull(ScanRefusalPrefs.read(store.data.first()))
    }

    @Test
    fun `a new sign-in of the same account does not inherit the old session's record`() = runBlocking {
        // Same account, and for good measure the same cookie: still another
        // sign-in, and the run that read the earlier one writes nothing.
        val store = CallerContextStore()
        val old = emptyPreferences().signedIn("enc:SID=abc")
        store.updateData { old.toMutablePreferences().also { SessionPrefs.signOut(it) }.signedIn("enc:SID=abc") }

        val written = store.editForSession(sessionOf(old, "SID=abc")) {
            ScanRefusalPrefs.write(it, refusal)
        }

        assertFalse(written)
        assertNull(ScanRefusalPrefs.read(store.data.first()))
    }

    @Test
    fun `a sign-out that lands just before the record wins`() = runBlocking {
        // The session IS the stored one when the call is made; it is gone by
        // the time the update runs. Checked on the value the update changes.
        val store = CallerContextStore()
        val prefs = emptyPreferences().signedIn("enc:SID=abc")
        store.updateData { prefs }
        store.concurrentWrite = { now -> now.toMutablePreferences().also { SessionPrefs.signOut(it) } }

        val written = store.editForSession(sessionOf(prefs, "SID=abc")) {
            ScanRefusalPrefs.write(it, refusal)
        }

        assertFalse(written)
        assertNull(ScanRefusalPrefs.read(store.data.first()))
    }
}
