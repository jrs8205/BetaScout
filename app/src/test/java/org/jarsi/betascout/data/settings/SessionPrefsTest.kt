package org.jarsi.betascout.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.jarsi.betascout.domain.PlaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPrefsTest {

    private fun signedIn() = mutablePreferencesOf().also {
        SessionPrefs.signIn(it, email = "user@example.com", encryptedCookie = "keystore:v1:iv:ct")
    }

    @Test
    fun `a fresh install is signed out but not expired`() {
        assertFalse(SessionPrefs.isExpired(mutablePreferencesOf()))
    }

    @Test
    fun `a signed-in session is not expired`() {
        val prefs = signedIn()

        assertEquals("keystore:v1:iv:ct", prefs[SessionPrefs.cookie])
        assertFalse(SessionPrefs.isExpired(prefs))
    }

    @Test
    fun `expiring a session removes it and leaves the reason behind`() {
        // The reason lives in the same store as the session, so it survives the
        // process: a run that finds the session dead while the app is closed
        // must still be explained on the next launch.
        val prefs = signedIn()

        SessionPrefs.expire(prefs)

        assertNull(prefs[SessionPrefs.cookie])
        assertNull(prefs[SessionPrefs.email])
        assertTrue(SessionPrefs.isExpired(prefs))
    }

    /** Stands in for the Keystore cipher: "keystore:v1:<plain>" decodes to <plain>. */
    private fun decode(stored: String): String? = stored.removePrefix("keystore:v1:")

    private fun signedInWith(cookie: String) = mutablePreferencesOf().also {
        SessionPrefs.signIn(it, email = "user@example.com", encryptedCookie = "keystore:v1:$cookie")
    }

    /** The session a run or a check would have read from [prefs]. */
    private fun sessionOf(prefs: Preferences, cookie: String) =
        PlaySession("user@example.com", cookie, SessionPrefs.generationOf(prefs))

    @Test
    fun `the session a check was made with is expired when it is still the stored one`() {
        val prefs = signedInWith("SID=abc")

        val expired = SessionPrefs.expireIfCurrent(prefs, sessionOf(prefs, "SID=abc"))

        assertTrue(expired)
        assertTrue(SessionPrefs.isExpired(prefs))
        assertNull(prefs[SessionPrefs.cookie])
    }

    @Test
    fun `a late rejection does not mark a deliberate sign-out as an expiry`() {
        // The check's answer arrives after the user signed out: without the
        // comparison they would be told "session expired" for good.
        val prefs = signedInWith("SID=abc")
        val checked = sessionOf(prefs, "SID=abc")
        SessionPrefs.signOut(prefs)

        val expired = SessionPrefs.expireIfCurrent(prefs, checked)

        assertFalse(expired)
        assertFalse(SessionPrefs.isExpired(prefs))
    }

    @Test
    fun `a late rejection of an old session leaves a newer one alone`() {
        // Signed in again (or as someone else) while the old session's check
        // was still on its way back.
        val prefs = signedInWith("SID=old")
        val checked = sessionOf(prefs, "SID=old")
        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:SID=new")

        val expired = SessionPrefs.expireIfCurrent(prefs, checked)

        assertFalse(expired)
        assertEquals("keystore:v1:SID=new", prefs[SessionPrefs.cookie])
        assertEquals("user@example.com", prefs[SessionPrefs.email])
        assertFalse(SessionPrefs.isExpired(prefs))
    }

    @Test
    fun `every sign-in is a new session, also of the same account with the same cookie`() {
        val prefs = signedInWith("SID=abc")
        val first = sessionOf(prefs, "SID=abc")
        assertTrue(SessionPrefs.holds(prefs, first))

        // Out and in again: the stored values are the very same as before.
        SessionPrefs.signOut(prefs)
        assertFalse(SessionPrefs.holds(prefs, first))
        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:SID=abc")

        assertFalse(SessionPrefs.holds(prefs, first))
        assertTrue(SessionPrefs.holds(prefs, sessionOf(prefs, "SID=abc")))
        // After an expiry too.
        val second = sessionOf(prefs, "SID=abc")
        SessionPrefs.expire(prefs)
        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:SID=abc")
        assertFalse(SessionPrefs.holds(prefs, second))
    }

    @Test
    fun `a session is recognised without its cookie being read`() {
        // What is stored here decodes to nothing anyone holds. The session is
        // known by the sign-in it came from; whether the Keystore answers when
        // a scan ends does not come into it.
        val prefs = signedInWith("whatever-is-stored")

        assertTrue(SessionPrefs.holds(prefs, sessionOf(prefs, "SID=abc")))
        assertTrue(SessionPrefs.expireIfCurrent(prefs, sessionOf(prefs, "SID=abc")))
        assertEquals(SessionStanding.EXPIRED, SessionPrefs.standing(prefs))
    }

    @Test
    fun `a session stored before sign-ins were counted is still its own`() {
        // An update from a version that kept no count: the stored session reads
        // as sign-in 0, and so does the session a run reads from it.
        val prefs = mutablePreferencesOf(
            SessionPrefs.email to "user@example.com",
            SessionPrefs.cookie to "keystore:v1:SID=abc",
        )
        val legacy = sessionOf(prefs, "SID=abc")

        assertEquals(0L, legacy.generation)
        assertTrue(SessionPrefs.holds(prefs, legacy))
        // The next sign-in is number one, and the old session is not it.
        SessionPrefs.expire(prefs)
        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:SID=abc")
        assertEquals(1L, SessionPrefs.generationOf(prefs))
        assertFalse(SessionPrefs.holds(prefs, legacy))
    }

    private fun lastScanOf(accountKey: String?) = LastScanInfo(
        at = 1_000L,
        checked = 0,
        joined = 0,
        notJoined = 0,
        failed = 5,
        failureReason = "timeout",
        accountKey = accountKey,
    )

    @Test
    fun `an expired session keeps its last scan for when the same account returns`() {
        val prefs = signedIn()
        LastScanPrefs.write(prefs, lastScanOf("user@example.com"))

        SessionPrefs.expire(prefs)
        SessionPrefs.signIn(prefs, email = "User@Example.com ", encryptedCookie = "keystore:v1:new")

        // Compared as the session itself keys accounts: trimmed, lower case.
        assertEquals(lastScanOf("user@example.com"), LastScanPrefs.read(prefs))
    }

    @Test
    fun `signing in as another account drops the previous account's last scan`() {
        // A's last scan failed for every app, A's session expired, B signs in.
        // B must never be shown A's failed checks — not on the main screen, not
        // in the settings card — whatever happens to B's own first scan.
        val prefs = signedIn()
        LastScanPrefs.write(prefs, lastScanOf("user@example.com"))
        SessionPrefs.expire(prefs)

        SessionPrefs.signIn(prefs, email = "other@example.com", encryptedCookie = "keystore:v1:b")

        assertNull(LastScanPrefs.read(prefs))
        assertEquals("other@example.com", prefs[SessionPrefs.email])
    }

    @Test
    fun `a last scan of unknown owner is dropped at the next sign-in`() {
        // Stored by an earlier version, which did not record whose it was.
        val prefs = signedIn()
        LastScanPrefs.write(prefs, lastScanOf(accountKey = null))
        SessionPrefs.expire(prefs)

        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:new")

        assertNull(LastScanPrefs.read(prefs))
    }

    @Test
    fun `signing in again settles the expiry`() {
        val prefs = signedIn()
        SessionPrefs.expire(prefs)

        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:iv2:ct2")

        assertFalse(SessionPrefs.isExpired(prefs))
        assertEquals("keystore:v1:iv2:ct2", prefs[SessionPrefs.cookie])
    }

    @Test
    fun `sign-out clears all scan records in the same edit as the session`() {
        val prefs = signedIn()
        val owner = "user@example.com"
        ScanOutcomes.recordDone(prefs, owner, 1, LastScanInfo(10, 1, 0, 1))
        ScanOutcomes.recordRefusal(prefs, ScanRefusal("scan_blocked", owner, 2))
        SessionPrefs.signOut(prefs)
        assertNull(LastScanPrefs.read(prefs))
        assertNull(ScanDonePrefs.read(prefs))
        assertNull(ScanRefusalPrefs.read(prefs))
        assertNull(prefs[SessionPrefs.cookie])
    }

    @Test
    fun `a deliberate sign-out is not an expiry, and clears an earlier one`() {
        val prefs = signedIn()
        SessionPrefs.signOut(prefs)
        assertFalse(SessionPrefs.isExpired(prefs))
        assertNull(prefs[SessionPrefs.cookie])

        // Expired, then the user signs out of the leftovers from settings: the
        // "session expired" prompt has nothing left to say.
        val expired = signedIn()
        SessionPrefs.expire(expired)
        SessionPrefs.signOut(expired)
        assertFalse(SessionPrefs.isExpired(expired))
    }

    // The "sign in again" notification that is owed.

    @Test
    fun `an expiry found in the background leaves a notice owed, next to the expiry itself`() {
        // In the same edit: the run may not get as far as posting it, and
        // nothing scans again for a session that is gone.
        val prefs = signedIn()

        SessionPrefs.expire(prefs, tellByNotification = true)

        // Owed for the sign-in that expired: the count stays with the expiry.
        assertEquals(
            ReloginNoticeState(SessionStanding.EXPIRED, owed = true, generation = 1),
            SessionPrefs.noticeState(prefs),
        )
    }

    @Test
    fun `an expiry the user is told on screen owes no notice`() {
        val prefs = signedIn()

        SessionPrefs.expire(prefs)

        assertEquals(
            ReloginNoticeState(SessionStanding.EXPIRED, owed = false, generation = 1),
            SessionPrefs.noticeState(prefs),
        )
    }

    @Test
    fun `a notice that was shown is no longer owed, and the session stays expired`() {
        val prefs = signedIn()
        SessionPrefs.expire(prefs, tellByNotification = true)

        val noted = SessionPrefs.noticeShown(prefs, generation = 1)

        assertTrue(noted)
        assertEquals(
            ReloginNoticeState(SessionStanding.EXPIRED, owed = false, generation = 1),
            SessionPrefs.noticeState(prefs),
        )
    }

    @Test
    fun `a note for an earlier sign-in's notice leaves a later one owed`() {
        // The first notice was shown; before that was noted the user signed in
        // again and that session expired in the background too. The late note
        // is about a notice that is history. What is owed now is another one.
        val prefs = signedIn()
        SessionPrefs.expire(prefs, tellByNotification = true)
        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:b")
        SessionPrefs.expire(prefs, tellByNotification = true)

        val noted = SessionPrefs.noticeShown(prefs, generation = 1)

        assertFalse(noted)
        assertEquals(
            ReloginNoticeState(SessionStanding.EXPIRED, owed = true, generation = 2),
            SessionPrefs.noticeState(prefs),
        )
        assertTrue(SessionPrefs.noticeShown(prefs, generation = 2))
        assertFalse(SessionPrefs.noticeState(prefs).owed)
    }

    @Test
    fun `a note that arrives after the user signed in again is not taken`() {
        val prefs = signedIn()
        SessionPrefs.expire(prefs, tellByNotification = true)
        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:b")

        assertFalse(SessionPrefs.noticeShown(prefs, generation = 1))
        assertFalse(SessionPrefs.noticeShown(prefs, generation = 2))
    }

    @Test
    fun `signing in or out settles a notice that was still owed`() {
        val signedInAgain = signedIn().also { SessionPrefs.expire(it, tellByNotification = true) }
        SessionPrefs.signIn(signedInAgain, email = "user@example.com", encryptedCookie = "keystore:v1:b")
        assertEquals(
            ReloginNoticeState(SessionStanding.SIGNED_IN, owed = false, generation = 2),
            SessionPrefs.noticeState(signedInAgain),
        )
        // Not owed again by the next expiry either, unless that one says so.
        SessionPrefs.expire(signedInAgain)
        assertFalse(SessionPrefs.noticeState(signedInAgain).owed)

        val signedOut = signedIn().also { SessionPrefs.expire(it, tellByNotification = true) }
        SessionPrefs.signOut(signedOut)
        assertEquals(
            ReloginNoticeState(SessionStanding.SIGNED_OUT, owed = false, generation = 1),
            SessionPrefs.noticeState(signedOut),
        )
    }

    @Test
    fun `a stored session always wins over a stale flag`() {
        // Defensive: whatever order writes landed in, a readable session means
        // signed in, never "expired".
        val prefs = signedIn()
        prefs[SessionPrefs.expired] = true

        assertFalse(SessionPrefs.isExpired(prefs))
    }

    @Test
    fun `where the session stands is one value, and a sign-in changes it in one step`() {
        val prefs = mutablePreferencesOf()
        assertEquals(SessionStanding.SIGNED_OUT, SessionPrefs.standing(prefs))

        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:a")
        assertEquals(SessionStanding.SIGNED_IN, SessionPrefs.standing(prefs))

        SessionPrefs.expire(prefs)
        assertEquals(SessionStanding.EXPIRED, SessionPrefs.standing(prefs))

        // Straight from expired to signed in: nothing in between for a
        // follower of this value to act on twice.
        SessionPrefs.signIn(prefs, email = "user@example.com", encryptedCookie = "keystore:v1:b")
        assertEquals(SessionStanding.SIGNED_IN, SessionPrefs.standing(prefs))

        SessionPrefs.signOut(prefs)
        assertEquals(SessionStanding.SIGNED_OUT, SessionPrefs.standing(prefs))
    }

    // What a run reads.

    @Test
    fun `a run reads the stored session, legacy plain text included, and which sign-in it is`() {
        val encrypted = signedInWith("SID=abc")
        val plain = mutablePreferencesOf().also {
            SessionPrefs.signIn(it, email = "user@example.com", encryptedCookie = "SID=legacy")
        }
        val isEncrypted = { stored: String -> stored.startsWith("keystore:v1:") }

        assertEquals(
            SessionRead.Present(PlaySession("user@example.com", "SID=abc", generation = 1)),
            SessionPrefs.read(encrypted, isEncrypted, ::decode),
        )
        assertEquals(
            SessionRead.Present(PlaySession("user@example.com", "SID=legacy", generation = 1)),
            SessionPrefs.read(plain, isEncrypted, ::decode),
        )
    }

    @Test
    fun `a session that cannot be read is not the same as nobody signed in`() {
        val isEncrypted = { stored: String -> stored.startsWith("keystore:v1:") }

        // Nothing stored: signed out, and a run has no one to scan for.
        assertEquals(
            SessionRead.None,
            SessionPrefs.read(mutablePreferencesOf(), isEncrypted, ::decode),
        )
        assertEquals(
            SessionRead.None,
            SessionPrefs.read(signedInWith("SID=abc").also { SessionPrefs.expire(it) }, isEncrypted, ::decode),
        )
        // Stored, but the Keystore did not give it up this time. The screens
        // may still show the user signed in; a run must not end as if nobody
        // had asked for it.
        assertEquals(
            SessionRead.Unreadable,
            SessionPrefs.read(signedInWith("SID=abc"), isEncrypted, decrypt = { null }),
        )
    }
}
