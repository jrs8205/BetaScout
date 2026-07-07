package org.jarsi.betascout.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BetaScanScheduler {

    const val WORK_NAME = "beta_status_scan"

    /** Enqueues the periodic status scan; KEEP preserves the existing schedule.
     *  Six hours balances catching short-lived beta openings against request volume:
     *  with ScanPolicy's TTLs a FULL program is still re-checked at most once a day. */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BetaScanWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }
}
