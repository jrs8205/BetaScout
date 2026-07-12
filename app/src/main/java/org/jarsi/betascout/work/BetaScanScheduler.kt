package org.jarsi.betascout.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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
     *  default incremental scan checks new apps and stale observations only. */
    fun scanNow(
        workManager: WorkManager,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        force: Boolean = false,
    ) {
        workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<BetaScanWorker>()
                .setInputData(
                    workDataOf(
                        BetaScanWorker.KEY_MANUAL to true,
                        BetaScanWorker.KEY_FORCE to force,
                    ),
                )
                .setConstraints(connected)
                .build(),
        )
    }
}
