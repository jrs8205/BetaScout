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
    var writes = 0

    override fun observeAll(): Flow<List<BetaProgramEntity>> = MutableStateFlow(emptyList())
    override suspend fun getAll(): List<BetaProgramEntity> = state.values.toList()
    override suspend fun insertIgnoring(programs: List<BetaProgramEntity>) {
        writes++
        programs.forEach { state.putIfAbsent(it.packageName, it) }
    }
    override suspend fun upsertAll(programs: List<BetaProgramEntity>) {
        writes++
        programs.forEach { state[it.packageName] = it }
    }
    override suspend fun upsert(program: BetaProgramEntity) {
        writes++
        state[program.packageName] = program
    }
    override suspend fun getAllPackageNames(): List<String> = state.keys.toList()
    override suspend fun deleteIn(packageNames: List<String>) {
        writes++
        state.keys.removeAll(packageNames.toSet())
    }
    override suspend fun count(): Int = state.size
}

private fun snapshot(json: String, source: BetaSource = BetaSource.REMOTE) =
    CatalogSnapshot(programs = BetaSeedParser.parse(json, source), source = source)

private suspend fun FakeBetaProgramDao.seedWith(snapshot: CatalogSnapshot?) =
    BetaSeeder(readCatalog = { snapshot }, dao = this).seed()

class BetaSeederTest {

    @Test
    fun `seeds parsed programs into dao with the catalog's provenance`() = runTest {
        val dao = FakeBetaProgramDao()
        val seedJson = """
            {"programs":[
              {"packageName":"com.whatsapp","appName":"WhatsApp Messenger","knownStatus":"OFTEN_FULL"},
              {"packageName":"com.android.chrome","appName":"Google Chrome","knownStatus":"OFTEN_OPEN"}
            ]}
        """.trimIndent()

        dao.seedWith(snapshot(seedJson, BetaSource.BUNDLED))

        val seeded = dao.state.values.toList()
        assertEquals(2, seeded.size)
        assertEquals("com.whatsapp", seeded[0].packageName)
        assertEquals(KnownBetaStatus.OFTEN_FULL, seeded[0].knownStatus)
        assertEquals(BetaSource.BUNDLED, seeded[0].source)
        assertEquals("com.android.chrome", seeded[1].packageName)
    }

    @Test
    fun `a downloaded catalog removes programs it no longer contains`() = runTest {
        val dao = FakeBetaProgramDao()
        dao.seedWith(
            snapshot(
                """{"programs":[
                    {"packageName":"com.kept","appName":"Kept"},
                    {"packageName":"com.removed","appName":"Removed"}
                ]}""",
            ),
        )

        dao.seedWith(snapshot("""{"programs":[{"packageName":"com.kept","appName":"Kept"}]}"""))

        assertEquals(listOf("com.kept"), dao.state.keys.toList())
    }

    @Test
    fun `an empty catalog is ignored instead of wiping the seeded programs`() = runTest {
        val dao = FakeBetaProgramDao()
        dao.seedWith(snapshot("""{"programs":[{"packageName":"com.kept","appName":"Kept"}]}"""))

        dao.seedWith(snapshot("""{"programs":[]}"""))

        assertEquals(listOf("com.kept"), dao.state.keys.toList())
    }

    @Test
    fun `the bundled seed only fills gaps and never deletes downloaded programs`() = runTest {
        // Offline start with a truncated cache file: the provider falls back to the
        // tiny bundled seed. Mirroring it would delete every program a previous
        // download stored and make all "Beta available" badges vanish.
        val dao = FakeBetaProgramDao()
        dao.seedWith(
            snapshot(
                """{"programs":[
                    {"packageName":"com.downloaded","appName":"Downloaded"},
                    {"packageName":"com.shared","appName":"Shared (remote)"}
                ]}""",
            ),
        )

        dao.seedWith(
            snapshot(
                """{"programs":[
                    {"packageName":"com.shared","appName":"Shared (bundled)"},
                    {"packageName":"com.bundled.only","appName":"Bundled"}
                ]}""",
                BetaSource.BUNDLED,
            ),
        )

        assertEquals(
            listOf("com.downloaded", "com.shared", "com.bundled.only"),
            dao.state.keys.toList(),
        )
        assertEquals("Shared (remote)", dao.state.getValue("com.shared").appName)
    }

    @Test
    fun `nothing new from the provider leaves the table untouched`() = runTest {
        val dao = FakeBetaProgramDao()
        dao.seedWith(snapshot("""{"programs":[{"packageName":"com.kept","appName":"Kept"}]}"""))
        val writesAfterSeed = dao.writes

        dao.seedWith(null)

        assertEquals(writesAfterSeed, dao.writes)
    }
}
