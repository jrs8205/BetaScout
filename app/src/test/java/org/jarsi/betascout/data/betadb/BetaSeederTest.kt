package org.jarsi.betascout.data.betadb

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.BetaProgramEntity
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.KnownBetaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBetaProgramDao : BetaProgramDao {
    val state = linkedMapOf<String, BetaProgramEntity>()
    var writes = 0

    /** Makes the next write fail like a Room/SQLite error would. */
    var failNextWrite = false

    private fun write() {
        writes++
        if (failNextWrite) {
            failNextWrite = false
            throw IOException("database is locked")
        }
    }

    override fun observeAll(): Flow<List<BetaProgramEntity>> = MutableStateFlow(emptyList())
    override suspend fun getAll(): List<BetaProgramEntity> = state.values.toList()
    override suspend fun insertIgnoring(programs: List<BetaProgramEntity>) {
        write()
        programs.forEach { state.putIfAbsent(it.packageName, it) }
    }
    override suspend fun upsertAll(programs: List<BetaProgramEntity>) {
        write()
        programs.forEach { state[it.packageName] = it }
    }
    override suspend fun upsert(program: BetaProgramEntity) {
        write()
        state[program.packageName] = program
    }
    override suspend fun getAllPackageNames(): List<String> = state.keys.toList()
    override suspend fun deleteIn(packageNames: List<String>) {
        write()
        state.keys.removeAll(packageNames.toSet())
    }
    override suspend fun count(): Int = state.size
}

private fun snapshot(json: String, source: BetaSource = BetaSource.REMOTE) = CatalogSnapshot(
    programs = BetaSeedParser.parse(json, source),
    source = source,
    fingerprint = CatalogFingerprint.of(json),
)

private class SeedHarness(val dao: FakeBetaProgramDao = FakeBetaProgramDao()) {
    val applied = mutableListOf<CatalogSnapshot>()

    suspend fun seedWith(snapshot: CatalogSnapshot?) =
        BetaSeeder(readCatalog = { snapshot }, markApplied = { applied += it }, dao = dao).seed()
}

class BetaSeederTest {

    @Test
    fun `seeds parsed programs into dao with the catalog's provenance`() = runTest {
        val harness = SeedHarness()
        val seedJson = """
            {"programs":[
              {"packageName":"com.whatsapp","appName":"WhatsApp Messenger","knownStatus":"OFTEN_FULL"},
              {"packageName":"com.android.chrome","appName":"Google Chrome","knownStatus":"OFTEN_OPEN"}
            ]}
        """.trimIndent()

        harness.seedWith(snapshot(seedJson, BetaSource.BUNDLED))

        val seeded = harness.dao.state.values.toList()
        assertEquals(2, seeded.size)
        assertEquals("com.whatsapp", seeded[0].packageName)
        assertEquals(KnownBetaStatus.OFTEN_FULL, seeded[0].knownStatus)
        assertEquals(BetaSource.BUNDLED, seeded[0].source)
        assertEquals("com.android.chrome", seeded[1].packageName)
    }

    @Test
    fun `a downloaded catalog removes programs it no longer contains`() = runTest {
        val harness = SeedHarness()
        harness.seedWith(
            snapshot(
                """{"programs":[
                    {"packageName":"com.kept","appName":"Kept"},
                    {"packageName":"com.removed","appName":"Removed"}
                ]}""",
            ),
        )

        harness.seedWith(snapshot("""{"programs":[{"packageName":"com.kept","appName":"Kept"}]}"""))

        assertEquals(listOf("com.kept"), harness.dao.state.keys.toList())
    }

    @Test
    fun `an empty catalog is ignored instead of wiping the seeded programs`() = runTest {
        val harness = SeedHarness()
        harness.seedWith(snapshot("""{"programs":[{"packageName":"com.kept","appName":"Kept"}]}"""))

        harness.seedWith(snapshot("""{"programs":[]}"""))

        assertEquals(listOf("com.kept"), harness.dao.state.keys.toList())
    }

    @Test
    fun `the bundled seed only fills gaps and never deletes downloaded programs`() = runTest {
        // Offline start with a truncated cache file: the provider falls back to the
        // tiny bundled seed. Mirroring it would delete every program a previous
        // download stored and make all "Beta available" badges vanish.
        val harness = SeedHarness()
        harness.seedWith(
            snapshot(
                """{"programs":[
                    {"packageName":"com.downloaded","appName":"Downloaded"},
                    {"packageName":"com.shared","appName":"Shared (remote)"}
                ]}""",
            ),
        )

        harness.seedWith(
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
            harness.dao.state.keys.toList(),
        )
        assertEquals("Shared (remote)", harness.dao.state.getValue("com.shared").appName)
    }

    @Test
    fun `nothing new from the provider leaves the table untouched`() = runTest {
        val harness = SeedHarness()
        harness.seedWith(snapshot("""{"programs":[{"packageName":"com.kept","appName":"Kept"}]}"""))
        val writesAfterSeed = harness.dao.writes

        harness.seedWith(null)

        assertEquals(writesAfterSeed, harness.dao.writes)
    }

    @Test
    fun `a catalog is confirmed as applied only after the database write succeeded`() = runTest {
        // Confirming before the write would make the provider withhold the same
        // catalog from the retry, leaving the table empty or stale until the
        // process restarts or the catalog changes.
        val harness = SeedHarness()
        val catalog = snapshot("""{"programs":[{"packageName":"com.a","appName":"A"}]}""")
        harness.dao.failNextWrite = true

        val failure = runCatching { harness.seedWith(catalog) }.exceptionOrNull()

        assertTrue("expected the write failure to propagate, was $failure", failure is IOException)
        assertTrue(harness.applied.isEmpty())

        harness.seedWith(catalog)

        assertEquals(listOf(catalog), harness.applied)
        assertEquals(listOf("com.a"), harness.dao.state.keys.toList())
    }

    @Test
    fun `a bundled seed that was applied is confirmed too`() = runTest {
        val harness = SeedHarness()
        val catalog = snapshot("""{"programs":[{"packageName":"com.a","appName":"A"}]}""", BetaSource.BUNDLED)

        harness.seedWith(catalog)

        assertEquals(listOf(catalog), harness.applied)
    }
}
