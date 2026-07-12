package org.jarsi.betascout.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jarsi.betascout.data.betadb.BetaSeedParser
import org.jarsi.betascout.data.crowd.DiscoveryReporter
import org.jarsi.betascout.data.betadb.BetaSeeder
import org.jarsi.betascout.data.betadb.CatalogProvider
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

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val SEED_ASSET = "beta_programs.json"
    private const val CATALOG_URL = "https://betascout-catalog.jarsi.workers.dev"
    private const val CATALOG_CACHE_FILE = "catalog_cache.json"

    /** GET the catalog; returns null on any failure so the caller can fall back. */
    private suspend fun fetchCatalog(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                if (connection.responseCode == 200) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** POST discovery hints; true only on a 2xx answer. Package names are plain
     *  `[A-Za-z0-9_.]` identifiers, so the JSON needs no escaping. */
    private suspend fun postHints(urlBase: String, packages: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val connection = (URL("$urlBase/hints").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    val body = """{"version":1,"packages":[${
                        packages.joinToString(",") { "\"$it\"" }
                    }]}"""
                    connection.outputStream.use { it.write(body.toByteArray()) }
                    connection.responseCode in 200..299
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                false
            }
        }

    @Provides
    @Singleton
    fun provideDiscoveryReporter(
        settings: SettingsRepository,
        betaObservationDao: BetaObservationDao,
        betaProgramDao: BetaProgramDao,
    ): DiscoveryReporter = DiscoveryReporter(
        shareEnabled = { settings.shareDiscoveries.first() },
        reportedPackages = { settings.reportedPackages.first() },
        markReported = { settings.addReportedPackages(it) },
        betaObservationDao = betaObservationDao,
        betaProgramDao = betaProgramDao,
        post = { packages -> postHints(CATALOG_URL, packages) },
        io = Dispatchers.IO,
    )

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

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
        scanner: PackageScanner,
        installedAppDao: InstalledAppDao,
        betaProgramDao: BetaProgramDao,
        betaObservationDao: BetaObservationDao,
        userBetaStatusDao: UserBetaStatusDao,
        settings: SettingsRepository,
    ): AppRepository = DefaultAppRepository(
        scanner = scanner,
        installedAppDao = installedAppDao,
        betaProgramDao = betaProgramDao,
        betaObservationDao = betaObservationDao,
        userBetaStatusDao = userBetaStatusDao,
        seeder = BetaSeeder(
            readSeedJson = CatalogProvider(
                fetchRemote = { fetchCatalog(CATALOG_URL) },
                readCache = {
                    File(context.filesDir, CATALOG_CACHE_FILE).takeIf { it.exists() }?.readText()
                },
                writeCache = { File(context.filesDir, CATALOG_CACHE_FILE).writeText(it) },
                readBundled = {
                    context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
                },
                // The catalog Worker answers a missing KV key with HTTP 200 and an
                // empty catalog, and the cache file can be corrupt: only a parseable,
                // non-empty catalog may replace the bundled seed (or be cached).
                isValid = { json ->
                    runCatching { BetaSeedParser.parse(json).isNotEmpty() }.getOrDefault(false)
                },
            )::catalogJson,
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
    )
}
