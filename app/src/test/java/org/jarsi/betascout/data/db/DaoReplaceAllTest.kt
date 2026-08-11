package org.jarsi.betascout.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.KnownBetaStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** SQLite before 3.32 (Android 8-11) rejects statements with more than 999 host
 *  parameters; the fakes enforce the limit on every list-bound query so replaceAll
 *  cannot regress to a single unbounded statement. */
private const val SQLITE_MAX_VARIABLES = 999

private fun checkVariableLimit(count: Int) {
    check(count <= SQLITE_MAX_VARIABLES) { "too many SQL variables: $count" }
}

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
    override suspend fun getAllPackageNames(): List<String> = state.keys.toList()
    override suspend fun deleteIn(packageNames: List<String>) {
        checkVariableLimit(packageNames.size)
        state.keys.removeAll(packageNames.toSet())
    }
    override suspend fun count(): Int = state.size
}

private class FakeInstalledAppDao : InstalledAppDao {
    val state = linkedMapOf<String, InstalledAppEntity>()

    override fun observeAll(): Flow<List<InstalledAppEntity>> = MutableStateFlow(emptyList())
    override suspend fun getAll(): List<InstalledAppEntity> = state.values.toList()
    override suspend fun upsertAll(apps: List<InstalledAppEntity>) {
        apps.forEach { state[it.packageName] = it }
    }
    override suspend fun getAllPackageNames(): List<String> = state.keys.toList()
    override suspend fun deleteIn(packageNames: List<String>) {
        checkVariableLimit(packageNames.size)
        state.keys.removeAll(packageNames.toSet())
    }
}

private fun program(packageName: String) = BetaProgramEntity(
    packageName = packageName,
    appName = packageName,
    testingUrl = null,
    knownStatus = KnownBetaStatus.UNKNOWN,
    notes = null,
    source = BetaSource.REMOTE,
)

private fun installedApp(packageName: String) = InstalledAppEntity(
    packageName = packageName,
    label = packageName,
    versionName = null,
    versionCode = 1L,
    installerPackage = null,
    isSystem = false,
    hasLauncher = true,
    lastScanned = 0L,
)

class DaoReplaceAllTest {

    @Test
    fun `beta program replaceAll mirrors a catalog larger than the sqlite variable limit`() = runTest {
        val dao = FakeBetaProgramDao()
        dao.upsertAll((1..1200).map { program("com.old.app$it") })

        val catalog = (1..1310).map { program("com.new.app$it") }
        dao.replaceAll(catalog)

        assertEquals(catalog.map { it.packageName }.toSet(), dao.state.keys.toSet())
    }

    @Test
    fun `installed app replaceAll mirrors a scan larger than the sqlite variable limit`() = runTest {
        val dao = FakeInstalledAppDao()
        dao.upsertAll((1..1200).map { installedApp("com.old.app$it") })

        val scanned = (1..1310).map { installedApp("com.new.app$it") }
        dao.replaceAll(scanned)

        assertEquals(scanned.map { it.packageName }.toSet(), dao.state.keys.toSet())
    }

    @Test
    fun `beta program replaceAll removes missing rows and updates the rest`() = runTest {
        val dao = FakeBetaProgramDao()
        dao.upsertAll(listOf(program("com.kept"), program("com.removed")))

        val updated = program("com.kept").copy(appName = "Kept App")
        dao.replaceAll(listOf(updated))

        assertEquals(listOf("com.kept"), dao.state.keys.toList())
        assertEquals("Kept App", dao.state["com.kept"]?.appName)
    }
}
