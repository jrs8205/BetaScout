package org.jarsi.betascout.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.jarsi.betascout.data.betadb.BetaSeeder
import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.InstalledAppDao
import org.jarsi.betascout.data.db.UserBetaStatusDao
import org.jarsi.betascout.data.db.toDomain
import org.jarsi.betascout.data.db.toEntity
import org.jarsi.betascout.data.scanner.PackageScanner
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.DataError
import org.jarsi.betascout.domain.UserBetaState
import org.jarsi.betascout.domain.UserBetaStatusInfo

class DefaultAppRepository(
    private val scanner: PackageScanner,
    private val installedAppDao: InstalledAppDao,
    private val betaProgramDao: BetaProgramDao,
    private val userBetaStatusDao: UserBetaStatusDao,
    private val seeder: BetaSeeder,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) : AppRepository {

    override fun observeApps(): Flow<List<AppBetaOverview>> = combine(
        installedAppDao.observeAll(),
        betaProgramDao.observeAll(),
        userBetaStatusDao.observeAll(),
    ) { apps, programs, statuses ->
        val programsByPkg = programs.associateBy { it.packageName }
        val statusesByPkg = statuses.associateBy { it.packageName }
        apps.map { app ->
            AppBetaOverview(
                app = app.toDomain(),
                betaProgram = programsByPkg[app.packageName]?.toDomain(),
                userStatus = statusesByPkg[app.packageName]?.toDomain(),
            )
        }
    }

    override suspend fun ensureSeeded(): Result<Unit> = runCatchingData(::wrapLocal) {
        seeder.seed()
    }

    override suspend fun refreshApps(): Result<Unit> = runCatchingData(DataError::Scan) {
        val scanned = scanner.scan()
        installedAppDao.replaceAll(scanned.map { it.toEntity() })
    }

    override suspend fun setUserState(packageName: String, state: UserBetaState): Result<Unit> =
        updateStatus(packageName) { it.copy(state = state) }

    override suspend fun setWatching(
        packageName: String,
        watching: Boolean,
        reminderIntervalDays: Int?,
    ): Result<Unit> = updateStatus(packageName) {
        it.copy(
            watching = watching,
            reminderIntervalDays = reminderIntervalDays ?: it.reminderIntervalDays,
        )
    }

    override suspend fun setUserNote(packageName: String, note: String?): Result<Unit> =
        updateStatus(packageName) { it.copy(userNote = note) }

    override suspend fun markCheckedNow(packageName: String): Result<Unit> =
        updateStatus(packageName) { it.copy(lastCheckedByUser = clock()) }

    private suspend fun updateStatus(
        packageName: String,
        transform: (UserBetaStatusInfo) -> UserBetaStatusInfo,
    ): Result<Unit> = runCatchingData(::wrapLocal) {
        val current = userBetaStatusDao.get(packageName)?.toDomain()
            ?: UserBetaStatusInfo(packageName = packageName)
        userBetaStatusDao.upsert(transform(current).toEntity())
    }

    private fun wrapLocal(cause: Throwable): DataError = DataError.Local(cause)

    private suspend fun runCatchingData(
        wrap: (Throwable) -> DataError,
        block: suspend () -> Unit,
    ): Result<Unit> = withContext(io) {
        try {
            block()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DataError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(wrap(e))
        }
    }
}
