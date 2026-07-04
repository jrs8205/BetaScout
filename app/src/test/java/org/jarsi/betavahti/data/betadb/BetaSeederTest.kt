package org.jarsi.betavahti.data.betadb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jarsi.betavahti.data.db.BetaProgramDao
import org.jarsi.betavahti.data.db.BetaProgramEntity
import org.jarsi.betavahti.domain.BetaSource
import org.jarsi.betavahti.domain.KnownBetaStatus
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeBetaProgramDao : BetaProgramDao {
    val inserted = mutableListOf<BetaProgramEntity>()

    override fun observeAll(): Flow<List<BetaProgramEntity>> = MutableStateFlow(emptyList())
    override suspend fun insertIgnoring(programs: List<BetaProgramEntity>) {
        inserted += programs
    }
    override suspend fun upsert(program: BetaProgramEntity) = throw UnsupportedOperationException()
    override suspend fun count(): Int = inserted.size
}

class BetaSeederTest {

    @Test
    fun `seeds parsed programs into dao as bundled entities`() = runTest {
        val dao = FakeBetaProgramDao()
        val seedJson = """
            {"programs":[
              {"packageName":"com.whatsapp","appName":"WhatsApp Messenger","knownStatus":"OFTEN_FULL"},
              {"packageName":"com.android.chrome","appName":"Google Chrome","knownStatus":"OFTEN_OPEN"}
            ]}
        """.trimIndent()

        BetaSeeder(readSeedJson = { seedJson }, dao = dao).seed()

        assertEquals(2, dao.inserted.size)
        assertEquals("com.whatsapp", dao.inserted[0].packageName)
        assertEquals(KnownBetaStatus.OFTEN_FULL, dao.inserted[0].knownStatus)
        assertEquals(BetaSource.BUNDLED, dao.inserted[0].source)
        assertEquals("com.android.chrome", dao.inserted[1].packageName)
    }
}
