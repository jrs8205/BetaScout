package org.jarsi.betascout.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jarsi.betascout.data.settings.LastScanInfo
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.domain.AppRepository
import org.jarsi.betascout.domain.ScanProgress
import org.jarsi.betascout.work.BetaScanScheduler
import org.jarsi.betascout.work.BetaScanWorker

data class ScanUiState(
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    /** A cancelled run is still unwinding: controls stay disabled, no progress row. */
    val cancelling: Boolean = false,
    val progress: ScanProgress? = null,
    val lastScan: LastScanInfo? = null,
    val error: String? = null,
    val needsLogin: Boolean = false,
)

/**
 * Scan state shared by the main screen's status card and the account screen.
 * The scan itself runs as WorkManager work (it outlives any screen and the whole
 * app process); this just mirrors that work's state plus the repository's scan
 * lock — WorkManager reports CANCELLED before a cancelled run has actually
 * released the lock.
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

    init {
        viewModelScope.launch {
            settings.playSession.collect { session ->
                _state.update {
                    it.copy(
                        signedIn = session != null,
                        // A fresh session settles any earlier expiry.
                        needsLogin = if (session != null) false else it.needsLogin,
                    )
                }
            }
        }
        viewModelScope.launch {
            settings.lastScan.collect { last ->
                _state.update { it.copy(lastScan = last) }
            }
        }
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(BetaScanScheduler.MANUAL_WORK_NAME)
                .collect { infos -> onScanWorkChanged(infos.firstOrNull()) }
        }
        viewModelScope.launch {
            combine(workBusy, repository.scanRunning) { work, lock -> work to lock }
                .collect { (work, lock) ->
                    _state.update { it.copy(busy = work || lock, cancelling = !work && lock) }
                }
        }
    }

    private fun onScanWorkChanged(info: WorkInfo?) {
        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                workBusy.value = true
                _state.update {
                    val total = info.progress.getInt(BetaScanWorker.KEY_PROGRESS_TOTAL, 0)
                    val label = info.progress.getString(BetaScanWorker.KEY_PROGRESS_LABEL)
                    it.copy(
                        error = null,
                        progress = if (total > 0 && label != null) {
                            ScanProgress(
                                index = info.progress.getInt(BetaScanWorker.KEY_PROGRESS_INDEX, 0),
                                total = total,
                                currentLabel = label,
                            )
                        } else {
                            null
                        },
                    )
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                workBusy.value = false
                _state.update {
                    it.copy(
                        progress = null,
                        // Guarded on signedIn so a stale pre-re-login result cannot
                        // resurface the prompt once a new session is in place.
                        needsLogin = !it.signedIn &&
                            info.outputData.getBoolean(BetaScanWorker.KEY_NEEDS_LOGIN, false),
                    )
                }
            }

            WorkInfo.State.FAILED -> {
                workBusy.value = false
                _state.update {
                    it.copy(
                        progress = null,
                        error = info.outputData.getString(BetaScanWorker.KEY_ERROR) ?: "scan_failed",
                    )
                }
            }

            WorkInfo.State.CANCELLED, null -> {
                workBusy.value = false
                _state.update { it.copy(progress = null) }
            }
        }
    }

    /** Incremental scan: new apps and stale observations only. */
    fun scanNow() {
        _state.update { it.copy(error = null) }
        BetaScanScheduler.scanNow(workManager)
    }

    /** Full re-scan: ignores freshness TTLs and re-checks every app. */
    fun fullScan() {
        _state.update { it.copy(error = null) }
        BetaScanScheduler.scanNow(workManager, force = true)
    }

    fun cancel() {
        workManager.cancelUniqueWork(BetaScanScheduler.MANUAL_WORK_NAME)
    }
}
