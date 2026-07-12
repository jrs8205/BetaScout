package org.jarsi.betascout.data.crowd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.data.db.BetaObservationDao
import org.jarsi.betascout.data.db.BetaObservationEntity
import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.BetaProgramEntity
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACCOUNT = "user@example.com"

private class FakeBetaObservationDao : BetaObservationDao {
    private data class Key(val accountKey: String, val packageName: String)

    private val state = MutableStateFlow<Map<Key, BetaObservationEntity>>(emptyMap())

    override fun observeAll(): Flow<List<BetaObservationEntity>> = state.map { it.values.toList() }

    override suspend fun getAll(): List<BetaObservationEntity> = state.value.values.toList()

    override suspend fun getAllForAccount(accountKey: String): List<BetaObservationEntity> =
        state.value.values.filter { it.accountKey == accountKey }

    override suspend fun get(accountKey: String, packageName: String): BetaObservationEntity? =
        state.value[Key(accountKey, packageName)]

    override suspend fun deleteForAccount(accountKey: String) {
        state.value = state.value.filterKeys { it.accountKey != accountKey }
    }

    override suspend fun upsert(observation: BetaObservationEntity) {
        state.value = state.value +
            (Key(observation.accountKey, observation.packageName) to observation)
    }
}

private class FakeBetaProgramDao : BetaProgramDao {
    val state = MutableStateFlow<Map<String, BetaProgramEntity>>(emptyMap())

    override fun observeAll(): Flow<List<BetaProgramEntity>> = state.map { it.values.toList() }

    override suspend fun getAll(): List<BetaProgramEntity> = state.value.values.toList()

    override suspend fun insertIgnoring(programs: List<BetaProgramEntity>) {
        state.value = programs.associateBy { it.packageName } + state.value
    }

    override suspend fun upsertAll(programs: List<BetaProgramEntity>) {
        state.value = state.value + programs.associateBy { it.packageName }
    }

    override suspend fun upsert(program: BetaProgramEntity) {
        state.value = state.value + (program.packageName to program)
    }

    override suspend fun deleteNotIn(keep: List<String>) {
        state.value = state.value.filterKeys { it in keep }
    }

    override suspend fun count(): Int = state.value.size
}

private fun observation(
    packageName: String,
    liveStatus: LiveBetaStatus,
    membership: ObservedMembership = ObservedMembership.NOT_JOINED,
) = BetaObservationEntity(
    accountKey = ACCOUNT,
    packageName = packageName,
    liveStatus = liveStatus,
    observedMembership = membership,
    checkedAt = 1_000L,
    lastError = null,
)

private fun program(packageName: String, source: BetaSource) = BetaProgramEntity(
    packageName = packageName,
    appName = packageName,
    testingUrl = null,
    knownStatus = KnownBetaStatus.UNKNOWN,
    notes = null,
    source = source,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryReporterTest {

    private val observationDao = FakeBetaObservationDao()
    private val programDao = FakeBetaProgramDao()
    private val reported = mutableSetOf<String>()
    private var shareEnabled = true
    private var postResult = true
    private val postedBatches = mutableListOf<List<String>>()

    private fun kotlinx.coroutines.test.TestScope.reporter() = DiscoveryReporter(
        shareEnabled = { shareEnabled },
        reportedPackages = { reported.toSet() },
        markReported = { reported += it },
        betaObservationDao = observationDao,
        betaProgramDao = programDao,
        post = { packages ->
            postedBatches += packages
            postResult
        },
        io = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `selection keeps program sightings and drops catalog and reported packages`() {
        val observations = listOf(
            observation("com.open", LiveBetaStatus.OPEN),
            observation("com.full", LiveBetaStatus.FULL),
            observation("com.closed", LiveBetaStatus.CLOSED),
            observation("com.none", LiveBetaStatus.NO_PROGRAM),
            observation("com.unknown", LiveBetaStatus.UNKNOWN),
            observation("com.catalog", LiveBetaStatus.OPEN),
            observation("com.reported", LiveBetaStatus.OPEN),
        )

        val selected = selectDiscoveries(
            observations,
            catalogPackages = setOf("com.catalog"),
            reported = setOf("com.reported"),
        )

        assertEquals(listOf("com.closed", "com.full", "com.open"), selected)
    }

    @Test
    fun `a joined observation contributes only through its live status`() = runTest {
        // Membership must never gate the upload: a JOINED row uploads because its
        // liveStatus proves a program page, exactly like a NOT_JOINED one.
        observationDao.upsert(
            observation("com.joined", LiveBetaStatus.OPEN, ObservedMembership.JOINED),
        )

        reporter().reportAfterScan(ACCOUNT)

        assertEquals(listOf(listOf("com.joined")), postedBatches)
    }

    @Test
    fun `nothing is posted when sharing is off`() = runTest {
        shareEnabled = false
        observationDao.upsert(observation("com.open", LiveBetaStatus.OPEN))

        reporter().reportAfterScan(ACCOUNT)

        assertTrue(postedBatches.isEmpty())
    }

    @Test
    fun `user-created program rows do not hide a discovery`() = runTest {
        observationDao.upsert(observation("com.user.marked", LiveBetaStatus.OPEN))
        programDao.upsert(program("com.user.marked", BetaSource.USER))
        programDao.upsert(program("com.from.catalog", BetaSource.REMOTE))
        observationDao.upsert(observation("com.from.catalog", LiveBetaStatus.OPEN))

        reporter().reportAfterScan(ACCOUNT)

        assertEquals(listOf(listOf("com.user.marked")), postedBatches)
    }

    @Test
    fun `successful upload marks packages reported so they are not resent`() = runTest {
        observationDao.upsert(observation("com.open", LiveBetaStatus.OPEN))

        reporter().reportAfterScan(ACCOUNT)
        reporter().reportAfterScan(ACCOUNT)

        assertEquals(1, postedBatches.size)
        assertTrue("com.open" in reported)
    }

    @Test
    fun `failed upload keeps the reported set unchanged for a natural retry`() = runTest {
        postResult = false
        observationDao.upsert(observation("com.open", LiveBetaStatus.OPEN))

        reporter().reportAfterScan(ACCOUNT)

        assertTrue(reported.isEmpty())

        postResult = true
        reporter().reportAfterScan(ACCOUNT)

        assertEquals(2, postedBatches.size)
        assertTrue("com.open" in reported)
    }

    @Test
    fun `large sets are chunked under the worker's request limit`() = runTest {
        (1..60).forEach { observationDao.upsert(observation("com.app$it", LiveBetaStatus.OPEN)) }

        reporter().reportAfterScan(ACCOUNT)

        assertEquals(2, postedBatches.size)
        assertTrue(postedBatches.all { it.size <= 50 })
        assertEquals(60, reported.size)
    }

    @Test
    fun `a throwing transport is swallowed and marks nothing`() = runTest {
        observationDao.upsert(observation("com.open", LiveBetaStatus.OPEN))
        val throwing = DiscoveryReporter(
            shareEnabled = { true },
            reportedPackages = { reported.toSet() },
            markReported = { reported += it },
            betaObservationDao = observationDao,
            betaProgramDao = programDao,
            post = { throw java.io.IOException("network down") },
            io = UnconfinedTestDispatcher(testScheduler),
        )

        throwing.reportAfterScan(ACCOUNT)

        assertTrue(reported.isEmpty())
    }
}
