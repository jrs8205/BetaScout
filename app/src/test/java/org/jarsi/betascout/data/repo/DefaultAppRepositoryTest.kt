package org.jarsi.betascout.data.repo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.data.betadb.BetaSeeder
import org.jarsi.betascout.data.db.BetaObservationDao
import org.jarsi.betascout.data.db.BetaObservationEntity
import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.BetaProgramEntity
import org.jarsi.betascout.data.db.InstalledAppDao
import org.jarsi.betascout.data.db.InstalledAppEntity
import org.jarsi.betascout.data.db.UserBetaStatusDao
import org.jarsi.betascout.data.db.UserBetaStatusEntity
import org.jarsi.betascout.data.scanner.PackageScanner
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.DataError
import org.jarsi.betascout.domain.InstalledAppInfo
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership
import org.jarsi.betascout.domain.UserBetaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeInstalledAppDao : InstalledAppDao {
    val state = MutableStateFlow<Map<String, InstalledAppEntity>>(emptyMap())

    override fun observeAll(): Flow<List<InstalledAppEntity>> =
        state.map { it.values.sortedBy { e -> e.label.lowercase() } }

    override suspend fun upsertAll(apps: List<InstalledAppEntity>) {
        state.value = state.value + apps.associateBy { it.packageName }
    }

    override suspend fun deleteNotIn(keep: List<String>) {
        state.value = state.value.filterKeys { it in keep }
    }
}

private class FakeBetaProgramDao : BetaProgramDao {
    val state = MutableStateFlow<Map<String, BetaProgramEntity>>(emptyMap())

    override fun observeAll(): Flow<List<BetaProgramEntity>> = state.map { it.values.toList() }

    override suspend fun insertIgnoring(programs: List<BetaProgramEntity>) {
        state.value = programs.associateBy { it.packageName } + state.value
    }

    override suspend fun upsertAll(programs: List<BetaProgramEntity>) {
        state.value = state.value + programs.associateBy { it.packageName }
    }

    override suspend fun upsert(program: BetaProgramEntity) {
        state.value = state.value + (program.packageName to program)
    }

    override suspend fun count(): Int = state.value.size
}

private class FakeBetaObservationDao : BetaObservationDao {
    val state = MutableStateFlow<Map<String, BetaObservationEntity>>(emptyMap())

    override fun observeAll(): Flow<List<BetaObservationEntity>> = state.map { it.values.toList() }

    override suspend fun get(packageName: String): BetaObservationEntity? = state.value[packageName]

    override suspend fun upsert(observation: BetaObservationEntity) {
        state.value = state.value + (observation.packageName to observation)
    }
}

private class FakeUserBetaStatusDao : UserBetaStatusDao {
    val state = MutableStateFlow<Map<String, UserBetaStatusEntity>>(emptyMap())

    override fun observeAll(): Flow<List<UserBetaStatusEntity>> = state.map { it.values.toList() }

    override fun observe(packageName: String): Flow<UserBetaStatusEntity?> =
        state.map { it[packageName] }

    override suspend fun get(packageName: String): UserBetaStatusEntity? =
        state.value[packageName]

    override suspend fun upsert(status: UserBetaStatusEntity) {
        state.value = state.value + (status.packageName to status)
    }
}

private class FakeScanner(
    var result: () -> List<InstalledAppInfo>,
) : PackageScanner {
    override suspend fun scan(): List<InstalledAppInfo> = result()
}

private fun app(packageName: String, label: String = packageName) = InstalledAppInfo(
    packageName = packageName,
    label = label,
    versionName = "1.0",
    versionCode = 1L,
    installerPackage = "com.android.vending",
    isSystem = false,
    lastScanned = 0L,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAppRepositoryTest {

    private val installedDao = FakeInstalledAppDao()
    private val betaDao = FakeBetaProgramDao()
    private val observationDao = FakeBetaObservationDao()
    private val userDao = FakeUserBetaStatusDao()
    private val scanner = FakeScanner { emptyList() }

    private fun kotlinx.coroutines.test.TestScope.repository(
        seedJson: () -> String = { """{"programs":[]}""" },
        now: Long = 42L,
    ) = DefaultAppRepository(
        scanner = scanner,
        installedAppDao = installedDao,
        betaProgramDao = betaDao,
        betaObservationDao = observationDao,
        userBetaStatusDao = userDao,
        seeder = BetaSeeder(seedJson, betaDao),
        membership = org.jarsi.betascout.domain.MembershipSource { _, _, _ -> Result.success(emptySet()) },
        io = UnconfinedTestDispatcher(testScheduler),
        clock = { now },
    )

    @Test
    fun `observeApps combines installed apps with beta info and user status`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.whatsapp", "WhatsApp"), app("com.example", "Example")) }
        repo.refreshApps()
        betaDao.upsert(
            BetaProgramEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp Messenger",
                testingUrl = null,
                knownStatus = KnownBetaStatus.OFTEN_FULL,
                notes = null,
                source = BetaSource.BUNDLED,
            )
        )
        repo.setUserState("com.whatsapp", UserBetaState.JOINED)

        val rows = repo.observeApps().first()

        assertEquals(2, rows.size)
        val whatsapp = rows.single { it.app.packageName == "com.whatsapp" }
        assertEquals(KnownBetaStatus.OFTEN_FULL, whatsapp.betaProgram?.knownStatus)
        assertEquals(UserBetaState.JOINED, whatsapp.userStatus?.state)
        val example = rows.single { it.app.packageName == "com.example" }
        assertNull(example.betaProgram)
        assertNull(example.userStatus)
    }

    @Test
    fun `observeApps includes the scraped observation for an app`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.whatsapp", "WhatsApp")) }
        repo.refreshApps()
        observationDao.upsert(
            BetaObservationEntity(
                packageName = "com.whatsapp",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 1000L,
                lastError = null,
            )
        )

        val whatsapp = repo.observeApps().first().single { it.app.packageName == "com.whatsapp" }

        assertEquals(LiveBetaStatus.OPEN, whatsapp.observation?.liveStatus)
        assertEquals(ObservedMembership.JOINED, whatsapp.observation?.observedMembership)
    }

    @Test
    fun `refreshApps replaces cache and drops uninstalled apps`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.old")) }
        repo.refreshApps()
        scanner.result = { listOf(app("com.new")) }

        val result = repo.refreshApps()

        assertTrue(result.isSuccess)
        assertEquals(listOf("com.new"), installedDao.state.value.keys.toList())
    }

    @Test
    fun `refreshApps maps scanner failure to DataError`() = runTest {
        val repo = repository()
        scanner.result = { throw RuntimeException("boom") }

        val result = repo.refreshApps()

        assertTrue(result.exceptionOrNull() is DataError.Scan)
    }

    @Test
    fun `setUserState creates status row with defaults when missing`() = runTest {
        val repo = repository()

        repo.setUserState("com.whatsapp", UserBetaState.FULL)

        val status = userDao.get("com.whatsapp")!!
        assertEquals(UserBetaState.FULL, status.state)
        assertEquals(false, status.watching)
        assertEquals(7, status.reminderIntervalDays)
    }

    @Test
    fun `setWatching updates interval but preserves other fields`() = runTest {
        val repo = repository()
        repo.setUserState("com.whatsapp", UserBetaState.JOINED)
        repo.setUserNote("com.whatsapp", "my note")

        repo.setWatching("com.whatsapp", watching = true, reminderIntervalDays = 14)

        val status = userDao.get("com.whatsapp")!!
        assertEquals(UserBetaState.JOINED, status.state)
        assertEquals("my note", status.userNote)
        assertEquals(true, status.watching)
        assertEquals(14, status.reminderIntervalDays)
    }

    @Test
    fun `setWatching keeps previous interval when none given`() = runTest {
        val repo = repository()
        repo.setWatching("com.whatsapp", watching = true, reminderIntervalDays = 14)

        repo.setWatching("com.whatsapp", watching = false)

        assertEquals(14, userDao.get("com.whatsapp")!!.reminderIntervalDays)
    }

    @Test
    fun `markCheckedNow stamps current time`() = runTest {
        val repo = repository(now = 123L)

        repo.markCheckedNow("com.whatsapp")

        assertEquals(123L, userDao.get("com.whatsapp")!!.lastCheckedByUser)
    }

    @Test
    fun `ensureSeeded loads seed into beta dao`() = runTest {
        val repo = repository(seedJson = {
            """{"programs":[{"packageName":"com.whatsapp","appName":"WhatsApp"}]}"""
        })

        val result = repo.ensureSeeded()

        assertTrue(result.isSuccess)
        assertEquals(1, betaDao.count())
    }

    @Test
    fun `ensureSeeded maps failure to DataError`() = runTest {
        val repo = repository(seedJson = { throw IllegalStateException("asset missing") })

        val result = repo.ensureSeeded()

        assertTrue(result.exceptionOrNull() is DataError.Local)
    }

    @Test
    fun `enabling watch stamps lastRemindedAt so the first reminder waits a full interval`() = runTest {
        val repo = repository(now = 500L)

        repo.setWatching("com.whatsapp", watching = true)

        assertEquals(500L, userDao.get("com.whatsapp")!!.lastRemindedAt)
    }

    @Test
    fun `markReminded stamps lastRemindedAt for all given packages`() = runTest {
        val repo = repository(now = 900L)
        repo.setUserState("com.whatsapp", UserBetaState.JOINED)

        val result = repo.markReminded(listOf("com.whatsapp", "com.other"))

        assertTrue(result.isSuccess)
        assertEquals(900L, userDao.get("com.whatsapp")!!.lastRemindedAt)
        assertEquals(900L, userDao.get("com.other")!!.lastRemindedAt)
        assertEquals(UserBetaState.JOINED, userDao.get("com.whatsapp")!!.state)
    }
}
