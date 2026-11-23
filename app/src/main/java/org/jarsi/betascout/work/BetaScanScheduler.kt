package org.jarsi.betascout.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object BetaScanScheduler {

    const val WORK_NAME = "beta_status_scan"
    const val MANUAL_WORK_NAME = "manual_beta_scan"

    private val connected =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Enqueues the periodic status scan; KEEP preserves the existing schedule.
     *  Six hours balances catching short-lived beta openings against request volume:
     *  with ScanPolicy's TTLs a FULL program is still re-checked at most once a day. */
    fun schedule(context: Context) = schedule(WorkManager.getInstance(context))

    fun schedule(workManager: WorkManager) {
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BetaScanWorker>(6, TimeUnit.HOURS)
                .setConstraints(connected)
                .build(),
        )
    }

    /** Enqueues a user-initiated scan as unique work so it survives the user
     *  leaving the screen (or the app). KEEP means a second tap while one is running
     *  does nothing; REPLACE (after a fresh sign-in) supersedes a stale run and its
     *  recorded outcome. [force] ignores the freshness TTLs (full re-scan); the
     *  default incremental scan checks new apps and stale observations only.
     *  [recordEmptyRun] off (pull-to-refresh) keeps a run that found nothing due
     *  from replacing the last-scan summary. [appsRefreshedAt] is when the caller
     *  itself last re-read the installed apps, so the run can skip its own read
     *  while that is still fresh. [sessionGeneration] is the sign-in the request
     *  is made in ([org.jarsi.betascout.domain.PlaySession.generation]): the
     *  request carries it as a tag, so the run does not scan for a later
     *  sign-in, and a request that failed can be told whose it was. It is not
     *  optional: whoever cannot say which sign-in a request belongs to does not
     *  make one. */
    fun scanNow(
        workManager: WorkManager,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        force: Boolean = false,
        recordEmptyRun: Boolean = true,
        appsRefreshedAt: Long? = null,
        sessionGeneration: Long,
    ) {
        workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            policy,
            manualScanRequest(force, recordEmptyRun, appsRefreshedAt, sessionGeneration),
        )
    }

    /** The request [scanNow] enqueues. There is none without a sign-in to tag
     *  it with. */
    internal fun manualScanRequest(
        force: Boolean = false,
        recordEmptyRun: Boolean = true,
        appsRefreshedAt: Long? = null,
        sessionGeneration: Long,
    ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<BetaScanWorker>()
        .setInputData(
            workDataOf(
                BetaScanWorker.KEY_MANUAL to true,
                BetaScanWorker.KEY_FORCE to force,
                BetaScanWorker.KEY_RECORD_EMPTY to recordEmptyRun,
                BetaScanWorker.KEY_APPS_REFRESHED_AT to (appsRefreshedAt ?: 0L),
            ),
        )
        .setConstraints(connected)
        .addTag(BetaScanWorker.sessionTag(sessionGeneration))
        .build()
}
