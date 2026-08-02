package org.jarsi.betascout.domain

/**
 * Orders the sign-out cleanup so no step can race a scan still holding the old
 * session. WorkManager's cancel Operation completes before the cancelled
 * coroutine has actually unwound, so cancelling alone is not enough — the scan
 * lock must also be free before account data is wiped. All collaborators are
 * injected as lambdas to keep the ordering unit-testable.
 */
class SignOutUseCase(
    private val cancelScanWork: suspend () -> Unit,
    private val awaitScanIdle: suspend () -> Unit,
    private val rescheduleBackgroundScans: () -> Unit,
    private val currentAccountKey: suspend () -> String?,
    private val clearObservations: suspend (String) -> Unit,
    private val clearSession: suspend () -> Unit,
    private val clearLastScan: suspend () -> Unit,
    private val clearWebViewCookies: suspend () -> Unit,
) {

    suspend fun signOut() {
        // Stop BOTH scans first: a worker still holding the old session would
        // otherwise keep fetching Google pages with its cookies and could write
        // freshly deleted observations back.
        cancelScanWork()
        // Cancellation is asynchronous; wait for the scan lock so an in-flight
        // fetch or observation write has actually finished before anything is
        // cleared.
        awaitScanIdle()
        // Cancelling the unique periodic work removed its schedule too;
        // re-register it so background scans resume after the next sign-in
        // without an app restart (signed-out runs are no-ops).
        rescheduleBackgroundScans()
        // Delete the account's observations before clearing the session so a
        // signed-out account's beta memberships do not linger on the device.
        currentAccountKey()?.let { clearObservations(it) }
        clearSession()
        clearLastScan()
        // The login WebView persists its Google cookies app-wide: without
        // clearing them the next "sign in" silently reuses the old session.
        // The lambda completes only once the cookie store confirms the removal.
        clearWebViewCookies()
    }
}
