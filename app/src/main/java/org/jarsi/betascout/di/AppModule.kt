package org.jarsi.betascout.di

import android.content.Context
import androidx.room.Room
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
import kotlinx.coroutines.withContext
import org.jarsi.betascout.data.betadb.BetaSeeder
import org.jarsi.betascout.data.betadb.CatalogProvider
import org.jarsi.betascout.data.scrape.BetaStatusScraper
import org.jarsi.betascout.data.scrape.HttpTestingPageSource
import org.jarsi.betascout.data.db.AppDatabase
import org.jarsi.betascout.data.db.BetaObservationDao
import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.MIGRATION_1_2
import org.jarsi.betascout.data.db.MIGRATION_2_3
import org.jarsi.betascout.data.db.InstalledAppDao
import org.jarsi.betascout.data.db.UserBetaStatusDao
import org.jarsi.betascout.data.repo.DefaultAppRepository
import org.jarsi.betascout.data.scanner.AndroidInstalledPackagesSource
import org.jarsi.betascout.data.scanner.DefaultPackageScanner
import org.jarsi.betascout.data.scanner.PackageScanner
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "betascout.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
            )::catalogJson,
            dao = betaProgramDao,
        ),
        scraper = BetaStatusScraper(
            source = HttpTestingPageSource(),
            clock = System::currentTimeMillis,
        ),
        io = Dispatchers.IO,
        clock = System::currentTimeMillis,
    )
}
