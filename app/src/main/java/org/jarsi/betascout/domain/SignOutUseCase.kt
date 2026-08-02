package org.jarsi.betascout.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Orders the sign-out cleanup so no step can race a scan still holding the old
 * session. WorkManager's cancel Operation completes before the cancelled
 * coroutine has actually unwound, so cancelling alone is not enough — the scan
 * lock must also be free before account data is wiped. All collaborators are
 * injected as lambdas to keep the ordering unit-testable.
 */
class SignOutUseCase(
    private val cancelScanWork: suspend () -> Unit,
    private val withScanLock: suspend (suspend () -> Unit) -> Unit,
    private val currentAccountKey: suspend () -> String?,
    private val clearObservations: suspend (String) -> Unit,
    private val clearSession: suspend () -> Unit,
    private val clearLastScan: suspend () -> Unit,
    private val clearWebViewCookies: suspend () -> Unit,
    private val rescheduleBackgroundScans: () -> Unit,
) {

    suspend fun signOut() = withContext(NonCancellable) {
        // NonCancellable: the caller is a screen-scoped coroutine that dies if
        // the user navigates away mid-cleanup — stopping half-way could leave
        // the session wiped but the old Google cookies alive. Sign-out is
        // cleanup; once started it must finish.
        // Stop BOTH scans first: a worker still holding the old session would
        // otherwise keep fetching Google pages with its cookies and could write
        // freshly deleted observations back.
        cancelScanWork()
        // The wipe runs INSIDE the scan lock: acquiring it waits out a cancelled
        // worker still unwinding an in-flight fetch, and any scan started while
        // the cleanup runs bounces off with ScanInProgress instead of racing it.
        withScanLock {
            // Observations first, then the session, so a signed-out account's
            // beta memberships cannot linger on the device.
            currentAccountKey()?.let { clearObservations(it) }
            clearSession()
            clearLastScan()
        }
        // The login WebView persists its Google cookies app-wide: without
        // clearing them the next "sign in" silently reuses the old session.
        // The lambda completes only once the cookie store confirms the removal.
        clearWebViewCookies()
        // Last on purpose: a freshly registered periodic request may start
        // immediately (no initial delay), and by now the session is gone, so
        // the run is a guaranteed no-op.
        rescheduleBackgroundScans()
    }
}
