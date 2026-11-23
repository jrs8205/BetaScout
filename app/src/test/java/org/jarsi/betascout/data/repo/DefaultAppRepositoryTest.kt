package org.jarsi.betascout.data.repo

import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.jarsi.betascout.data.settings.FlakyStore
import org.jarsi.betascout.data.settings.SessionCookieCipher
import org.jarsi.betascout.data.settings.SessionPrefs
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.ui.appdetail.AppDetailViewModel
import org.jarsi.betascout.ui.appdetail.CheckStatusError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.data.betadb.BetaSeedParser
import org.jarsi.betascout.data.betadb.BetaSeeder
import org.jarsi.betascout.data.betadb.CatalogFingerprint
import org.jarsi.betascout.data.betadb.CatalogSnapshot
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
import org.jarsi.betascout.data.scrape.HttpStatusException
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
import org.junit.Assert.assertFalse
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

    override suspend fun getAllPackageNames(): List<String> = state.value.keys.toList()

    override suspend fun deleteIn(packageNames: List<String>) {
        state.value = state.value.filterKeys { it !in packageNames.toSet() }
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

    override suspend fun getAllPackageNames(): List<String> = state.value.keys.toList()

    override suspend fun deleteIn(packageNames: List<String>) {
        state.value = state.value.filterKeys { it !in packageNames.toSet() }
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

    override suspend fun deleteAll() {
        state.value = emptyMap()
    }

    override suspend fun upsert(observation: BetaObservationEntity) {
        state.value = state.value + (ObservationKey(observation.accountKey, observation.packageName) to observation)
    }
}

private class FakeUserBetaStatusDao : UserBetaStatusDao {
    var beforeWrite: suspend () -> Unit = {}
    val state = MutableStateFlow<Map<String, UserBetaStatusEntity>>(emptyMap())

    override fun observeAll(): Flow<List<UserBetaStatusEntity>> = state.map { it.values.toList() }

    override suspend fun getAll(): List<UserBetaStatusEntity> = state.value.values.toList()

    override fun observe(packageName: String): Flow<UserBetaStatusEntity?> =
        state.map { it[packageName] }

    override suspend fun get(packageName: String): UserBetaStatusEntity? =
        state.value[packageName]

    override suspend fun upsert(status: UserBetaStatusEntity) {
        beforeWrite()
        state.value = state.value + (status.packageName to status)
    }

    override suspend fun insertMissing(statuses: List<UserBetaStatusEntity>) {
        state.value = statuses.associateBy { it.packageName } + state.value
    }

    override suspend fun setReminderTime(packages: List<String>, at: Long) {
        beforeWrite()
        state.value = state.value.mapValues { (pkg, row) ->
            if (pkg in packages) row.copy(lastRemindedAt = at) else row
        }
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

    /** Which sign-in the stored session is ([PlaySession.generation]). */
    private var signedInGeneration = session.generation

    /** HTML the fake testing-page source returns per package (empty page by default). */
    private var pageHtml: (String) -> String = { "<html><body></body></html>" }

    /** Packages whose page fetch fails (simulated transient network error). */
    private val failingPackages = mutableSetOf<String>()

    /** Packages whose page fetch fails with HTTP 429 (Google throttling). */
    private val rateLimitedPackages = mutableSetOf<String>()

    /** What the repository persisted via setScanBlockedUntil, if anything. */
    private var savedBlockedUntil: Long? = null

    /** Runs inside every fake page fetch; tests use it to observe scan concurrency. */
    private var onFetch: suspend (String) -> Unit = {}

    /** Scan tickets handed to the repository so far. */
    private var ticketsIssued = 0L

    private fun kotlinx.coroutines.test.TestScope.repository(
        seedJson: () -> String = { """{"programs":[]}""" },
        now: Long = 42L,
        blockedUntil: suspend () -> Long? = { null },
        ownsSession: suspend (PlaySession) -> Boolean = {
            it.accountKey == currentAccountKey.value && it.generation == signedInGeneration
        },
    ) = DefaultAppRepository(
        scanner = scanner,
        installedAppDao = installedDao,
        betaProgramDao = betaDao,
        betaObservationDao = observationDao,
        userBetaStatusDao = userDao,
        seeder = BetaSeeder(
            readCatalog = {
                val json = seedJson()
                CatalogSnapshot(
                    programs = BetaSeedParser.parse(json, BetaSource.REMOTE),
                    source = BetaSource.REMOTE,
                    fingerprint = CatalogFingerprint.of(json),
                )
            },
            markApplied = {},
            dao = betaDao,
        ),
        scraper = BetaStatusScraper(
            source = TestingPageSource { pkg, _ ->
                onFetch(pkg)
                when {
                    pkg in rateLimitedPackages -> Result.failure(HttpStatusException(429))
                    pkg in failingPackages -> Result.failure(RuntimeException("network error"))
                    else -> Result.success(FetchedPage(pageHtml(pkg)))
                }
            },
            clock = { now },
            delayFn = {},
        ),
        currentAccountKey = currentAccountKey,
        sessionIsCurrent = ownsSession,
        io = UnconfinedTestDispatcher(testScheduler),
        clock = { now },
        scanBlockedUntil = blockedUntil,
        setScanBlockedUntil = { savedBlockedUntil = it },
        scanTicket = { ++ticketsIssued },
    )

    @Test
    fun `detail recheck reports an expiry write failure and can retry`() = runTest {
        val store = FlakyStore()
        pageHtml = { "<input id='identifierId'>" }
        checkDetail(store) { vm ->
            store.failingWrites = true
            vm.checkStatusNow()
            runCurrent()
            assertEquals(CheckStatusError.FAILED, vm.uiState.value.checkError)
            assertTrue(vm.uiState.value.signedIn)
            assertFalse(vm.uiState.value.checkingStatus)

            store.failingWrites = false
            vm.checkStatusNow()
            runCurrent()
            assertFalse(vm.uiState.value.signedIn)
            assertNull(vm.uiState.value.checkError)
        }
    }

    @Test
    fun `detail recheck reports an unreadable owner and never fetches with that session`() = runTest {
        val store = FlakyStore()
        var fetched = false
        onFetch = { fetched = true }
        checkDetail(store, beforeOwnershipRead = { store.failingReads = 1 }) { vm ->
            vm.checkStatusNow()
            runCurrent()
            assertEquals(CheckStatusError.FAILED, vm.uiState.value.checkError)
            assertTrue(vm.uiState.value.signedIn)
            assertFalse(fetched)
        }
    }

    private suspend fun TestScope.checkDetail(
        store: FlakyStore,
        beforeOwnershipRead: () -> Unit = {},
        check: (AppDetailViewModel) -> Unit,
    ) {
        val main = StandardTestDispatcher(testScheduler)
        store.edit { SessionPrefs.signIn(it, ACCOUNT, "SID=fixture") }
        val settings = SettingsRepository(store, SessionCookieCipher(), main)
        val repo = repository(ownsSession = {
            beforeOwnershipRead()
            settings.holdsPlaySession(it)
        })
        Dispatchers.setMain(main)
        try {
            val vm = AppDetailViewModel(SavedStateHandle(mapOf("packageName" to "example.app")), repo, settings)
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            try {
                runCurrent()
                check(vm)
            } finally {
                observer.cancel()
                vm.viewModelScope.cancel()
                runCurrent()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `detail reports a failed initial session read and releases the recheck button`() = runTest {
        val store = FlakyStore()
        var fetched = false
        onFetch = { fetched = true }
        checkDetail(store) { vm ->
            store.failingReads = 1
            vm.checkStatusNow()
            runCurrent()
            assertEquals(CheckStatusError.FAILED, vm.uiState.value.checkError)
            assertFalse(vm.uiState.value.checkingStatus)
            assertFalse(fetched)
        }
    }

    @Test
    fun `a batch scan with an unreadable owner returns the retriable session error`() = runTest {
        var fetched = false
        onFetch = { fetched = true }
        val repo = repository(ownsSession = { throw java.io.IOException("disk") })
        val result = repo.refreshBetaStatus(session)
        assertTrue(result.exceptionOrNull() is DataError.UnreadableSession)
        assertFalse(fetched)
        assertFalse(repo.scanRunning.value)
    }

    @Test
    fun `getApps returns the combined snapshot without collecting the flow`() = runTest {
        // Workers need a one-shot read: a Room flow's initial emission can be lost
        // to the invalidation-tracker race, after which first() suspends forever.
        val repo = repository()
        scanner.result = { listOf(app("com.whatsapp", "WhatsApp")) }
        repo.refreshApps()
        repo.setUserState("com.whatsapp", UserBetaState.JOINED)

        val rows = repo.getApps()

        val whatsapp = rows.single { it.app.packageName == "com.whatsapp" }
        assertEquals(UserBetaState.JOINED, whatsapp.userStatus?.state)
    }

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
                // Updated by an OEM updater, not the Play Store: no testing page.
                app(
                    "com.oem.component",
                    isSystem = true,
                    hasLauncher = false,
                    installerPackage = "com.oem.updater",
                ),
            )
        }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }

        val summary = repo.refreshBetaStatus(session).getOrThrow()

        assertEquals(3, summary.checked)
        assertNull(observationDao.get(ACCOUNT, "com.android.providers.media"))
        assertNull(observationDao.get(ACCOUNT, "com.oem.component"))
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
        assertEquals("RuntimeException: network error", summary.failures["com.broken"])
        // The failed package keeps no observation, so the next run retries it first.
        assertNull(observationDao.get(ACCOUNT, "com.broken"))
    }

    @Test
    fun `a fetch failure stamps the reason on the existing observation without touching its status`() = runTest {
        val repo = repository(now = 100 * 3_600_000L)
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = ACCOUNT,
                packageName = "com.a",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 1_000L,
                lastError = null,
            )
        )
        failingPackages += "com.a"

        val summary = repo.refreshBetaStatus(session, force = true).getOrThrow()

        assertEquals(1, summary.failed)
        val observation = observationDao.get(ACCOUNT, "com.a")!!
        assertEquals("RuntimeException: network error", observation.lastError)
        // Status and freshness stay intact: the blip must not overwrite a good
        // status, and the stale checkedAt keeps the package due on the next run.
        assertEquals(LiveBetaStatus.OPEN, observation.liveStatus)
        assertEquals(ObservedMembership.JOINED, observation.observedMembership)
        assertEquals(1_000L, observation.checkedAt)
    }

    @Test
    fun `a successful re-check clears a previously recorded fetch error`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = ACCOUNT,
                packageName = "com.a",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 0L,
                lastError = "SocketTimeoutException: read timed out",
            )
        )
        pageHtml = { """<html><body><form id="leaveForm"></form></body></html>""" }

        repo.refreshBetaStatus(session, force = true).getOrThrow()

        assertNull(observationDao.get(ACCOUNT, "com.a")!!.lastError)
    }

    @Test
    fun `refreshBetaStatus counts apps without a beta program in the account totals`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.gone")) }
        repo.refreshApps()
        pageHtml = { pkg ->
            if (pkg == "com.gone") """<html><body>The requested URL was not found.</body></html>"""
            else """<html><body><form id="joinForm"></form></body></html>"""
        }

        val summary = repo.refreshBetaStatus(session).getOrThrow()

        assertEquals(2, summary.checked)
        assertEquals(1, summary.notJoined)
        assertEquals(1, summary.noProgram)
        assertEquals(LiveBetaStatus.NO_PROGRAM, observationDao.get(ACCOUNT, "com.gone")!!.liveStatus)
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
    fun `refreshSingleBetaStatus re-checks one app immediately even when its observation is fresh`() = runTest {
        val repo = repository(now = 10_000L)
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        // Fresh observation, well inside the TTL — a batch scan would skip it,
        // but the user just visited the beta page so it must be re-read now.
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = ACCOUNT,
                packageName = "com.a",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.NOT_JOINED,
                checkedAt = 9_999L,
                lastError = null,
            )
        )
        pageHtml = { """<html><body><form id="leaveForm"></form></body></html>""" }

        val result = repo.refreshSingleBetaStatus(session, "com.a")

        assertTrue(result.isSuccess)
        val observation = observationDao.get(ACCOUNT, "com.a")!!
        assertEquals(ObservedMembership.JOINED, observation.observedMembership)
        assertEquals(10_000L, observation.checkedAt)
    }

    @Test
    fun `refreshSingleBetaStatus stamps a fetch failure without touching the stored status`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        observationDao.upsert(
            BetaObservationEntity(
                accountKey = ACCOUNT,
                packageName = "com.a",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 1_000L,
                lastError = null,
            )
        )
        failingPackages += "com.a"

        val result = repo.refreshSingleBetaStatus(session, "com.a")

        assertTrue(result.exceptionOrNull() is DataError.Local)
        val observation = observationDao.get(ACCOUNT, "com.a")!!
        assertEquals("RuntimeException: network error", observation.lastError)
        assertEquals(ObservedMembership.JOINED, observation.observedMembership)
        assertEquals(1_000L, observation.checkedAt)
    }

    @Test
    fun `refreshSingleBetaStatus is rejected while a full scan holds the lock`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }
        var single: Result<Unit>? = null
        onFetch = {
            if (single == null) single = repo.refreshSingleBetaStatus(session, "com.a")
        }

        repo.refreshBetaStatus(session).getOrThrow()

        assertTrue(single!!.exceptionOrNull() is DataError.ScanInProgress)
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
    fun `records each observation as soon as it is scraped`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }
        val seenDuringScan = mutableListOf<String?>()
        onFetch = { pkg ->
            // What com.a looks like in the DB while com.b is still being fetched.
            if (pkg == "com.b") seenDuringScan += observationDao.get(ACCOUNT, "com.a")?.packageName
        }

        repo.refreshBetaStatus(session).getOrThrow()

        assertEquals(listOf<String?>("com.a"), seenDuringScan)
    }

    @Test
    fun `a run that dies mid-scan keeps the observations recorded so far`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }
        onFetch = { pkg -> if (pkg == "com.b") throw IllegalStateException("boom") }

        val result = repo.refreshBetaStatus(session)

        assertTrue(result.exceptionOrNull() is DataError.Local)
        assertEquals(
            ObservedMembership.NOT_JOINED,
            observationDao.get(ACCOUNT, "com.a")!!.observedMembership,
        )
        assertNull(observationDao.get(ACCOUNT, "com.b"))
    }

    @Test
    fun `a cancelled scan keeps completed observations and releases the scan lock`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }
        lateinit var job: Job
        onFetch = { pkg ->
            if (pkg == "com.b") {
                job.cancel()
                // First suspension point after the cancel → CancellationException here.
                delay(1)
            }
        }
        job = launch { repo.refreshBetaStatus(session) }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(
            ObservedMembership.NOT_JOINED,
            observationDao.get(ACCOUNT, "com.a")!!.observedMembership,
        )
        assertNull(observationDao.get(ACCOUNT, "com.b"))
        // The finally block released the lock: a follow-up scan must not be rejected.
        assertTrue(repo.refreshBetaStatus(session, force = true).isSuccess)
    }

    @Test
    fun `scanRunning reflects the scan lock across a cancelled run`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }
        assertEquals(false, repo.scanRunning.value)
        val seenWhileScanning = mutableListOf<Boolean>()
        lateinit var job: Job
        onFetch = { pkg ->
            seenWhileScanning += repo.scanRunning.value
            if (pkg == "com.b") {
                job.cancel()
                delay(1)
            }
        }
        job = launch { repo.refreshBetaStatus(session) }
        job.join()

        // True during the run, false again once the cancelled run has unwound —
        // the account screen gates its scan buttons on this.
        assertEquals(listOf(true, true), seenWhileScanning)
        assertEquals(false, repo.scanRunning.value)
    }

    @Test
    fun `clearAllObservations deletes every account's observations including orphaned keys`() = runTest {
        // Cookie-hash fallback keys change across re-logins, so rows can be
        // orphaned under keys no caller knows anymore; sign-out wipes them all.
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
                accountKey = "cookie:0123abcd",
                packageName = "com.a",
                liveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
                checkedAt = 1L,
                lastError = null,
            )
        )

        val result = repo.clearAllObservations()

        assertTrue(result.isSuccess)
        assertNull(observationDao.get("a@example.com", "com.a"))
        assertNull(observationDao.get("cookie:0123abcd", "com.a"))
    }

    @Test
    fun `an active Google-block cooldown rejects the scan without any fetch`() = runTest {
        val fetched = mutableListOf<String>()
        onFetch = { fetched += it }
        val repo = repository(now = 1_000L, blockedUntil = { 2_000L })
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()

        val result = repo.refreshBetaStatus(session)

        assertTrue(result.exceptionOrNull() is DataError.ScanBlocked)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `an expired block cooldown does not reject the scan`() = runTest {
        val fetched = mutableListOf<String>()
        onFetch = { fetched += it }
        val repo = repository(now = 1_000L, blockedUntil = { 500L })
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()

        val result = repo.refreshBetaStatus(session)

        assertTrue(result.isSuccess)
        assertEquals(listOf("com.a"), fetched)
    }

    @Test
    fun `a rate-limited run persists the block cooldown`() = runTest {
        val repo = repository(now = 1_000L)
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        rateLimitedPackages += "com.a"

        repo.refreshBetaStatus(session)

        assertEquals(1_000L + 60 * 60 * 1_000L, savedBlockedUntil)
    }

    @Test
    fun `an active block cooldown rejects the single-app re-check too`() = runTest {
        val fetched = mutableListOf<String>()
        onFetch = { fetched += it }
        val repo = repository(now = 1_000L, blockedUntil = { 2_000L })

        val result = repo.refreshSingleBetaStatus(session, "com.a")

        assertTrue(result.exceptionOrNull() is DataError.ScanBlocked)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a rate-limited single-app re-check persists the block cooldown`() = runTest {
        val repo = repository(now = 1_000L)
        rateLimitedPackages += "com.a"

        repo.refreshSingleBetaStatus(session, "com.a")

        assertEquals(1_000L + 60 * 60 * 1_000L, savedBlockedUntil)
    }

    @Test
    fun `withScanLock waits for the running scan and rejects scans started meanwhile`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        var lockCompletedDuringScan: Boolean? = null
        onFetch = {
            // Probed from inside the scan: the lock is held, so a waiter must
            // still be suspended.
            val probe = async { repo.withScanLock { } }
            lockCompletedDuringScan = probe.isCompleted
            probe.cancel()
        }

        repo.refreshBetaStatus(session).getOrThrow()
        var scanDuringLock: Result<*>? = null
        repo.withScanLock {
            // A scan attempted while sign-out holds the lock must bounce off
            // instead of running against a half-wiped account.
            scanDuringLock = repo.refreshBetaStatus(session)
        }

        assertEquals(false, lockCompletedDuringScan)
        assertTrue(scanDuringLock!!.exceptionOrNull() is DataError.ScanInProgress)
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
    fun `a single re-check aborts inside the scan lock once the account has signed out`() = runTest {
        // The detail screen reads the session before it reaches the scan lock, and
        // sign-out (which cancels only the WorkManager scans) can complete its wipe
        // in that window. The re-check must then stop instead of fetching Google
        // with the dead cookies and writing a membership row for the signed-out
        // account back into the freshly emptied table.
        val repo = repository()
        pageHtml = { """<html><body><form id="leaveForm"></form></body></html>""" }
        var fetches = 0
        onFetch = { fetches++ }
        currentAccountKey.value = null

        val result = repo.refreshSingleBetaStatus(session, "com.whatsapp")

        // StaleSession, not NeedsLogin: the detail screen clears the stored session
        // on NeedsLogin, which here would wipe whatever account signed in meanwhile.
        assertTrue("expected StaleSession, was ${result.exceptionOrNull()}", result.exceptionOrNull() is DataError.StaleSession)
        assertEquals(0, fetches)
        assertTrue(observationDao.getAll().isEmpty())
    }

    @Test
    fun `a scan refused while another runs is placed before that scan's end`() = runTest {
        // The refused run's caller records "did not run" some time later; the
        // running scan's caller records "got through" some time later too. Which
        // came first is settled here, at the lock: the refusal, always — or it
        // would outlive the very scan that answers it.
        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        var refused: Result<*>? = null
        onFetch = { refused = repo.refreshBetaStatus(session) }

        val summary = repo.refreshBetaStatus(session).getOrThrow()

        val refusal = refused!!.exceptionOrNull() as DataError.ScanInProgress
        assertTrue("no ticket for the refusal", refusal.scanTicket > 0)
        assertTrue(
            "refused at ${refusal.scanTicket}, ended at ${summary.ticket}",
            refusal.scanTicket < summary.ticket,
        )
    }

    @Test
    fun `a scan refused after another ended is placed after it`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()

        val summary = repo.refreshBetaStatus(session).getOrThrow()
        var refused: Result<*>? = null
        // Something else holds the lock now (a sign-out, a single re-check).
        repo.withScanLock { refused = repo.refreshBetaStatus(session) }

        val refusal = refused!!.exceptionOrNull() as DataError.ScanInProgress
        assertTrue(
            "ended at ${summary.ticket}, refused at ${refusal.scanTicket}",
            summary.ticket in 1 until refusal.scanTicket,
        )
    }

    @Test
    fun `a scan refused for the cooldown says when, and a failed one when it ended`() = runTest {
        val blocked = repository(now = 1_000L, blockedUntil = { 5_000L }).refreshBetaStatus(session)
        assertTrue((blocked.exceptionOrNull() as DataError.ScanBlocked).scanTicket > 0)

        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        onFetch = { throw IllegalStateException("boom") }
        val failed = repo.refreshBetaStatus(session)
        val ticket = (failed.exceptionOrNull() as? DataError)?.scanTicket
        // Either the page failure was absorbed into the summary, or the run
        // failed as a whole: both say when the lock was let go.
        assertTrue(
            "was $failed",
            failed.getOrNull()?.ticket?.let { it > 0 } ?: (ticket != null && ticket > 0),
        )
    }

    @Test
    fun `a scan aborts inside the scan lock once the account has signed out`() = runTest {
        // A pull-to-refresh can enqueue its scan after sign-out has cancelled
        // the scans. That run reads the session while it is still stored and
        // reaches the scan lock only after the cleanup, which holds the lock,
        // is through. It must then stop: no fetch with the old cookies, and no
        // observation written back for the account that has just been wiped.
        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="leaveForm"></form></body></html>""" }
        var fetches = 0
        onFetch = { fetches++ }
        currentAccountKey.value = null

        val result = repo.refreshBetaStatus(session)

        assertTrue(
            "expected StaleSession, was ${result.exceptionOrNull()}",
            result.exceptionOrNull() is DataError.StaleSession,
        )
        assertEquals(0, fetches)
        assertTrue(observationDao.getAll().isEmpty())
        // The lock is free again for whoever signs in.
        assertFalse(repo.scanRunning.value)
        currentAccountKey.value = ACCOUNT
        assertEquals(1, repo.refreshBetaStatus(session).getOrThrow().checked)
    }

    @Test
    fun `a scan for a different signed-in account is refused too`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        var fetches = 0
        onFetch = { fetches++ }
        currentAccountKey.value = OTHER_ACCOUNT

        val result = repo.refreshBetaStatus(session)

        assertTrue(result.exceptionOrNull() is DataError.StaleSession)
        assertEquals(0, fetches)
        assertTrue(observationDao.getAll().isEmpty())
    }

    @Test
    fun `a scan made for an earlier sign-in of the same account stops inside the scan lock`() = runTest {
        // A background run read its session and was on its way to the scan
        // lock when the user signed in again, as the same account. The account
        // matches; the session is another one. The run must not fetch with the
        // old cookies, and must not sit on the lock while the scan started by
        // the new sign-in is turned away.
        val repo = repository()
        scanner.result = { listOf(app("com.a")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="leaveForm"></form></body></html>""" }
        var fetches = 0
        onFetch = { fetches++ }
        signedInGeneration = session.generation + 1

        val result = repo.refreshBetaStatus(session)

        assertTrue(
            "expected StaleSession, was ${result.exceptionOrNull() ?: result.getOrNull()}",
            result.exceptionOrNull() is DataError.StaleSession,
        )
        assertEquals(0, fetches)
        // The lock is free for the new sign-in's own scan.
        assertFalse(repo.scanRunning.value)
        val current = session.copy(generation = signedInGeneration)
        assertEquals(1, repo.refreshBetaStatus(current).getOrThrow().checked)
    }

    @Test
    fun `a single re-check made for an earlier sign-in of the same account is refused too`() = runTest {
        val repo = repository()
        var fetches = 0
        onFetch = { fetches++ }
        signedInGeneration = session.generation + 1

        val result = repo.refreshSingleBetaStatus(session, "com.whatsapp")

        assertTrue(
            "expected StaleSession, was ${result.exceptionOrNull() ?: result.getOrNull()}",
            result.exceptionOrNull() is DataError.StaleSession,
        )
        assertEquals(0, fetches)
        assertTrue(observationDao.getAll().isEmpty())
    }

    @Test
    fun `a single re-check for a different signed-in account is refused too`() = runTest {
        val repo = repository()
        var fetches = 0
        onFetch = { fetches++ }
        currentAccountKey.value = OTHER_ACCOUNT

        val result = repo.refreshSingleBetaStatus(session, "com.whatsapp")

        assertTrue(result.exceptionOrNull() is DataError.StaleSession)
        assertEquals(0, fetches)
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
    fun `re-enabling a watch restarts the reminder interval`() = runTest {
        repository(now = 500L).setWatching("com.whatsapp", watching = true)
        repository(now = 600L).setWatching("com.whatsapp", watching = false)

        repository(now = 9_000L).setWatching("com.whatsapp", watching = true)

        // The reminder clock restarts on re-activation, so the next reminder
        // waits a full interval instead of firing on the next daily run.
        assertEquals(9_000L, userDao.get("com.whatsapp")!!.lastRemindedAt)
    }

    @Test
    fun `changing the interval while watching does not restart the reminder clock`() = runTest {
        repository(now = 500L).setWatching("com.whatsapp", watching = true)

        repository(now = 900L).setWatching("com.whatsapp", watching = true, reminderIntervalDays = 14)

        assertEquals(500L, userDao.get("com.whatsapp")!!.lastRemindedAt)
    }

    @Test
    fun `an overlapping scan is rejected instead of running in parallel`() = runTest {
        val repo = repository()
        scanner.result = { listOf(app("com.a"), app("com.b")) }
        repo.refreshApps()
        pageHtml = { """<html><body><form id="joinForm"></form></body></html>""" }
        var active = 0
        var maxActive = 0
        onFetch = {
            active++
            maxActive = maxOf(maxActive, active)
            // A real suspension (yield() is a no-op on an unconfined dispatcher)
            // so the other scan gets a chance to interleave its fetches.
            delay(1)
            active--
        }

        // The manual and the periodic worker can fire simultaneously; both go
        // through this shared repository instance.
        val manual = async { repo.refreshBetaStatus(session, force = true) }
        val periodic = async { repo.refreshBetaStatus(session, cap = 30) }
        val manualResult = manual.await()
        val periodicResult = periodic.await()

        assertTrue(manualResult.isSuccess)
        // The loser is rejected outright: queueing it would re-hit Google with a
        // second full pass right after the first one finishes.
        assertTrue(periodicResult.exceptionOrNull() is DataError.ScanInProgress)
        assertEquals(1, maxActive)
    }

    @Test
    fun `a reminder preserves a note saved while its write is pending`() = runTest {
        val pkg = "example.app"
        val repo = repository(now = 900L)
        repo.setUserNote(pkg, "before").getOrThrow()
        userDao.beforeWrite = {
            userDao.beforeWrite = {}
            repo.setUserNote(pkg, "after").getOrThrow()
        }
        repo.markReminded(listOf(pkg)).getOrThrow()
        assertEquals("after", userDao.get(pkg)?.userNote)
        assertEquals(900L, userDao.get(pkg)?.lastRemindedAt)
    }

    @Test
    fun `competing scan cannot fetch between reading the cooldown and entering the lock`() = runTest {
        for (batchFirst in listOf(true, false)) {
            val read = kotlinx.coroutines.CompletableDeferred<Unit>()
            val proceed = kotlinx.coroutines.CompletableDeferred<Unit>()
            savedBlockedUntil = null
            val repo = repository(blockedUntil = {
                val snapshot = savedBlockedUntil
                if (!read.isCompleted) {
                    read.complete(Unit)
                    proceed.await()
                }
                snapshot
            })
            val pkg = "example.app"
            scanner.result = { listOf(app(pkg)) }
            repo.refreshApps().getOrThrow()
            rateLimitedPackages += pkg
            var fetches = 0
            onFetch = { fetches++ }
            val first = async {
                if (batchFirst) repo.refreshBetaStatus(session, force = true)
                else repo.refreshSingleBetaStatus(session, pkg)
            }
            read.await()
            if (batchFirst) repo.refreshSingleBetaStatus(session, pkg)
            else repo.refreshBetaStatus(session, force = true)
            proceed.complete(Unit)
            first.await()
            assertEquals(1, fetches)
            assertTrue(savedBlockedUntil != null)
        }
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
