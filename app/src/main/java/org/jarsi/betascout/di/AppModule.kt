package org.jarsi.betascout.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import org.jarsi.betascout.data.betadb.BetaSeeder
import org.jarsi.betascout.data.db.AppDatabase
import org.jarsi.betascout.data.db.BetaProgramDao
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "betascout.db").build()

    @Provides
    fun provideInstalledAppDao(db: AppDatabase): InstalledAppDao = db.installedAppDao()

    @Provides
    fun provideBetaProgramDao(db: AppDatabase): BetaProgramDao = db.betaProgramDao()

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
        userBetaStatusDao: UserBetaStatusDao,
    ): AppRepository = DefaultAppRepository(
        scanner = scanner,
        installedAppDao = installedAppDao,
        betaProgramDao = betaProgramDao,
        userBetaStatusDao = userBetaStatusDao,
        seeder = BetaSeeder(
            readSeedJson = {
                context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
            },
            dao = betaProgramDao,
        ),
        io = Dispatchers.IO,
        clock = System::currentTimeMillis,
    )
}
