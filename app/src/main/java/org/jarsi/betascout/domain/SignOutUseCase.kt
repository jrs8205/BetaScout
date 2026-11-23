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
    private val clearObservations: suspend () -> Result<Unit>,
    private val clearSession: suspend () -> Unit,
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
        try {
            cancelScanWork()
            withScanLock {
                // Keep the session as a retry point until the local rows and browser cookies are gone.
                // The final preferences edit clears the session and its scan history together.
                clearObservations().getOrThrow()
                clearWebViewCookies()
                clearSession()
            }
        } finally {
            rescheduleBackgroundScans()
        }
    }
}
