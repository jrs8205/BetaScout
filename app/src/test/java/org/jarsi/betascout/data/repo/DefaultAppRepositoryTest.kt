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
import org.jarsi.betascout.data.scrape.BetaStatusScraper
import org.jarsi.betascout.data.scrape.FetchedPage
import org.jarsi.betascout.data.scrape.TestingPageSource
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.domain.DataError
import org.jarsi.betascout.domain.InstalledAppInfo
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership
import org.jarsi.betascout.domain.ScanProgress
import org.jarsi.betascout.domain.StatusTransition
import org.jarsi.betascout.domain.UserBetaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACCOUNT = "user@example.com"
private const val OTHER_ACCOUNT = "other@example.com"

private class FakeInstalledAppDao : InstalledAppDao {
    val state = MutableStateFlow<Map<String, InstalledAppEntity>>(emptyMap())

    override fun observeAll(): Flow<List<InstalledAppEntity>> =
        state.map { it.values.sortedBy { e -> e.label.lowercase() } }

    override suspend fun getAll(): List<InstalledAppEntity> =
        state.value.values.sortedBy { e -> e.label.lowercase() }

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
    private data class ObservationKey(val accountKey: String, val packageName: String)

    private val state = MutableStateFlow<Map<ObservationKey, BetaObservationEntity>>(emptyMap())

    override fun observeAll(): Flow<List<BetaObservationEntity>> = state.map { it.values.toList() }

    override suspend fun getAll(): List<BetaObservationEntity> = state.value.values.toList()

    override suspend fun getAllForAccount(accountKey: String): List<BetaObservationEntity> =
        state.value.values.filter { it.accountKey == accountKey }

    override suspend fun get(accountKey: String, packageName: String): BetaObservationEntity? =
        state.value[ObservationKey(accountKey, packageName)]

    override suspend fun deleteForAccount(accountKey: String) {
        state.value = state.value.filterKeys { it.accountKey != accountKey }
    }

    override suspend fun upsert(observation: BetaObservationEntity) {
        state.value = state.value + (ObservationKey(observation.accountKey, observation.packageName) to observation)
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

private fun app(
    packageName: String,
    label: String = packageName,
    isSystem: Boolean = false,
    hasLauncher: Boolean = !isSystem,
    installerPackage: String? = "com.android.vending",
) = InstalledAppInfo(
    packageName = packageName,
    label = label,
    versionName = "1.0",
    versionCode = 1L,
    installerPackage = installerPackage,
    isSystem = isSystem,
    hasLauncher = hasLauncher,
    lastScanned = 0L,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAppRepositoryTest {

    private val installedDao = FakeInstalledAppDao()
    private val betaDao = FakeBetaProgramDao()
    private val observationDao = FakeBetaObservationDao()
    private val userDao = FakeUserBetaStatusDao()
    private val scanner = FakeScanner { emptyList() }
    private val currentAccountKey = MutableStateFlow<String?>(ACCOUNT)
    private val session = PlaySession(accountEmail = ACCOUNT, cookieHeader = "SID=abc")

    /** HTML the fake testing-page source returns per package (empty page by default). */
    private var pageHtml: (String) -> String = { "<html><body></body></html>" }

    /** Packages whose page fetch fails (simulated transient network error). */
    private val failingPackages = mutableSetOf<String>()

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
        scraper = BetaStatusScraper(
            source = TestingPageSource { pkg, _ ->
                if (pkg in failingPackages) {
                    Result.failure(RuntimeException("network error"))
                } else {
                    Result.success(FetchedPage(pageHtml(pkg)))
                }
            },
            clock = { now },
            delayFn = {},
        ),
        currentAccountKey = currentAccountKey,
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
                accountKey = ACCOUNT,
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
    fun `observeApps ignores scraped observations from another account`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.whatsapp", "WhatsApp")) }
        repo.refreshApps()
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = OTHER_ACCOUNT,
                packageName = "com.whatsapp",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 1000L,
                lastError = null,
            )
        )

        val whatsapp = repo.observeApps().first().single { it.app.packageName == "com.whatsapp" }

        assertNull(whatsapp.observation)
    }

    @Test
    fun `refreshBetaStatus scrapes due installed apps and records observations`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        pageHtml = { pkg ->
            if (pkg == "com.a") """<html><body><form id="leaveForm"></form></body></html>"""
            else """<html><body><form id="joinForm"></form></body></html>"""
        }

        val summary = repo.refreshBetaStatus(session).getOrThrow()

        assertEquals(2, summary.checked)
        assertEquals(1, summary.joined)
        assertEquals(false, summary.needsLogin)
        assertEquals(ObservedMembership.JOINED, observationDao.get(ACCOUNT, "com.a")!!.observedMembership)
        assertEquals(ObservedMembership.NOT_JOINED, observationDao.get(ACCOUNT, "com.b")!!.observedMembership)
        assertEquals(LiveBetaStatus.OPEN, observationDao.get(ACCOUNT, "com.b")!!.liveStatus)
    }

    @Test
    fun `refreshBetaStatus ignores another account observation when choosing due apps`() = runTest {
        val repo = repository(now = 10_000L)
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = OTHER_ACCOUNT,
                packageName = "com.a",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 10_000L,
                lastError = null,
            )
        )
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }

        val summary = repo.refreshBetaStatus(session).getOrThrow()

        assertEquals(1, summary.checked)
        assertEquals(ObservedMembership.NOT_JOINED, observationDao.get(ACCOUNT, "com.a")!!.observedMembership)
        assertEquals(ObservedMembership.JOINED, observationDao.get(OTHER_ACCOUNT, "com.a")!!.observedMembership)
    }

    @Test
    fun `refreshBetaStatus includes launchable and store-updated system apps, skips framework packages`() = runTest {
        val repo = repository()
        scanner.result = {
            listOf(
                app("com.user"),
                app("com.google.android.gm", isSystem = true, hasLauncher = true),
                // Store-updated but no launcher icon (WebView, Play services…).
                app(
                    "com.google.android.webview",
                    isSystem = true,
                    hasLauncher = false,
                    installerPackage = "com.android.vending",
                ),
                app(
                    "com.android.providers.media",
                    isSystem = true,
                    hasLauncher = false,
                    installerPackage = null,
                ),
            )
        }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }

        val summary = repo.refreshBetaStatus(session).getOrThrow()

        assertEquals(3, summary.checked)
        assertNull(observationDao.get(ACCOUNT, "com.android.providers.media"))
        assertEquals(
            ObservedMembership.NOT_JOINED,
            observationDao.get(ACCOUNT, "com.google.android.gm")!!.observedMembership,
        )
        assertEquals(
            ObservedMembership.NOT_JOINED,
            observationDao.get(ACCOUNT, "com.google.android.webview")!!.observedMembership,
        )
    }

    @Test
    fun `refreshBetaStatus counts pages that could not be fetched as failed`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.ok"), app("com.broken")) }
        repo.refreshApps()
        failingPackages += "com.broken"
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }

        val summary = repo.refreshBetaStatus(session).getOrThrow()

        assertEquals(1, summary.checked)
        assertEquals(1, summary.failed)
        // The failed package keeps no observation, so the next run retries it first.
        assertNull(observationDao.get(ACCOUNT, "com.broken"))
    }

    @Test
    fun `refreshBetaStatus skips fresh observations unless forced`() = runTest {
        val repo = repository(now = 10_000L)
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = ACCOUNT,
                packageName = "com.a",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.NOT_JOINED,
                checkedAt = 9_000L,
                lastError = null,
            )
        )
        pageHtml = { """<html><body><form id="leaveForm"></form></body></html>""" }

        val throttled = repo.refreshBetaStatus(session).getOrThrow()
        assertEquals(0, throttled.checked)

        // The user may have joined or left a beta outside the app, so a manual
        // "Scan now" must re-check even a fresh observation.
        val forced = repo.refreshBetaStatus(session, force = true).getOrThrow()
        assertEquals(1, forced.checked)
        assertEquals(ObservedMembership.JOINED, observationDao.get(ACCOUNT, "com.a")!!.observedMembership)
    }

    @Test
    fun `refreshBetaStatus reports joined and not-joined totals for the whole account`() = runTest {
        val repo = repository(now = 10_000L)
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        // A membership observed on an earlier run still counts toward the totals.
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = ACCOUNT,
                packageName = "com.earlier",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 9_999L,
                lastError = null,
            )
        )
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }

        val summary = repo.refreshBetaStatus(session, force = true).getOrThrow()

        assertEquals(1, summary.checked)
        assertEquals(1, summary.joined)
        assertEquals(1, summary.notJoined)
    }

    @Test
    fun `refreshBetaStatus reports a transition when a full program opens`() = runTest {
        val repo = repository(now = 100 * 3_600_000L)
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = ACCOUNT,
                packageName = "com.a",
                liveStatus = LiveBetaStatus.FULL,
                observedMembership = ObservedMembership.NOT_JOINED,
                checkedAt = 0L,
                lastError = null,
            )
        )
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }

        val summary = repo.refreshBetaStatus(session).getOrThrow()

        // com.b is a first sighting, not a change, so only com.a transitions.
        assertEquals(
            listOf(StatusTransition("com.a", LiveBetaStatus.FULL, LiveBetaStatus.OPEN)),
            summary.transitions,
        )
    }

    @Test
    fun `refreshBetaStatus scans all due apps in one run when uncapped`() = runTest {
        val repo = repository()
        scanner.result = { (1..35).map { app("com.app$it") } }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }

        val summary = repo.refreshBetaStatus(session).getOrThrow()

        assertEquals(35, summary.checked)
    }

    @Test
    fun `refreshBetaStatus caps a run when a cap is given`() = runTest {
        val repo = repository()
        scanner.result = { (1..35).map { app("com.app$it") } }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }

        val summary = repo.refreshBetaStatus(session, cap = 10).getOrThrow()

        assertEquals(10, summary.checked)
    }

    @Test
    fun `refreshBetaStatus reports scan progress with app labels`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a", "Alpha"), app("com.b", "Beta")) }
        repo.refreshApps()
        val progress = mutableListOf<ScanProgress>()

        repo.refreshBetaStatus(session) { progress += it }

        assertEquals(
            listOf(ScanProgress(1, 2, "Alpha"), ScanProgress(2, 2, "Beta")),
            progress,
        )
    }

    @Test
    fun `clearObservations deletes only the given account's observations`() = runTest {
        val repo = repository()
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = "a@example.com",
                packageName = "com.a",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 1L,
                lastError = null,
            )
        )
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = "b@example.com",
                packageName = "com.a",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 1L,
                lastError = null,
            )
        )

        val result = repo.clearObservations("a@example.com")

        assertTrue(result.isSuccess)
        assertNull(observationDao.get("a@example.com", "com.a"))
        assertEquals(
            ObservedMembership.JOINED,
            observationDao.get("b@example.com", "com.a")!!.observedMembership,
        )
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
