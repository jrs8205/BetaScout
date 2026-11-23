package org.jarsi.betascout.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.data.settings.ReloginNoticeState
import org.jarsi.betascout.data.settings.SessionStanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "sign in again" notification is owed by a background run that found the
 * session dead, and taken down by a sign-in, and the two can cross. Whatever
 * order they arrive in, the notice has to end up as the stored state says — and
 * a state that cannot be read says nothing. What is owed is in the store, so
 * it is still owed when the run that left it there is long gone; and it is owed
 * for one sign-in, so settling it late cannot settle the next one's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReloginNoticeTest {

    /** What the store says; null = it cannot be read. */
    private var stored: ReloginNoticeState? =
        ReloginNoticeState(SessionStanding.SIGNED_OUT, owed = false, generation = 0)

    /** Whether the store can be written. */
    private var writable = true

    /** What was done to the notification, in order. */
    private val done = mutableListOf<String>()

    /** The sign-ins the store was asked to note a shown notice for, in order. */
    private val notes = mutableListOf<Long>()

    private fun expired(owed: Boolean, generation: Long = 1) =
        ReloginNoticeState(SessionStanding.EXPIRED, owed, generation)

    private fun signedIn(generation: Long = 2) =
        ReloginNoticeState(SessionStanding.SIGNED_IN, owed = false, generation = generation)

    /** The store's own rule (SessionPrefs.noticeShown): the note is taken only
     *  for the expiry of the sign-in it is made for. */
    private fun note(generation: Long): Boolean {
        notes += generation
        val now = stored
        val taken = writable && now != null &&
            now.standing == SessionStanding.EXPIRED && now.generation == generation
        if (taken) stored = now!!.copy(owed = false)
        return taken
    }

    private var visibleGeneration: Long? = null

    private fun notice(state: suspend () -> ReloginNoticeState? = { stored }) = ReloginNotice(
        state = state,
        noteShown = ::note,
        show = {
            visibleGeneration = it
            done += "show"
        },
        withdraw = {
            visibleGeneration = null
            done += "withdraw"
        },
        syncLegacySignedOutNotice = { done += "check legacy" },
        activeGeneration = { visibleGeneration },
    )

    @Test
    fun `a background run that expired the session gets its notice`() = runTest {
        stored = expired(owed = true)

        val settled = notice().sync()

        assertTrue(settled)
        assertEquals(listOf("show"), done)
        assertEquals(expired(owed = false), stored)
    }

    @Test
    fun `a notice that is shown is not shown a second time`() = runTest {
        // The user may have swiped it away. Every later look at the state —
        // the next start of the process, say — finds nothing owed.
        val notice = notice()
        stored = expired(owed = true)
        notice.sync()

        notice.sync()
        notice().sync()

        assertEquals(listOf("show"), done)
    }

    @Test
    fun `an expiry that owes no notice gets none`() = runTest {
        // Found by a run the user asked for and is looking at, or by a single
        // app's re-check: the screens say it.
        stored = expired(owed = false)

        val settled = notice().sync()

        assertTrue(settled)
        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun `a notice the run could not get shown is shown at the next look, without another scan`() = runTest {
        // The run stored the expiry, and then the store would not be read: all
        // its attempts came back empty-handed and it ended. Nothing scans for a
        // session that is gone. Whoever looks next — the application's watch,
        // or the next start of the process — must still find the notice owed.
        val run = notice()
        stored = null
        val attempts = List(3) { run.sync() }
        stored = expired(owed = true)

        val settled = notice().sync()

        assertEquals(listOf(false, false, false), attempts)
        assertTrue(settled)
        assertEquals(listOf("show"), done)
    }

    @Test
    fun `signing in takes the notice down`() = runTest {
        val notice = notice()
        stored = expired(owed = true)
        notice.sync()

        stored = signedIn()
        val read = notice.sync()

        assertTrue(read)
        assertEquals(listOf("show", "withdraw"), done)
    }

    @Test
    fun `a run that gets round to its notice after the user signed in again shows nothing`() = runTest {
        // The run expired the session; the user signed in at once and the
        // notice (not there yet) was taken down; only then does the run get as
        // far as asking for it. No change of state will follow to take it down.
        val notice = notice()
        stored = signedIn()
        notice.sync()

        notice.sync()

        assertEquals(listOf("withdraw", "withdraw"), done)
    }

    @Test
    fun `a sign-in that crosses the showing still ends with the notice down`() = runTest {
        // One caller has read "expired, owed" and is about to show. The sign-in
        // lands in exactly that gap. Its withdrawal must come after the show,
        // not before it.
        val hasRead = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        var reads = 0
        val notice = notice(
            state = {
                if (reads++ == 0) {
                    hasRead.complete(Unit)
                    goOn.await()
                    expired(owed = true)
                } else {
                    signedIn()
                }
            },
        )

        val showing = launch { notice.sync() }
        hasRead.await()
        val withdrawing = launch { notice.sync() }
        runCurrent()
        goOn.complete(Unit)
        showing.join()
        withdrawing.join()

        assertEquals(listOf("show", "withdraw"), done)
    }

    @Test
    fun `a state that cannot be read takes nothing down, shows nothing, and is reported`() = runTest {
        // The session expired and the notice is up. Then the store fails to
        // read. That is not a sign-in: the notice stays, and the caller learns
        // that it has to ask again.
        val notice = notice()
        stored = expired(owed = true)
        notice.sync()

        stored = null
        val read = notice.sync()

        assertFalse(read)
        assertEquals(listOf("show"), done)
    }

    @Test
    fun `a notice that could not be noted as shown is not posted again and again`() = runTest {
        // The store reads but will not be written. The caller is told to come
        // back, and comes back every couple of seconds: it must find the note
        // still to be made, not a notice to post each time.
        val notice = notice()
        stored = expired(owed = true)
        writable = false

        val first = notice.sync()
        val second = notice.sync()
        writable = true
        val third = notice.sync()

        assertEquals(listOf(false, false, true), listOf(first, second, third))
        assertEquals(listOf("show"), done)
        assertEquals(listOf(1L, 1L, 1L), notes)
        assertEquals(expired(owed = false), stored)
    }

    @Test
    fun `a fresh synchronizer acknowledges a visible notice without posting it again`() = runTest {
        stored = expired(owed = true)
        writable = false
        assertFalse(notice().sync())

        writable = true
        assertTrue(notice().sync())

        assertEquals(listOf("show"), done)
        assertEquals(expired(owed = false), stored)
    }

    @Test
    fun `a new expiry after a sign-in is owed its own notice`() = runTest {
        val notice = notice()
        stored = expired(owed = true)
        notice.sync()
        stored = signedIn()
        notice.sync()

        stored = expired(owed = true, generation = 2)
        notice.sync()

        assertEquals(listOf("show", "withdraw", "show"), done)
    }

    // A notice belongs to one sign-in.

    @Test
    fun `sign-out withdraws a notice just posted for an imported live session`() = runTest {
        val notice = notice()
        stored = expired(owed = true, generation = 0)
        assertTrue(notice.sync())
        stored = ReloginNoticeState(SessionStanding.SIGNED_OUT, owed = false, generation = 0)
        assertTrue(notice.sync())
        assertEquals(listOf("show", "withdraw"), done)
    }

    @Test
    fun `a fresh synchronizer removes a visible notice owned by a previous sign-in`() = runTest {
        stored = expired(owed = true, generation = 1)
        assertTrue(notice().sync())
        stored = expired(owed = false, generation = 2)
        assertTrue(notice().sync())
        assertEquals(listOf("show", "withdraw"), done)
    }

    @Test
    fun `a later expiry told on screen withdraws the earlier unacknowledged notice`() = runTest {
        val notice = notice()
        stored = expired(owed = true, generation = 1)
        writable = false
        assertFalse(notice.sync())

        // The user signed in from the app, leaving the first notice in the shade.
        // Its pending acknowledgement never saw the intervening signed-in state.
        stored = expired(owed = false, generation = 2)
        assertTrue(notice.sync())

        assertEquals(listOf("show", "withdraw"), done)
        assertEquals(listOf(1L), notes)
    }

    @Test
    fun `a note still to be made does not settle the notice of a later sign-in`() = runTest {
        // The first notice was shown and its note could not be written. The
        // user opened that notice — which takes it off the screen — and signed
        // in; that session expired in the background in turn, and nobody came
        // by here while it was signed in. What is owed now is the second
        // sign-in's notice: it has to be shown, not written off with the note
        // that was still pending for the first.
        val notice = notice()
        stored = expired(owed = true, generation = 1)
        writable = false
        notice.sync()

        stored = expired(owed = true, generation = 2)
        writable = true
        val settled = notice.sync()

        assertTrue(settled)
        assertEquals(listOf("show", "show"), done)
        assertEquals(listOf(1L, 2L), notes)
        assertEquals(expired(owed = false, generation = 2), stored)
    }

    @Test
    fun `a session that expired again before the note was made keeps its own notice owed`() = runTest {
        // Inside one visit: the state read says "sign-in 1 expired, owed", the
        // notice is shown, and by the time the note reaches the store the
        // store is about sign-in 2. Only the store can tell; it must not take
        // the note, and the caller is sent round again.
        var reads = 0
        val notice = notice(
            state = {
                if (reads++ == 0) expired(owed = true, generation = 1) else stored
            },
        )
        stored = expired(owed = true, generation = 2)

        val first = notice.sync()
        val second = notice.sync()

        assertEquals(listOf(false, true), listOf(first, second))
        assertEquals(listOf("show", "show"), done)
        assertEquals(listOf(1L, 2L), notes)
        assertEquals(expired(owed = false, generation = 2), stored)
    }
}
