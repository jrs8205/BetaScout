package org.jarsi.betascout.domain

/** Data-layer errors in domain form; platform exceptions never leak to ViewModels. */
sealed class DataError(
    message: String,
    cause: Throwable? = null,
    /** For a scan that was refused or failed: when, as a scan ticket taken at
     *  that very moment; 0 if none was taken. */
    val scanTicket: Long = 0,
) : Exception(message, cause) {
    class Local(cause: Throwable, scanTicket: Long = 0) :
        DataError("Local storage error", cause, scanTicket)
    class Scan(cause: Throwable) : DataError("Package scan error", cause)

    /** Another beta-status scan already holds the scan lock; this one was rejected. */
    class ScanInProgress(scanTicket: Long = 0) :
        DataError("A scan is already running", scanTicket = scanTicket)

    /** The Google session is signed out or expired; a fresh sign-in is required. */
    class NeedsLogin : DataError("Sign-in required")

    /** The session this request was started with is no longer the signed-in one
     *  (signed out or switched account meanwhile). Nothing is wrong with the
     *  current session, so unlike [NeedsLogin] this must not clear it. */
    class StaleSession : DataError("Session is no longer current")

    /** The store could not confirm which sign-in owns the scan. */
    class UnreadableSession(cause: Throwable) : DataError("Could not read the current session", cause)

    /** Google answered 429/403 recently; scans are refused until [until] so the
     *  account is not hammered right back into the block. */
    class ScanBlocked(val until: Long, scanTicket: Long = 0) :
        DataError("Google is rate limiting; retry later", scanTicket = scanTicket)
}
