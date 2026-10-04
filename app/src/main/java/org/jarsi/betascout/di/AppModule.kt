package org.jarsi.betascout.di

import android.content.Context
import android.webkit.CookieManager
import androidx.room.Room
import androidx.work.WorkManager
import androidx.work.await
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jarsi.betascout.data.betadb.BetaSeedParser
import org.jarsi.betascout.data.crowd.DiscoveryReporter
import org.jarsi.betascout.data.betadb.BetaSeeder
import org.jarsi.betascout.data.betadb.CatalogProvider
import org.jarsi.betascout.data.betadb.writeTextAtomically
import org.jarsi.betascout.data.remote.CatalogWorkerClient
import org.jarsi.betascout.data.scrape.BetaStatusScraper
import org.jarsi.betascout.data.scrape.HttpTestingPageSource
import org.jarsi.betascout.data.db.AppDatabase
import org.jarsi.betascout.data.db.BetaObservationDao
import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.MIGRATION_1_2
import org.jarsi.betascout.data.db.MIGRATION_2_3
import org.jarsi.betascout.data.db.MIGRATION_3_4
import org.jarsi.betascout.data.db.MIGRATION_4_5
import org.jarsi.betascout.data.db.InstalledAppDao
import org.jarsi.betascout.data.db.UserBetaStatusDao
import org.jarsi.betascout.data.repo.DefaultAppRepository
import org.jarsi.betascout.data.scanner.AndroidInstalledPackagesSource
import org.jarsi.betascout.data.scanner.DefaultPackageScanner
import org.jarsi.betascout.data.scanner.PackageScanner
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.SignOutUseCase
import org.jarsi.betascout.work.BetaScanScheduler

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val SEED_ASSET = "beta_programs.json"
    private const val CATALOG_URL = "https://betascout-catalog.jarsi.workers.dev"
    private const val CATALOG_CACHE_FILE = "catalog_cache.json"

    @Provides
    @Singleton
    fun provideCatalogWorkerClient(): CatalogWorkerClient = CatalogWorkerClient(CATALOG_URL)

    @Provides
    @Singleton
    fun provideDiscoveryReporter(
        settings: SettingsRepository,
        betaObservationDao: BetaObservationDao,
        betaProgramDao: BetaProgramDao,
        catalogWorker: CatalogWorkerClient,
    ): DiscoveryReporter = DiscoveryReporter(
        shareEnabled = { settings.shareDiscoveries.first() },
        reportedPackages = { settings.reportedPackages.first() },
        markReported = { settings.addReportedPackages(it) },
        betaObservationDao = betaObservationDao,
        betaProgramDao = betaProgramDao,
        post = catalogWorker::postHints,
        io = Dispatchers.IO,
    )

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    fun provideSignOutUseCase(
        workManager: WorkManager,
        settings: SettingsRepository,
        repository: AppRepository,
    ): SignOutUseCase = SignOutUseCase(
        cancelScanWork = {
            workManager.cancelUniqueWork(BetaScanScheduler.MANUAL_WORK_NAME).await()
            workManager.cancelUniqueWork(BetaScanScheduler.WORK_NAME).await()
        },
        withScanLock = { repository.withScanLock(it) },
        clearObservations = { repository.clearAllObservations() },
        clearSession = settings::clearPlaySession,
        clearLastScan = settings::clearLastScan,
        clearWebViewCookies = {
            val cookieManager = CookieManager.getInstance()
            // removeAllCookies is asynchronous; resume only once the cookie store
            // confirms the removal so a login opened right after sign-out cannot
            // capture the old session's cookies.
            suspendCancellableCoroutine { continuation ->
                cookieManager.removeAllCookies { continuation.resume(Unit) }
            }
            cookieManager.flush()
        },
        rescheduleBackgroundScans = { BetaScanScheduler.schedule(workManager) },
    )

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "betascout.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun provideInstalledAppDao(db: AppDatabase): InstalledAppDao = db.installedAppDao()

    @Provides
    fun provideBetaProgramDao(db: AppDatabase): BetaProgramDao = db.betaProgramDao()

    @Provides
    fun provideBetaObservationDao(db: AppDatabase): BetaObservationDao = db.betaObservationDao()

    @Provides
    fun provideUserBetaStatusDao(db: AppDatabase): UserBetaStatusDao = db.userBetaStatusDao()

    @Provides
    @Singleton
    fun providePackageScanner(@ApplicationContext context: Context): PackageScanner =
        DefaultPackageScanner(
            source = AndroidInstalledPackagesSource(context.packageManager),
            ownPackageName = context.packageName,
            clock = System::currentTimeMillis,
        )

    @Provides
    @Singleton
    fun provideAppRepository(
        @ApplicationContext context: Context,
        catalogWorker: CatalogWorkerClient,
        scanner: PackageScanner,
        installedAppDao: InstalledAppDao,
        betaProgramDao: BetaProgramDao,
        betaObservationDao: BetaObservationDao,
        userBetaStatusDao: UserBetaStatusDao,
        settings: SettingsRepository,
    ): AppRepository {
        val catalogProvider = CatalogProvider(
            fetchRemote = catalogWorker::fetchCatalog,
            readCache = {
                File(context.filesDir, CATALOG_CACHE_FILE).takeIf { it.exists() }?.readText()
            },
            // Atomic: a process death mid-write must leave the previous cache,
            // not a truncated file that fails parsing and drags the catalog back
            // to the bundled seed on the next offline start.
            writeCache = { File(context.filesDir, CATALOG_CACHE_FILE).writeTextAtomically(it) },
            deleteCache = { File(context.filesDir, CATALOG_CACHE_FILE).delete() },
            readBundled = {
                context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
            },
            // The catalog Worker answers a missing KV key with HTTP 200 and an
            // empty catalog, and the cache file can be corrupt: only a parseable,
            // non-empty catalog may be mirrored (or cached).
            parse = { json, source ->
                runCatching { BetaSeedParser.parse(json, source) }.getOrNull()?.takeIf { it.isNotEmpty() }
            },
            clock = System::currentTimeMillis,
        )
        return DefaultAppRepository(
            scanner = scanner,
            installedAppDao = installedAppDao,
            betaProgramDao = betaProgramDao,
            betaObservationDao = betaObservationDao,
            userBetaStatusDao = userBetaStatusDao,
            seeder = BetaSeeder(
                readCatalog = catalogProvider::catalog,
                markApplied = catalogProvider::markApplied,
                dao = betaProgramDao,
            ),
            scraper = BetaStatusScraper(
                source = HttpTestingPageSource(),
                clock = System::currentTimeMillis,
            ),
            // distinctUntilChanged avoids re-decrypting the cookie and re-filtering the
            // whole observation list on every unrelated DataStore emission.
            currentAccountKey = settings.playSession.map { it?.accountKey }.distinctUntilChanged(),
            io = Dispatchers.IO,
            clock = System::currentTimeMillis,
            scanBlockedUntil = { settings.scanBlockedUntil.first() },
            setScanBlockedUntil = settings::setScanBlockedUntil,
        )
    }
}
