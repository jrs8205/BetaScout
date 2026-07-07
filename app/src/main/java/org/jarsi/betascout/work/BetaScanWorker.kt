package org.jarsi.betascout.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.jarsi.betascout.data.settings.LastScanInfo
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.BetaLinkBuilder
import org.jarsi.betascout.domain.SlotOpenPolicy

/**
 * Periodic worker: re-checks the beta statuses that are due per ScanPolicy's TTLs
 * (capped, so a run stays gentle on the account) and notifies about watched apps
 * whose testing program just started accepting testers again. Skips silently when
 * no one is signed in.
 */
@HiltWorker
class BetaScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AppRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val session = settings.playSession.first() ?: return Result.success()
        val summary = repository.refreshBetaStatus(session, cap = SCAN_CAP)
            .getOrElse { return Result.retry() }

        if (summary.needsLogin) {
            // The session is dead: clear it so the account screen prompts a fresh
            // sign-in, and say so once — later runs skip because the session is gone.
            settings.clearPlaySession()
            BetaSlotNotifier(applicationContext).showReloginNeeded()
            return Result.success()
        }

        if (summary.checked > 0) {
            settings.saveLastScan(
                LastScanInfo(
                    at = System.currentTimeMillis(),
                    checked = summary.checked,
                    joined = summary.joined,
                    notJoined = summary.notJoined,
                ),
            )
        }

        val rows = repository.observeApps().first()
        val notifier = BetaSlotNotifier(applicationContext)
        SlotOpenPolicy.notifiable(summary.transitions, rows).forEach { row ->
            notifier.showSlotOpen(
                packageName = row.app.packageName,
                appLabel = row.app.label,
                testingUrl = row.betaProgram?.testingUrl
                    ?: BetaLinkBuilder.testingUrl(row.app.packageName),
            )
        }
        return Result.success()
    }

    private companion object {
        /** Handoff default for background runs; manual scans stay uncapped. */
        const val SCAN_CAP = 30
    }
}
