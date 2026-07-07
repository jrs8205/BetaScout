package org.jarsi.betascout.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.jarsi.betascout.data.betadb.BetaSeeder
import org.jarsi.betascout.data.db.BetaObservationDao
import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.InstalledAppDao
import org.jarsi.betascout.data.db.UserBetaStatusDao
import org.jarsi.betascout.data.db.toDomain
import org.jarsi.betascout.data.db.toEntity
import org.jarsi.betascout.data.scanner.PackageScanner
import org.jarsi.betascout.data.scrape.BetaStatusScraper
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.DataError
import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.domain.ScanCandidate
import org.jarsi.betascout.domain.ScanPolicy
import org.jarsi.betascout.domain.ScanProgress
import org.jarsi.betascout.domain.ScanSummary
import org.jarsi.betascout.domain.StatusTransition
import org.jarsi.betascout.domain.UserBetaState
import org.jarsi.betascout.domain.UserBetaStatusInfo

class DefaultAppRepository(
    private val scanner: PackageScanner,
    private val installedAppDao: InstalledAppDao,
    private val betaProgramDao: BetaProgramDao,
    private val betaObservationDao: BetaObservationDao,
    private val userBetaStatusDao: UserBetaStatusDao,
    private val seeder: BetaSeeder,
    private val scraper: BetaStatusScraper,
    private val currentAccountKey: Flow<String?>,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) : AppRepository {

    override fun observeApps(): Flow<List<AppBetaOverview>> = combine(
        installedAppDao.observeAll(),
        betaProgramDao.observeAll(),
        betaObservationDao.observeAll(),
        userBetaStatusDao.observeAll(),
        currentAccountKey,
    ) { apps, programs, observations, statuses, accountKey ->
        val programsByPkg = programs.associateBy { it.packageName }
        // Only the signed-in account's observations are surfaced; when signed out
        // (accountKey null) none are shown.
        val observationsByPkg = if (accountKey == null) {
            emptyMap()
        } else {
            observations.filter { it.accountKey == accountKey }.associateBy { it.packageName }
        }
        val statusesByPkg = statuses.associateBy { it.packageName }
        apps.map { app ->
            AppBetaOverview(
                app = app.toDomain(),
                betaProgram = programsByPkg[app.packageName]?.toDomain(),
                userStatus = statusesByPkg[app.packageName]?.toDomain(),
                observation = observationsByPkg[app.packageName]?.toDomain(),
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

    override suspend fun clearObservations(accountKey: String): Result<Unit> =
        runCatchingData(::wrapLocal) {
            betaObservationDao.deleteForAccount(accountKey)
        }

    override suspend fun refreshBetaStatus(
        session: PlaySession,
        cap: Int?,
        force: Boolean,
        onProgress: suspend (ScanProgress) -> Unit,
    ): Result<ScanSummary> = withContext(io) {
        try {
            android.util.Log.d(TAG, "refreshBetaStatus: start force=$force")
            // One-shot suspend queries, NOT observeAll().first(): a flow's initial
            // emission can be lost to an invalidation-tracker race, after which
            // first() suspends forever because nothing rewrites these tables.
            // Preinstalled apps carry the system flag but still have beta programs
            // (Chrome, Gmail…), so the scope is every app with a launcher entry
            // plus everything the user installed.
            val installed = installedAppDao.getAll().filter { !it.isSystem || it.hasLauncher }
            android.util.Log.d(TAG, "refreshBetaStatus: installed=${installed.size}")
            val observed = betaObservationDao.getAllForAccount(session.accountKey)
                .associateBy { it.packageName }
            android.util.Log.d(TAG, "refreshBetaStatus: observed=${observed.size}")
            val candidates = installed.map { app ->
                val previous = observed[app.packageName]
                ScanCandidate(
                    packageName = app.packageName,
                    lastStatus = previous?.liveStatus ?: LiveBetaStatus.UNKNOWN,
                    checkedAt = previous?.checkedAt,
                )
            }
            val due = ScanPolicy
                .selectDue(candidates, clock(), cap = cap ?: candidates.size, ignoreTtl = force)
                .map { it.packageName }
            android.util.Log.d(TAG, "refreshBetaStatus: due=${due.size} $due")
            val labels = installed.associate { it.packageName to it.label }
            val outcome = scraper.scrape(due, session) { index, total, packageName ->
                onProgress(ScanProgress(index, total, labels[packageName] ?: packageName))
            }
            android.util.Log.d(
                TAG,
                "refreshBetaStatus: scraped=${outcome.observations.size} needsLogin=${outcome.needsLogin}",
            )
            // A transition only exists where a previous observation is overwritten;
            // a first sighting is not a change and must not fire notifications.
            val transitions = outcome.observations.mapNotNull { observation ->
                val previous = observed[observation.packageName] ?: return@mapNotNull null
                if (previous.liveStatus == observation.liveStatus) return@mapNotNull null
                StatusTransition(
                    packageName = observation.packageName,
                    from = previous.liveStatus,
                    to = observation.liveStatus,
                )
            }
            outcome.observations.forEach { betaObservationDao.upsert(it.toEntity()) }
            val accountObservations = betaObservationDao.getAllForAccount(session.accountKey)
            Result.success(
                ScanSummary(
                    checked = outcome.observations.size,
                    joined = accountObservations
                        .count { it.observedMembership == ObservedMembership.JOINED },
                    notJoined = accountObservations
                        .count { it.observedMembership == ObservedMembership.NOT_JOINED },
                    needsLogin = outcome.needsLogin,
                    transitions = transitions,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(DataError.Local(e))
        }
    }

    override suspend fun setWatching(
        packageName: String,
        watching: Boolean,
        reminderIntervalDays: Int?,
    ): Result<Unit> = updateStatus(packageName) {
        it.copy(
            watching = watching,
            reminderIntervalDays = reminderIntervalDays ?: it.reminderIntervalDays,
            // Enabling the watch starts the reminder clock so the first
            // reminder arrives a full interval later, not immediately.
            lastRemindedAt = if (watching && it.lastRemindedAt == null) clock() else it.lastRemindedAt,
        )
    }

    override suspend fun setUserNote(packageName: String, note: String?): Result<Unit> =
        updateStatus(packageName) { it.copy(userNote = note) }

    override suspend fun markCheckedNow(packageName: String): Result<Unit> =
        updateStatus(packageName) { it.copy(lastCheckedByUser = clock()) }

    override suspend fun markReminded(packageNames: List<String>): Result<Unit> =
        runCatchingData(::wrapLocal) {
            val now = clock()
            packageNames.forEach { packageName ->
                val current = userBetaStatusDao.get(packageName)?.toDomain()
                    ?: UserBetaStatusInfo(packageName = packageName)
                userBetaStatusDao.upsert(current.copy(lastRemindedAt = now).toEntity())
            }
        }

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

    private companion object {
        const val TAG = "BetaScout"
    }
}
