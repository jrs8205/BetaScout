package org.jarsi.betascout.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jarsi.betascout.R
import org.jarsi.betascout.data.settings.LastScanInfo
import org.jarsi.betascout.data.settings.ScanDone
import org.jarsi.betascout.data.settings.ScanRefusal
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.domain.ScanProgress
import org.jarsi.betascout.work.BetaScanScheduler
import org.jarsi.betascout.work.BetaScanWorker

/**
 * What the screens know about scanning.
 *
 * Only what is happening right now ([busy], [progress], …) comes from watching
 * the work. Everything about how a scan ended — [lastScan], [refusal],
 * [needsLogin] — is read from the settings store, where the run itself wrote
 * it: a run can end with the app closed, with another screen in front, or
 * before anyone was watching, and none of that may decide whether the user is
 * told.
 */
data class ScanUiState(
    val signedIn: Boolean = false,
    /** The signed-in account, or null. A pull is tied to it (see [mayStartPullScan]). */
    val accountKey: String? = null,
    /** Which sign-in that is ([PlaySession.generation]), or null when nobody
     *  is known to be signed in. A pull is tied to this too. */
    val sessionGeneration: Long? = null,
    val busy: Boolean = false,
    /** A scan asked for from the app is in flight: waiting to start (for a
     *  connection, say) or running. */
    val requested: Boolean = false,
    /** A cancelled run is still unwinding: controls stay disabled, no progress row. */
    val cancelling: Boolean = false,
    /** The periodic background worker is scanning (no manual run in flight). */
    val backgroundScanning: Boolean = false,
    val progress: ScanProgress? = null,
    /** The signed-in account's latest recorded scan (see [visibleLastScan]). */
    val lastScan: LastScanInfo? = null,
    /** The signed-in account's latest scan request that did not run (see
     *  [visibleRefusal]). */
    val refusal: ScanRefusal? = null,
    /** The signed-in account's latest scan that got through (see [visibleDone]). */
    val done: ScanDone? = null,
    /** The session was dropped because Google stopped accepting it, and the user
     *  has not signed in (or out) since. */
    val needsLogin: Boolean = false,
    /** Google's rate-limit cooldown is running right now. */
    val blocked: Boolean = false,
    /** Goes up whenever this view model sees scan activity: a scan beginning —
     *  manual or background, started from any screen — or one being cancelled
     *  from here. A pull-to-refresh remembers the round it was made in and only
     *  enqueues its scan if that is still the round when its list refresh is
     *  done (see [mayStartPullScan]). */
    val scanRound: Int = 0,
)

/** A run that got through but checked nothing successfully: [failed] pages
 *  failed, most of them for [reason] (when one is known). */
data class ScanRunFailure(val failed: Int, val reason: String?)

/** One thing worth telling the user about scanning. */
sealed interface ScanNotice {
    /** The scan could not run; [code] is an error code of [BetaScanWorker]. */
    data class Error(val code: String) : ScanNotice

    /** The latest scan ran and every check failed. */
    data class RunFailed(val failure: ScanRunFailure) : ScanNotice

    /** The requested scan did not run. Past tense and without a reason: true
     *  for as long as it is shown, whatever has changed since. */
    data object NotStarted : ScanNotice

    /** Another scan is running right now; only said while it is. */
    data object OtherScanRunning : ScanNotice
}

/** This scan as a failure, or null. Zero checked with zero failed is a run that
 *  had nothing due — a success. Some checked with some failed did update the
 *  statuses; its details belong to the settings card. */
internal fun LastScanInfo.asRunFailure(): ScanRunFailure? =
    if (checked == 0 && failed > 0) ScanRunFailure(failed, failureReason) else null

/**
 * The stored last-scan summary as far as the account [accountKey] (null = signed
 * out) may see it: only one recorded for that very account. A summary without
 * an owner — stored by a version that did not record one — is shown to nobody:
 * being signed in proves nothing about whose it was, since the previous
 * account's summary used to survive its session and the next sign-in. It is
 * replaced by the first scan recorded for the signed-in account.
 */
internal fun visibleLastScan(raw: LastScanInfo?, accountKey: String?): LastScanInfo? =
    raw?.takeIf { accountKey != null && it.accountKey == accountKey }

/** The stored refusal as far as the account [accountKey] may see it: its own. */
internal fun visibleRefusal(raw: ScanRefusal?, accountKey: String?): ScanRefusal? =
    raw?.takeIf { accountKey != null && it.accountKey == accountKey }

/** The stored latest-scan record as far as [accountKey] may see it: its own. */
internal fun visibleDone(raw: ScanDone?, accountKey: String?): ScanDone? =
    raw?.takeIf { accountKey != null && it.accountKey == accountKey }

/** What WorkManager still knows of a requested scan that ended as failed. */
internal data class FailedRequest(
    /** The sign-in the request was made in ([PlaySession.generation]), if tagged. */
    val sessionGeneration: Long?,
    /** How the run said it ended ([BetaScanWorker.KEY_OUTCOME]); null if it
     *  said nothing — it broke off without getting that far. */
    val outcome: String?,
    val code: String?,
    /** Its place among scan events; 0 if not known. */
    val order: Long,
)

internal fun WorkInfo.asFailedRequest(): FailedRequest? {
    if (state != WorkInfo.State.FAILED) return null
    return FailedRequest(
        sessionGeneration = BetaScanWorker.sessionOfTags(tags),
        outcome = outputData.getString(BetaScanWorker.KEY_OUTCOME),
        code = outputData.getString(BetaScanWorker.KEY_ERROR),
        order = outputData.getLong(BetaScanWorker.KEY_ORDER, 0L),
    )
}

/**
 * The stand-in for an outcome that never reached the settings store, or null.
 *
 * A run normally records how it ended and the screens read that. But the record
 * is a write, and a write can fail; or the run can break off before it gets
 * that far. WorkManager then lists the request as failed and nothing else
 * exists — the progress strip would just go away. For exactly that case what
 * WorkManager knows is shown instead, as a refusal:
 * - only of a request made in the session now signed in ([session]);
 * - not of a run that says it recorded its outcome, or that ended on purpose
 *   without one (nobody left to tell);
 * - and not once a scan of the account has got through after it ([done]), when
 *   its place in the order is known.
 * It lasts as long as WorkManager keeps the failed request: until the next
 * request replaces it.
 */
internal fun unrecordedFailure(
    failed: List<FailedRequest>,
    session: PlaySession?,
    done: ScanDone?,
): ScanRefusal? {
    if (session == null) return null
    val request = failed
        .filter {
            it.sessionGeneration == session.generation ||
                (it.sessionGeneration == null && session.generation == 0L &&
                    it.outcome == BetaScanWorker.OUTCOME_UNRECORDED)
        }
        .filter {
            it.outcome != BetaScanWorker.OUTCOME_RECORDED && it.outcome != BetaScanWorker.OUTCOME_QUIET
        }
        .maxByOrNull { it.order }
        ?: return null
    val answered = done != null &&
        done.accountKey == session.accountKey &&
        request.order != 0L &&
        done.order > request.order
    if (answered) return null
    return ScanRefusal(
        code = request.code ?: BetaScanWorker.ERROR_UNRECORDED,
        accountKey = session.accountKey,
        order = request.order,
    )
}

/** Of the stored refusal and the stand-in for one, the one to show: the later. */
internal fun shownRefusal(stored: ScanRefusal?, unrecorded: ScanRefusal?): ScanRefusal? =
    listOfNotNull(stored, unrecorded).maxByOrNull { it.order }

/** This state for [account] (the signed-in account, null = nobody), showing of
 *  the stored outcomes what that account may see; [unrecorded] is the stand-in
 *  of [unrecordedFailure], already the signed-in session's. */
internal fun ScanUiState.withOutcomesOf(
    account: String?,
    lastScan: LastScanInfo?,
    refusal: ScanRefusal?,
    done: ScanDone?,
    unrecorded: ScanRefusal? = null,
): ScanUiState = copy(
    accountKey = account,
    lastScan = visibleLastScan(lastScan, account),
    refusal = shownRefusal(visibleRefusal(refusal, account), unrecorded),
    done = visibleDone(done, account),
)

/**
 * What [ScanUiState.refusal] is worth saying right now, or null.
 *
 * A stored refusal is one no scan has answered yet: the store drops it when a
 * later scan gets through (see ScanOutcomes), so nothing is compared here —
 * least of all clock times. It is reported in words that are true now:
 * - Nothing while a new request is in flight ([ScanUiState.requested]). The
 *   user has asked again, and that request — waiting for a connection, or
 *   running — is what the screen is about; read against it, an old "lost the
 *   lock" would turn into "a scan is running" while nothing scans at all.
 * - Lost the lock: "a scan is running" ([ScanNotice.OtherScanRunning]) only
 *   while one visibly is; after that, or when the lock was held by something
 *   that never shows as a running scan (a single app's re-check),
 *   [ScanNotice.NotStarted].
 * - Rate limited: the "right now" text only while the cooldown lasts; after it,
 *   [ScanNotice.NotStarted] — the scan the user asked for still did not run.
 */
internal fun ScanUiState.refusalNotice(): ScanNotice? {
    val refused = refusal ?: return null
    if (requested) return null
    return when (refused.code) {
        BetaScanWorker.ERROR_SCAN_IN_PROGRESS ->
            if (busy) ScanNotice.OtherScanRunning else ScanNotice.NotStarted
        BetaScanWorker.ERROR_SCAN_BLOCKED ->
            if (blocked) ScanNotice.Error(refused.code) else ScanNotice.NotStarted
        else -> ScanNotice.Error(refused.code)
    }
}

/**
 * The stored summary as a failure of the latest scan, or null. "Latest" is the
 * point: once a later scan has got through — even one with nothing due, which
 * leaves the stored summary alone — that failure is history. It stays in the
 * settings card. On the main screen it would follow the user's own successful
 * refresh with the very error that was there before, and with a promise of a
 * retry that has just happened.
 */
internal fun ScanUiState.latestRunFailure(): ScanRunFailure? {
    val summary = lastScan ?: return null
    val latest = done
    if (latest != null && latest.order > summary.order) return null
    return summary.asRunFailure()
}

/**
 * The main screen's line about scanning, or null.
 * - Nothing to a signed-out user: an outcome is the signed-in account's.
 * - Nothing while a scan runs: the progress strip is on screen then and an
 *   older outcome under it would contradict it.
 * - Otherwise a scan that was asked for and did not run, or else the latest
 *   scan if every one of its checks failed (see [latestRunFailure]).
 */
internal fun ScanUiState.mainScreenNotice(): ScanNotice? {
    if (!signedIn) return null
    if (busy) return null
    return refusalNotice() ?: latestRunFailure()?.let { ScanNotice.RunFailed(it) }
}

/** Whether Google's rate-limit cooldown, stored as the wall-clock time it ends
 *  ([blockedUntil]), is still running at [now]. Always judged against the wall
 *  clock, as the scan's own decision is. */
internal fun cooldownActive(blockedUntil: Long?, now: Long): Boolean =
    blockedUntil != null && now < blockedUntil

/** Whether a pull may enqueue its scan. [atPull] is the state when the gesture
 *  fired, [atEnqueue] the state once the pull's own list refresh has finished,
 *  which can be seconds later. Both must allow a scan — and nothing may have
 *  happened in between: a scan that was started and cancelled while the pull
 *  waited leaves both snapshots looking free, and the pull would put a new scan
 *  in the place of the one the user just cancelled. So the pull is tied to the
 *  scan round it was made in — and to the account it was made as: whoever is
 *  signed in when the list refresh ends did not ask for this scan. And to the
 *  sign-in itself: the same account signing in again starts a scan of its own,
 *  and until WorkManager reports that scan the round has not moved; the old
 *  pull's request would be tagged with the new sign-in and pass for one made
 *  in it. A pull made before any sign-in is known here asks for nothing. */
internal fun mayStartPullScan(atPull: ScanUiState, atEnqueue: ScanUiState): Boolean =
    atPull.canStartQuickScan() &&
        atEnqueue.canStartQuickScan() &&
        atPull.scanRound == atEnqueue.scanRound &&
        atPull.accountKey == atEnqueue.accountKey &&
        atPull.sessionGeneration != null &&
        atPull.sessionGeneration == atEnqueue.sessionGeneration

/**
 * Reads WorkManager's reports about the manual scan work: whether a run is
 * under way, and when a new scan round begins. That is all they are used for —
 * how a run ended is recorded by the run (see [ScanUiState]).
 *
 * WorkManager lists every generation still on record, in no order, and its
 * updates are conflated — a run can go from requested to finished between two
 * of them. So:
 * - The first report is history. Runs it lists as finished ended before this
 *   view model looked; they are not scans that began. A run under way in it is.
 * - After that, any generation not seen before is a scan that began, whether or
 *   not it was ever seen running.
 */
internal class ScanWorkTracker {
    private var known: Set<UUID>? = null

    /** What one report means: the run under way, if any, and whether a scan
     *  began since the previous report. */
    data class Report(val active: WorkInfo?, val newRound: Boolean)

    fun onReport(infos: List<WorkInfo>): Report {
        val before = known
        val active = infos.firstOrNull { !it.state.isFinished }
        val newRound = if (before == null) active != null else infos.any { it.id !in before }
        known = (before ?: emptySet()) + infos.map { it.id }
        return Report(active, newRound)
    }
}

/** The dedicated explanation for a scan error code of [BetaScanWorker], or null
 *  when only the generic "Failed: …" text applies. Shared by the settings card
 *  and the main screen so both say the same thing. */
internal fun scanErrorRes(error: String): Int? = when (error) {
    BetaScanWorker.ERROR_SCAN_IN_PROGRESS -> R.string.account_scan_in_progress
    BetaScanWorker.ERROR_SCAN_BLOCKED -> R.string.account_scan_blocked
    BetaScanWorker.ERROR_UNRECORDED -> R.string.scan_unrecorded
    else -> null
}

/** Whether a pull-to-refresh may start the incremental scan. Not while signed
 *  out (the worker has nobody to scan for) and not alongside any other
 *  scan — a manual run that loses the lock to the periodic worker is refused.
 *  [busy] covers every run holding the lock (manual, background, still
 *  cancelling); [backgroundScanning] also covers the periodic worker's first
 *  moments, when it is running but has not taken the lock yet. */
internal fun ScanUiState.canStartQuickScan(): Boolean =
    signedIn && !busy && !backgroundScanning

/**
 * Whether a scan can be asked for by button. Not on top of a scan in flight or
 * one still unwinding — and only once this view model has itself read who is
 * signed in ([ScanUiState.signedIn]). A request is tied to the sign-in it is
 * made in; the account screen shows the buttons as soon as its own view model
 * has read the session, which can be a moment before this one has, and a
 * request made in that moment would belong to nobody: it would be run for
 * whoever is signed in when it starts, and its failure shown to no one.
 */
internal fun ScanUiState.canRequestScan(): Boolean = signedIn && !busy && !cancelling

/**
 * Scan state shared by the main screen and the account screen. The scan itself
 * runs as WorkManager work (it outlives any screen and the whole app process);
 * this mirrors that work's progress plus the repository's scan lock —
 * WorkManager reports CANCELLED before a cancelled run has actually released
 * the lock — and reads the outcomes the runs stored.
 */
@HiltViewModel
class ScanStatusViewModel @Inject constructor(
    settings: SettingsRepository,
    repository: AppRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state = _state.asStateFlow()

    /** WorkManager reports an active scan job; the actual lock may outlive it. */
    private val workBusy = MutableStateFlow(false)

    private val manualWork = ScanWorkTracker()

    /** The stored outcomes and the signed-in account, as last read; together
     *  they decide what [ScanUiState.lastScan] and [ScanUiState.refusal] show. */
    private var storedLastScan: LastScanInfo? = null
    private var storedRefusal: ScanRefusal? = null
    private var storedDone: ScanDone? = null

    /** The session now signed in. (Not named like anything in [ScanUiState]:
     *  inside an extension on the state such a name would mean the state's own
     *  value.) */
    private var signedInSession: PlaySession? = null

    /** The failed requests WorkManager lists, as last reported. */
    private var failedRequests: List<FailedRequest> = emptyList()

    /** The periodic worker is RUNNING. It shares the scan lock with manual runs, so
     *  without tracking it a background scan would read as "!work && lock" — the
     *  exact signature of a cancelled manual run — and show up as "Cancelling…". */
    private val backgroundRunning = MutableStateFlow(false)

    /** The scan lock as last seen, to notice the moment it is taken. */
    private var lockHeld = false

    /** Bumped by [recheckCooldown] to make the cooldown watcher look again. */
    private val cooldownRecheck = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            settings.playSession.collect { session ->
                signedInSession = session
                _state.update {
                    it.copy(signedIn = session != null, sessionGeneration = session?.generation)
                        .withOwnOutcomes()
                }
            }
        }
        viewModelScope.launch {
            settings.sessionExpired.collect { expired ->
                _state.update { it.copy(needsLogin = expired) }
            }
        }
        viewModelScope.launch {
            settings.lastScan.collect { last ->
                storedLastScan = last
                _state.update { it.withOwnOutcomes() }
            }
        }
        viewModelScope.launch {
            settings.scanRefusal.collect { refusal ->
                storedRefusal = refusal
                _state.update { it.withOwnOutcomes() }
            }
        }
        viewModelScope.launch {
            settings.scanDone.collect { done ->
                storedDone = done
                _state.update { it.withOwnOutcomes() }
            }
        }
        viewModelScope.launch {
            // The cooldown simply runs out; nothing rewrites the stored time
            // when it does, so its end is watched here. Each look compares with
            // the wall clock. The waits in between are short on purpose: delay()
            // counts uptime, which stands still while the phone sleeps, so one
            // long wait would still be pending after a night in a drawer.
            // [recheckCooldown] forces a look when a screen comes back.
            combine(settings.scanBlockedUntil, cooldownRecheck) { until, _ -> until }
                .collectLatest { until ->
                    while (true) {
                        val now = System.currentTimeMillis()
                        val active = cooldownActive(until, now)
                        _state.update { it.copy(blocked = active) }
                        if (!active || until == null) break
                        delay((until - now).coerceAtMost(COOLDOWN_LOOK_INTERVAL_MS))
                    }
                }
        }
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(BetaScanScheduler.MANUAL_WORK_NAME)
                .collect { infos ->
                    val report = manualWork.onReport(infos)
                    if (report.newRound) newScanRound()
                    failedRequests = infos.mapNotNull { it.asFailedRequest() }
                    _state.update { it.withOwnOutcomes() }
                    onManualWork(report.active)
                }
        }
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(BetaScanScheduler.WORK_NAME)
                .collect { infos ->
                    val running = infos.any { it.state == WorkInfo.State.RUNNING }
                    if (running && !backgroundRunning.value) newScanRound()
                    backgroundRunning.value = running
                }
        }
        viewModelScope.launch {
            combine(workBusy, backgroundRunning, repository.scanRunning) { work, bg, lock ->
                Triple(work, bg, lock)
            }.collect { (work, bg, lock) ->
                // The lock being taken is a scan beginning, whoever took it.
                if (lock && !lockHeld) newScanRound()
                lockHeld = lock
                _state.update {
                    it.copy(
                        busy = work || lock,
                        requested = work,
                        backgroundScanning = !work && bg,
                        cancelling = !work && !bg && lock,
                    )
                }
            }
        }
    }

    /** The stored outcomes, as far as the signed-in account may see them. */
    private fun ScanUiState.withOwnOutcomes(): ScanUiState = withOutcomesOf(
        account = signedInSession?.accountKey,
        lastScan = storedLastScan,
        refusal = storedRefusal,
        done = storedDone,
        unrecorded = unrecordedFailure(failedRequests, signedInSession, storedDone),
    )

    /** [active] is the manual run under way, or null when none is. */
    private fun onManualWork(active: WorkInfo?) {
        workBusy.value = active != null
        _state.update {
            val total = active?.progress?.getInt(BetaScanWorker.KEY_PROGRESS_TOTAL, 0) ?: 0
            val label = active?.progress?.getString(BetaScanWorker.KEY_PROGRESS_LABEL)
            it.copy(
                progress = if (active != null && total > 0 && label != null) {
                    ScanProgress(
                        index = active.progress.getInt(BetaScanWorker.KEY_PROGRESS_INDEX, 0),
                        total = total,
                        currentLabel = label,
                    )
                } else {
                    null
                },
            )
        }
    }

    /** Incremental scan: new apps and stale observations only. */
    fun scanNow() = requestScan(force = false)

    /** A scan asked for by button, tagged with the sign-in it is asked in.
     *  Without one known here nothing is asked for (see [canRequestScan]; the
     *  buttons are off until then). */
    private fun requestScan(force: Boolean) {
        val session = signedInSession ?: return
        BetaScanScheduler.scanNow(workManager, force = force, sessionGeneration = session.generation)
    }

    /**
     * Pull-to-refresh on the main screen, in two steps because the pull first
     * refreshes the installed apps and that can take a while. Call this the
     * moment the gesture fires: it notes the state, the scan round and the
     * sign-in the pull was made in. Call the returned function once the list refresh is done
     * ([Boolean] = the installed apps were just re-read, so the run need not
     * read them): it enqueues the incremental scan only if the pull was allowed
     * to ask, the state still allows it and no scan began or was cancelled in
     * between (see [mayStartPullScan]).
     */
    fun beginPullScan(): (appsJustRefreshed: Boolean) -> Unit {
        val atPull = _state.value
        return { appsJustRefreshed ->
            // The sign-in the gesture was made in, not whichever is in force
            // by now: mayStartPullScan has just made sure they are the same.
            val generation = atPull.sessionGeneration
            if (generation != null && mayStartPullScan(atPull, atEnqueue = _state.value)) {
                BetaScanScheduler.scanNow(
                    workManager,
                    sessionGeneration = generation,
                    recordEmptyRun = false,
                    appsRefreshedAt = if (appsJustRefreshed) System.currentTimeMillis() else null,
                )
            }
        }
    }

    /** Full re-scan: ignores freshness TTLs and re-checks every app. */
    fun fullScan() = requestScan(force = true)

    fun cancel() {
        // Cancelling ends the round: a pull still waiting for its list refresh
        // must not bring the cancelled scan back.
        newScanRound()
        workManager.cancelUniqueWork(BetaScanScheduler.MANUAL_WORK_NAME)
    }

    private fun newScanRound() {
        _state.update { it.copy(scanRound = it.scanRound + 1) }
    }

    /** Call when a screen showing scan state returns to the foreground: the
     *  rate-limit cooldown may have ended while the phone slept. */
    fun recheckCooldown() {
        cooldownRecheck.update { it + 1 }
    }

    private companion object {
        /** Longest wait between two looks at the cooldown, in uptime. */
        const val COOLDOWN_LOOK_INTERVAL_MS = 60_000L
    }
}
