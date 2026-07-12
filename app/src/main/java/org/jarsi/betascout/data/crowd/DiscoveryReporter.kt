package org.jarsi.betascout.data.crowd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jarsi.betascout.data.db.BetaObservationDao
import org.jarsi.betascout.data.db.BetaObservationEntity
import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.LiveBetaStatus

/** Statuses that prove the scan saw a real testing-program page. */
private val DISCOVERY_STATUSES =
    setOf(LiveBetaStatus.OPEN, LiveBetaStatus.FULL, LiveBetaStatus.CLOSED)

/** The worker rejects requests with more than 50 packages. */
private const val MAX_BATCH = 50

/**
 * Account-neutral discovery facts: packages whose observation proves a testing
 * program exists, minus what the catalog already knows and what was already
 * uploaded. Membership is never read here and never uploaded — a JOINED
 * observation contributes only because its liveStatus shows a program page.
 */
fun selectDiscoveries(
    observations: List<BetaObservationEntity>,
    catalogPackages: Set<String>,
    reported: Set<String>,
): List<String> = observations
    .filter { it.liveStatus in DISCOVERY_STATUSES }
    .map { it.packageName }
    .filter { it !in catalogPackages && it !in reported }
    .sorted()

/**
 * Uploads opt-in discovery hints after a scan. Fire-and-forget by contract:
 * every failure is logged and swallowed so the upload can never affect scan
 * results or UX, and the reported-set only advances on a confirmed (2xx)
 * upload so failures retry naturally after the next scan.
 */
class DiscoveryReporter(
    private val shareEnabled: suspend () -> Boolean,
    private val reportedPackages: suspend () -> Set<String>,
    private val markReported: suspend (Set<String>) -> Unit,
    private val betaObservationDao: BetaObservationDao,
    private val betaProgramDao: BetaProgramDao,
    private val post: suspend (List<String>) -> Boolean,
    private val io: CoroutineDispatcher,
) {

    suspend fun reportAfterScan(accountKey: String) = withContext(io) {
        try {
            if (!shareEnabled()) return@withContext
            val catalogPackages = betaProgramDao.getAll()
                // A user-created row is the user's own marking, not catalog
                // knowledge — it must not hide a discovery from the crowd.
                .filter { it.source != BetaSource.USER }
                .map { it.packageName }
                .toSet()
            val candidates = selectDiscoveries(
                observations = betaObservationDao.getAllForAccount(accountKey),
                catalogPackages = catalogPackages,
                reported = reportedPackages(),
            )
            // Chunked under the worker's request cap; each accepted chunk is
            // marked on its own so a failed one retries without resending the rest.
            candidates.chunked(MAX_BATCH).forEach { batch ->
                if (post(batch)) markReported(batch.toSet())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.d("BetaScout", "discovery report failed: $e")
        }
    }
}
