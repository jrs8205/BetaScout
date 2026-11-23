package org.jarsi.betascout.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderEditTest {
    @Test
    fun aPendingNoteAndReminderBothReachRoom() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.userBetaStatusDao()
            val pkg = "example.app"
            dao.edit(pkg) { it.copy(watching = true, userNote = "before") }
            val noteRead = CompletableDeferred<Unit>()
            val releaseNote = CountDownLatch(1)
            val note = async(Dispatchers.IO) {
                dao.edit(pkg) {
                    noteRead.complete(Unit)
                    assertTrue(releaseNote.await(10, TimeUnit.SECONDS))
                    it.copy(userNote = "after")
                }
            }
            withTimeout(10_000) { noteRead.await() }
            val reminder = async(start = CoroutineStart.UNDISPATCHED) { dao.markReminded(listOf(pkg), 123L) }
            releaseNote.countDown()
            note.await()
            reminder.await()
            val saved = checkNotNull(dao.get(pkg))
            assertEquals("after", saved.userNote)
            assertEquals(123L, saved.lastRemindedAt)
            assertTrue(saved.watching)
        } finally {
            db.close()
        }
    }
}
