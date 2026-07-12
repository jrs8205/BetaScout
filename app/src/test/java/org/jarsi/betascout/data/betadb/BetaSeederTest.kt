package org.jarsi.betascout.data.betadb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.BetaProgramEntity
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.KnownBetaStatus
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeBetaProgramDao : BetaProgramDao {
    val state = linkedMapOf<String, BetaProgramEntity>()

    override fun observeAll(): Flow<List<BetaProgramEntity>> = MutableStateFlow(emptyList())
    override suspend fun getAll(): List<BetaProgramEntity> = state.values.toList()
    override suspend fun insertIgnoring(programs: List<BetaProgramEntity>) {
        programs.forEach { state.putIfAbsent(it.packageName, it) }
    }
    override suspend fun upsertAll(programs: List<BetaProgramEntity>) {
        programs.forEach { state[it.packageName] = it }
    }
    override suspend fun upsert(program: BetaProgramEntity) {
        state[program.packageName] = program
    }
    override suspend fun deleteNotIn(keep: List<String>) {
        state.keys.retainAll(keep.toSet())
    }
    override suspend fun count(): Int = state.size
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

        val seeded = dao.state.values.toList()
        assertEquals(2, seeded.size)
        assertEquals("com.whatsapp", seeded[0].packageName)
        assertEquals(KnownBetaStatus.OFTEN_FULL, seeded[0].knownStatus)
        assertEquals(BetaSource.BUNDLED, seeded[0].source)
        assertEquals("com.android.chrome", seeded[1].packageName)
    }

    @Test
    fun `seed removes programs the catalog no longer contains`() = runTest {
        val dao = FakeBetaProgramDao()
        BetaSeeder(
            readSeedJson = {
                """{"programs":[
                    {"packageName":"com.kept","appName":"Kept"},
                    {"packageName":"com.removed","appName":"Removed"}
                ]}"""
            },
            dao = dao,
        ).seed()

        BetaSeeder(
            readSeedJson = { """{"programs":[{"packageName":"com.kept","appName":"Kept"}]}""" },
            dao = dao,
        ).seed()

        assertEquals(listOf("com.kept"), dao.state.keys.toList())
    }

    @Test
    fun `an empty catalog is ignored instead of wiping the seeded programs`() = runTest {
        val dao = FakeBetaProgramDao()
        BetaSeeder(
            readSeedJson = { """{"programs":[{"packageName":"com.kept","appName":"Kept"}]}""" },
            dao = dao,
        ).seed()

        BetaSeeder(readSeedJson = { """{"programs":[]}""" }, dao = dao).seed()

        assertEquals(listOf("com.kept"), dao.state.keys.toList())
    }
}
