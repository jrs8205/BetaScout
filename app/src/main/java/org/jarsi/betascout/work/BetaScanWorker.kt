package org.jarsi.betascout.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.jarsi.betascout.R
import org.jarsi.betascout.data.settings.LastScanInfo
import org.jarsi.betascout.data.settings.ScanType
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.BetaLinkBuilder
import org.jarsi.betascout.domain.DataError
import org.jarsi.betascout.domain.ScanSummary
import org.jarsi.betascout.domain.SlotOpenPolicy

/**
 * Runs a beta-status scan and notifies about watched apps whose testing program just
 * started accepting testers again. Two modes, selected via [KEY_MANUAL]:
 *
 * - Periodic (default): re-checks only the statuses due per ScanPolicy's TTLs, capped
 *   so a run stays gentle on the account. Skips silently when no one is signed in.
 * - Manual ("Scan now"): uncapped; incremental by default (new + stale apps),
 *   forced to re-check everything when [KEY_FORCE] is set. With the 3-second
 *   crawl delay a large forced run takes well over ten minutes, so the run is
 *   promoted to a foreground service — surviving the user leaving the app —
 *   and reports per-app progress.
 */
@HiltWorker
class BetaScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AppRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val session = settings.playSession.first()
            ?: return if (manual) {
                Result.failure(workDataOf(KEY_ERROR to "not_signed_in"))
            } else {
                Result.success()
            }

        if (manual) {
            // Best effort: without foreground promotion the scan still runs, it just
            // loses the exemption from the ~10-minute background execution limit —
            // logged so a long scan that later dies quietly stays diagnosable.
            runCatching { setForeground(createForegroundInfo()) }
                .onFailure { android.util.Log.w("BetaScout", "manual scan: setForeground failed", it) }
        }

        // The installed-app mirror is otherwise only refreshed by the list screen;
        // an app installed since then must still join this scan's set. A failure
        // falls back to the cached list — a slightly stale set beats no scan.
        repository.refreshApps().onFailure {
            android.util.Log.w("BetaScout", "scan: refreshApps failed, using cached app list", it)
        }

        val result = if (manual) {
            repository.refreshBetaStatus(session, force = force) { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_INDEX to progress.index,
                        KEY_PROGRESS_TOTAL to progress.total,
                        KEY_PROGRESS_LABEL to progress.currentLabel,
                    ),
                )
            }
        } else {
            repository.refreshBetaStatus(session, cap = SCAN_CAP)
        }
        val summary = result.getOrElse { e ->
            if (e is DataError.ScanInProgress) {
                // Another scan holds the lock: the periodic worker just skips this
                // slot (the next period retries); a manual tap gets told in the UI.
                return if (manual) {
                    Result.failure(workDataOf(KEY_ERROR to ERROR_SCAN_IN_PROGRESS))
                } else {
                    Result.success()
                }
            }
            // A manual run must surface its error in the UI instead of silently
            // retrying with the button stuck on busy.
            return if (manual) {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "scan_failed")))
            } else {
                Result.retry()
            }
        }

        if (summary.needsLogin) {
            // The session is dead: clear it so the account screen prompts a fresh
            // sign-in. The account screen shows the manual run's outcome directly;
            // a background run says so once via notification — later runs skip
            // because the session is gone.
            settings.clearPlaySession()
            if (!manual) BetaSlotNotifier(applicationContext).showReloginNeeded()
            return Result.success(workDataOf(KEY_NEEDS_LOGIN to true))
        }

        // A background run that found nothing due must not overwrite the last real
        // result; a manual run always reports, even "checked 0".
        if (manual || summary.checked > 0) {
            settings.saveLastScan(summary.toLastScanInfo(manual))
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

    private fun ScanSummary.toLastScanInfo(manual: Boolean) = LastScanInfo(
        at = System.currentTimeMillis(),
        checked = checked,
        joined = joined,
        notJoined = notJoined,
        failed = failed,
        noProgram = noProgram,
        failureReason = topFailureReason,
        scanType = if (manual) ScanType.MANUAL else ScanType.BACKGROUND,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private fun createForegroundInfo(): ForegroundInfo {
        val channel = NotificationChannel(
            SCAN_CHANNEL_ID,
            applicationContext.getString(R.string.scan_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.scan_channel_description)
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(applicationContext.getString(R.string.scan_notification_title))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SCAN_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SCAN_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MANUAL = "manual"
        const val KEY_FORCE = "force"
        const val KEY_NEEDS_LOGIN = "needs_login"
        const val KEY_ERROR = "error"

        /** [KEY_ERROR] value for a manual scan rejected because one is already running. */
        const val ERROR_SCAN_IN_PROGRESS = "scan_in_progress"
        const val KEY_PROGRESS_INDEX = "progress_index"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_LABEL = "progress_label"

        private const val SCAN_CHANNEL_ID = "beta_scan"
        private const val SCAN_NOTIFICATION_ID = 42

        /** Handoff default for background runs; manual scans stay uncapped. */
        private const val SCAN_CAP = 30
    }
}
