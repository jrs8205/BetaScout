package org.jarsi.betascout.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The application watches where the session stands for as long as the process
 * lives, to keep the sign-in notice in step with it. One bad read of the store
 * must neither end that watch nor be taken for a sign-out.
 */
class SessionStandingFlowTest {

    private val signedIn: Preferences = mutablePreferencesOf().also {
        SessionPrefs.signIn(it, email = "user@example.com", encryptedCookie = "keystore:v1:a")
    }
    private val expired: Preferences = signedIn.toMutablePreferences().also { SessionPrefs.expire(it) }

    @Test
    fun `a read that fails is tried again, and the watch goes on`() = runTest {
        // The store as the watcher meets it: the first read fails; started
        // again, it delivers the state and then a change.
        var reads = 0
        val store = flow {
            if (reads++ == 0) throw IOException("disk")
            emit(signedIn)
            emit(expired)
        }
        val pauses = mutableListOf<Long>()

        val seen = store.reloginNoticeStates(pause = { pauses += it }).toList()

        // No "signed out" in between: a failed read says nothing about the
        // session, and whoever acts on this would take a notice down for it.
        assertEquals(listOf(SessionStanding.SIGNED_IN, SessionStanding.EXPIRED), seen.map { it.standing })
        assertEquals(listOf(0L), pauses)
    }

    @Test
    fun `failures in a row are counted for the pause, and a good read starts the count again`() = runTest {
        var reads = 0
        val store = flow {
            when (reads++) {
                0, 1 -> throw IOException("disk")
                2 -> {
                    emit(signedIn)
                    throw IOException("disk again")
                }
                else -> emit(expired)
            }
        }
        val pauses = mutableListOf<Long>()

        val seen = store.reloginNoticeStates(pause = { pauses += it }).toList()

        assertEquals(listOf(SessionStanding.SIGNED_IN, SessionStanding.EXPIRED), seen.map { it.standing })
        assertEquals(listOf(0L, 1L, 0L), pauses)
    }

    @Test
    fun `two observed expiries of different sign-ins both wake the notice watcher`() = runTest {
        val next = expired.toMutablePreferences().also {
            SessionPrefs.signIn(it, "user@example.com", "keystore:v1:b")
            SessionPrefs.expire(it)
        }
        val store = flow {
            emit(expired)
            emit(next)
            emit(next)
        }
        assertEquals(listOf(1L, 2L), store.reloginNoticeStates { }.toList().map { it.generation })
    }

    @Test
    fun `any other failure is not swallowed`() = runTest {
        val store = flow<Preferences> { throw IllegalStateException("not a read error") }

        val failure = runCatching { store.reloginNoticeStates(pause = {}).toList() }.exceptionOrNull()

        assertTrue("was $failure", failure is IllegalStateException)
    }
}
