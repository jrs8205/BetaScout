package org.jarsi.betascout.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jarsi.betascout.R
import org.jarsi.betascout.data.crowd.DiscoveryReporter
import org.jarsi.betascout.data.settings.LastScanInfo
import org.jarsi.betascout.data.settings.ScanRefusal
import org.jarsi.betascout.data.settings.ScanType
import org.jarsi.betascout.data.settings.SessionRead
import org.jarsi.betascout.data.settings.boundedFailureReason
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.BetaLinkBuilder
import org.jarsi.betascout.domain.DataError
import org.jarsi.betascout.domain.ScanSummary
import org.jarsi.betascout.domain.SlotOpenPolicy

/** Whether a finished run replaces the persisted "last scan" summary. A run
 *  that attempted anything always does — [checked] counts successful pages only,
 *  so a run whose every request failed (a first HTTP 429, say) has zero checked
 *  but did happen, and its failure reason is what the card must show. A run that
 *  had nothing due does only when the user pressed the Scan button and looks at
 *  that card for the answer: an empty background run, or an empty
 *  pull-to-refresh ([recordEmptyRun] off), must not wipe the last real result. */
internal fun recordsLastScan(
    manual: Boolean,
    recordEmptyRun: Boolean,
    checked: Int,
    failed: Int,
): Boolean = checked > 0 || failed > 0 || manual && recordEmptyRun

/** Why a scan the user asked for did not run, as the code kept in its
 *  [ScanRefusal]: the two cases the screens have words of their own for, or
 *  else the error's message. */
internal fun refusalCodeOf(error: Throwable): String = when (error) {
    is DataError.ScanInProgress -> BetaScanWorker.ERROR_SCAN_IN_PROGRESS
    is DataError.ScanBlocked -> BetaScanWorker.ERROR_SCAN_BLOCKED
    // An exception message of whatever length: it is stored, shown, and may
    // have to travel in the work's result.
    else -> boundedFailureReason(error.message) ?: "scan_failed"
}

/** A queued manual request belongs to the sign-in that enqueued it.
 *  Requests from 0.6.9 lack a tag and can run only with the imported session. */
internal fun requestIsFor(requestedFor: Long?, generation: Long): Boolean =
    (requestedFor ?: 0L) == generation

/**
 * The highest order a requested scan left in its work result
 * ([BetaScanWorker.KEY_ORDER]), 0 if none did. A run whose outcome could not be
 * stored hands it to its result, order and all; the settings store never
 * learns of that order, so a later process has to be told of it from here
 * before it gives out orders of its own (see ScanSequence).
 */
internal fun highestOrderOnRecord(infos: List<WorkInfo>): Long =
    infos.maxOfOrNull { it.outputData.getLong(BetaScanWorker.KEY_ORDER, 0L) } ?: 0L

/** How often a run that could not read the stored session is started again. */
private const val UNREADABLE_SESSION_RETRIES = 3

/**
 * Whether a run that could not read the stored session ([attempt]: how many
 * times it has been tried before, WorkManager's count) is to be tried again.
 * A read that fails is usually a hiccup of the Keystore or the disk and works
 * the next time. One that keeps failing is, for this app, a session that is
 * gone — the screens treat it as signed out as well — and trying for ever
 * would leave a requested scan "starting" for good.
 */
internal fun retriesUnreadableSession(attempt: Int): Boolean = attempt < UNREADABLE_SESSION_RETRIES

/** How long after the list screen's own package read a run may rely on it. Long
 *  enough for WorkManager to start the run, far too short for an app to be
 *  installed in between without the list refreshing again on resume. */
private const val APPS_FRESH_WINDOW_MS = 60_000L

/** Whether the installed-app mirror was re-read so recently ([refreshedAt], epoch
 *  millis, 0 = never said) that this run need not read every package again. A
 *  run that waited — offline, the pull long past — reads for itself. */
internal fun appsMirrorIsFresh(refreshedAt: Long, now: Long): Boolean =
    refreshedAt > 0 && now - refreshedAt in 0..APPS_FRESH_WINDOW_MS

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
    private val discoveryReporter: DiscoveryReporter,
    private val reloginNotice: ReloginNotice,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val session = when (val read = readSession()) {
            is SessionRead.Present -> read.session
            // Nobody is signed in: there is no one to scan for, and no one to
            // tell (a queued request can outlive the session it was made in).
            SessionRead.None -> return endWithoutSession(manual, unreadable = false)
            // Not the same thing: the screens may be showing the user signed
            // in, and a requested scan that ended here would simply vanish.
            SessionRead.Unreadable -> return endWithoutSession(manual, unreadable = true)
        }
        if (manual && !requestIsFor(sessionOfTags(tags), session.generation)) {
            return endWithoutSession(manual, unreadable = false)
        }

        // Establish that older outcomes can be ordered before fetching any pages.
        // WorkManager will retry the same request if its history is temporarily unavailable.
        val priorOrder = settings.priorScanOrder {
            highestOrderOnRecord(
                WorkManager.getInstance(applicationContext)
                    .getWorkInfosForUniqueWorkFlow(BetaScanScheduler.MANUAL_WORK_NAME)
                    .first(),
            )
        } ?: return Result.retry()

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
        // A pull-to-refresh has just done this read itself and says so; reading
        // and rewriting every package a second time would be pure waste.
        val appsRefreshedAt = inputData.getLong(KEY_APPS_REFRESHED_AT, 0L)
        if (appsMirrorIsFresh(appsRefreshedAt, System.currentTimeMillis())) {
            android.util.Log.d("BetaScout", "scan: app list just refreshed by the list screen, not re-read")
        } else {
            repository.refreshApps().onFailure {
                android.util.Log.w("BetaScout", "scan: refreshApps failed, using cached app list", it)
            }
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
            // When it was refused: the repository's ticket, taken at the lock
            // decision itself. (Its own only for an error that carries none;
            // then at least before anything here suspends.)
            val refused = (e as? DataError)?.scanTicket?.takeIf { it != 0L } ?: settings.scanTicket()
            if (e is DataError.StaleSession) return endWithoutSession(manual, unreadable = false)
            if (e is DataError.UnreadableSession) return endWithoutSession(manual, unreadable = true)
            // The periodic worker keeps quiet: when another scan holds the lock
            // or Google's cooldown is on (retrying now, or through Result.retry()
            // backoff, would hit the block again) it skips this slot, and
            // anything else it retries. A run the user asked for records that
            // it did not run, and why — here, not in whatever screen may be
            // watching: this can be long after the app's process is gone.
            if (!manual) {
                val skipSlot = e is DataError.ScanInProgress || e is DataError.ScanBlocked
                return if (skipSlot) Result.success() else Result.retry()
            }
            val code = refusalCodeOf(e)
            val saved = recorded { settings.saveScanRefusal(session, code, refused, priorOrder) }
            return when (saved) {
                true -> Result.failure(ended(OUTCOME_RECORDED))
                // The session ended meanwhile: nobody left to tell.
                false -> Result.failure(ended(OUTCOME_QUIET))
                null -> Result.failure(unrecorded(code, refused, priorOrder))
            }
        }
        // When this run got through: the repository's ticket, taken as it let
        // go of the scan lock.
        val finished = summary.ticket.takeIf { it != 0L } ?: settings.scanTicket()

        if (summary.needsLogin) {
            // The session is dead: clear it, leaving the reason in the same store
            // so every screen can explain the sign-out — also on a later launch,
            // when this run finished with the app closed. Only the session this
            // run was made with: if the user signed out or in again meanwhile,
            // nothing is expired and nobody is told to sign in. A manual run is
            // shown on screen; a background run also says so once via
            // notification (whose tap opens the sign-in page). That it is owed
            // one is stored with the expiry itself, in the same edit: later
            // runs skip — the session is gone — so nothing would ever come
            // back to post a notice this run did not get posted.
            val expired = recorded { settings.expirePlaySession(session, tellByNotification = !manual) }
                // The store could not be written: the dead session is still
                // stored. A background run tries again; a requested one says
                // that it ended badly.
                ?: return if (manual) Result.failure(unrecorded(ERROR_UNRECORDED, finished, priorOrder)) else Result.retry()
            if (expired && !manual) {
                // Not posted from here: by now the user may have signed in
                // again (see ReloginNotice, which reads what is owed). Asked
                // a few times if the store does not answer, because this
                // process may not live much longer than this run. If it still
                // has not answered, the notice stays owed: the application's
                // watch shows it once the store reads again, or else the next
                // start of the process does.
                var asked = 1
                while (!reloginNotice.sync() && asked < SESSION_READ_TRIES) {
                    delay(SESSION_READ_PAUSE_MS)
                    asked++
                }
            }
            return Result.success()
        }

        // What this run leaves behind for the screens: that a scan got through,
        // and its summary if it has one to record. Nothing was due and no
        // summary — it still got through, and it is the newer event: an earlier
        // "did not run, try again" is over, and an earlier failure is no longer
        // the latest outcome. Written only while this run's session is still
        // the stored one.
        val recordEmptyRun = inputData.getBoolean(KEY_RECORD_EMPTY, true)
        val summarised = recordsLastScan(manual, recordEmptyRun, summary.checked, summary.failed)
        val saved = recorded {
            settings.saveScanDone(
                session,
                finished,
                summary = if (summarised) summary.toLastScanInfo(manual, session.accountKey) else null,
                ordersOnRecord = priorOrder,
            )
        }

        // What follows is extra. None of it may turn a run that got through —
        // and has said so — into a failed one.
        try {
            // Opt-in crowd hints; the reporter swallows every failure.
            discoveryReporter.reportAfterScan(session.accountKey)

            val rows = repository.getApps()
            val notifier = BetaSlotNotifier(applicationContext)
            SlotOpenPolicy.notifiable(summary.transitions, rows).forEach { row ->
                notifier.showSlotOpen(
                    packageName = row.app.packageName,
                    appLabel = row.app.label,
                    testingUrl = row.betaProgram?.testingUrl
                        ?: BetaLinkBuilder.testingUrl(row.app.packageName),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("BetaScout", "scan: follow-up after the run failed", e)
        }
        // "Succeeded" only means the run got through. Whether it achieved
        // anything is in the summary saved above, which is what the screens
        // read: it outlives this work's result and is replaced by whichever
        // scan comes next. If that record could not be written, a requested
        // run says so through the one channel left (see unrecordedFailure in
        // the scan view model); a background run has nobody waiting.
        return if (manual && saved == null) {
            Result.failure(unrecorded(ERROR_UNRECORDED, finished, priorOrder))
        } else {
            Result.success()
        }
    }

    /** [write], or null if the settings store could not be written. */
    private suspend fun <T : Any> recorded(write: suspend () -> T): T? = try {
        write()
    } catch (e: IOException) {
        android.util.Log.w("BetaScout", "scan: the outcome could not be stored", e)
        null
    }

    /** How a requested run that ends as failed says in which way. */
    private fun ended(outcome: String): Data = workDataOf(KEY_OUTCOME to outcome)

    /** The result of a requested run whose outcome could not be stored: the
     *  outcome itself, as far as a work result can carry it. */
    private suspend fun unrecorded(code: String, ticket: Long, priorOrder: Long): Data = workDataOf(
        KEY_OUTCOME to OUTCOME_UNRECORDED,
        KEY_ERROR to code,
        KEY_ORDER to (settings.scanOrder(ticket, priorOrder) ?: 0L),
    )

    /** The stored session, read with a little patience: a Keystore or disk
     *  hiccup is usually over within a moment. */
    private suspend fun readSession(): SessionRead {
        var read = settings.readPlaySession()
        var tries = 1
        while (read == SessionRead.Unreadable && tries < SESSION_READ_TRIES) {
            delay(SESSION_READ_PAUSE_MS)
            read = settings.readPlaySession()
            tries++
        }
        return read
    }

    /**
     * How a run ends that has no session to scan with. Nobody signed in
     * ([unreadable] false): quietly — the periodic slot is skipped, a queued
     * request has nobody left to answer to. A session that could not be read:
     * the run is started again a few times (see [retriesUnreadableSession]);
     * until then a requested scan stays visible as starting.
     */
    private fun endWithoutSession(manual: Boolean, unreadable: Boolean): Result = when {
        unreadable && retriesUnreadableSession(runAttemptCount) -> Result.retry()
        manual -> Result.failure(ended(OUTCOME_QUIET))
        else -> Result.success()
    }

    private fun ScanSummary.toLastScanInfo(manual: Boolean, accountKey: String) = LastScanInfo(
        at = System.currentTimeMillis(),
        checked = checked,
        joined = joined,
        notJoined = notJoined,
        failed = failed,
        noProgram = noProgram,
        failureReason = topFailureReason,
        scanType = if (manual) ScanType.MANUAL else ScanType.BACKGROUND,
        accountKey = accountKey,
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

        // Lets the user stop a long scan from the shade; per-app persistence means
        // cancelling loses nothing already checked.
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(applicationContext.getString(R.string.scan_notification_title))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(
                R.drawable.ic_stat_reminder,
                applicationContext.getString(R.string.scan_notification_cancel),
                cancelIntent,
            )
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

        /** Manual runs only; false = an empty run leaves the last-scan summary alone. */
        const val KEY_RECORD_EMPTY = "record_empty"

        /** Epoch millis of a package read the caller just finished, or absent. */
        const val KEY_APPS_REFRESHED_AT = "apps_refreshed_at"

        /** In the result of a requested run that ended as failed: in which way. */
        const val KEY_OUTCOME = "outcome"

        /** It recorded why in the settings store; that record is what counts. */
        const val OUTCOME_RECORDED = "recorded"

        /** It ended on purpose without a record: nobody was left to tell. */
        const val OUTCOME_QUIET = "quiet"

        /** Its outcome could not be stored; [KEY_ERROR] and [KEY_ORDER] carry
         *  it instead. (No outcome at all: the run broke off.) */
        const val OUTCOME_UNRECORDED = "unrecorded"
        const val KEY_ERROR = "error"
        const val KEY_ORDER = "order"

        /** Code for "ended badly, and nothing more is known". */
        const val ERROR_UNRECORDED = "unrecorded"

        private const val SESSION_TAG = "session:"

        /** The tag that ties a request to the sign-in it was made in. */
        fun sessionTag(generation: Long): String = SESSION_TAG + generation

        /** The sign-in a request's tags say it was made in, or null. */
        fun sessionOfTags(tags: Set<String>): Long? = tags
            .firstOrNull { it.startsWith(SESSION_TAG) }
            ?.removePrefix(SESSION_TAG)
            ?.toLongOrNull()

        /** Refusal code of a manual scan rejected because one is already running. */
        const val ERROR_SCAN_IN_PROGRESS = "scan_in_progress"

        /** Refusal code of a scan rejected during the post-429/403 cooldown. */
        const val ERROR_SCAN_BLOCKED = "scan_blocked"
        const val KEY_PROGRESS_INDEX = "progress_index"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_LABEL = "progress_label"

        /** Reads of the stored session per run, and the pause between them. */
        private const val SESSION_READ_TRIES = 3
        private const val SESSION_READ_PAUSE_MS = 300L

        private const val SCAN_CHANNEL_ID = "beta_scan"
        private const val SCAN_NOTIFICATION_ID = 42

        /** Handoff default for background runs; manual scans stay uncapped. */
        private const val SCAN_CAP = 30
    }
}
