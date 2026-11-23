package org.jarsi.betascout.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored records of what scans left behind: a request that did not run, and
 * the latest scan that got through. They are written by the runs themselves and
 * kept with the settings — so they do not depend on a screen having watched, on
 * the process having survived, or on when anyone noticed. (How two of them are
 * reconciled: ScanOutcomesTest.)
 */
class ScanRefusalPrefsTest {

    private val refusal = ScanRefusal(code = "scan_blocked", accountKey = "a@example.com", order = 4)
    private val done = ScanDone(accountKey = "a@example.com", order = 3)

    @Test
    fun `a refusal is read back as written`() {
        val prefs = mutablePreferencesOf()

        ScanRefusalPrefs.write(prefs, refusal)

        assertEquals(refusal, ScanRefusalPrefs.read(prefs))
    }

    @Test
    fun `nothing stored, or a record with a part missing, reads as no refusal`() {
        assertNull(ScanRefusalPrefs.read(mutablePreferencesOf()))

        val partial = mutablePreferencesOf(ScanRefusalPrefs.code to "scan_blocked")
        assertNull(ScanRefusalPrefs.read(partial))
    }

    @Test
    fun `clearing leaves nothing of the refusal behind`() {
        val prefs = mutablePreferencesOf()
        ScanRefusalPrefs.write(prefs, refusal)

        ScanRefusalPrefs.clear(prefs)

        assertNull(ScanRefusalPrefs.read(prefs))
        assertEquals(emptyMap<Any, Any>(), prefs.asMap())
    }

    @Test
    fun `the latest scan is read back as written, and cleared without a trace`() {
        val prefs = mutablePreferencesOf()

        ScanDonePrefs.write(prefs, done)
        assertEquals(done, ScanDonePrefs.read(prefs))

        ScanDonePrefs.clear(prefs)
        assertNull(ScanDonePrefs.read(prefs))
        assertEquals(emptyMap<Any, Any>(), prefs.asMap())
    }

    @Test
    fun `a refusal goes with the session it was made in`() {
        fun signedInWithRefusal() = mutablePreferencesOf().also {
            SessionPrefs.signIn(it, email = "a@example.com", encryptedCookie = "keystore:v1:a")
            ScanRefusalPrefs.write(it, refusal)
        }

        // Signed out on purpose.
        val signedOut = signedInWithRefusal().also { SessionPrefs.signOut(it) }
        assertNull(ScanRefusalPrefs.read(signedOut))

        // The session expired: the request was the signed-in user's, and that
        // user is gone until they sign in again and ask anew.
        val expired = signedInWithRefusal().also { SessionPrefs.expire(it) }
        assertNull(ScanRefusalPrefs.read(expired))

        // Someone else signs in over a leftover record.
        val other = mutablePreferencesOf().also {
            ScanRefusalPrefs.write(it, refusal)
            SessionPrefs.signIn(it, email = "b@example.com", encryptedCookie = "keystore:v1:b")
        }
        assertNull(ScanRefusalPrefs.read(other))
    }

    @Test
    fun `the record of the latest scan follows the summary it speaks about`() {
        fun signedInWithDone() = mutablePreferencesOf().also {
            SessionPrefs.signIn(it, email = "a@example.com", encryptedCookie = "keystore:v1:a")
            ScanDonePrefs.write(it, done)
        }

        // Wherever the summary is removed (a sign-out does that), it goes too.
        val cleared = signedInWithDone().also { LastScanPrefs.clear(it) }
        assertNull(ScanDonePrefs.read(cleared))

        // An expired session keeps its summary for the account's return, and
        // with it the word on whether that summary is the latest outcome.
        val expired = signedInWithDone().also { SessionPrefs.expire(it) }
        assertEquals(done, ScanDonePrefs.read(expired))
        val back = expired.also {
            SessionPrefs.signIn(it, email = "a@example.com", encryptedCookie = "keystore:v1:a2")
        }
        assertEquals(done, ScanDonePrefs.read(back))

        // Anyone else signing in gets neither.
        val other = signedInWithDone().also {
            SessionPrefs.expire(it)
            SessionPrefs.signIn(it, email = "b@example.com", encryptedCookie = "keystore:v1:b")
        }
        assertNull(ScanDonePrefs.read(other))
    }
}
