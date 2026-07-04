package org.jarsi.betascout.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.BetaLinkBuilder
import org.jarsi.betascout.domain.ReminderPolicy

/**
 * Periodic worker: reminds the user to re-check the beta status of watched apps.
 * Fully offline — all decisions come from local data via ReminderPolicy.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AppRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Only installed apps appear in the overview, so watched-but-uninstalled
        // apps never trigger reminders.
        val rows = repository.observeApps().first()
        val due = ReminderPolicy.dueForReminder(
            statuses = rows.mapNotNull { it.userStatus },
            now = System.currentTimeMillis(),
        )
        if (due.isEmpty()) return Result.success()

        val rowsByPackage = rows.associateBy { it.app.packageName }
        val notifier = ReminderNotifier(applicationContext)
        val notified = due.filter { status ->
            val row = rowsByPackage[status.packageName] ?: return@filter false
            notifier.showReminder(
                packageName = status.packageName,
                appLabel = row.app.label,
                testingUrl = row.betaProgram?.testingUrl
                    ?: BetaLinkBuilder.testingUrl(status.packageName),
            )
            true
        }

        if (notified.isNotEmpty() && notifier.canNotify()) {
            repository.markReminded(notified.map { it.packageName })
        }
        return Result.success()
    }
}
