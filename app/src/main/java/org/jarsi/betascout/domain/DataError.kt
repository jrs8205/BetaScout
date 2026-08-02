package org.jarsi.betascout.domain

/** Data-layer errors in domain form; platform exceptions never leak to ViewModels. */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Local(cause: Throwable) : DataError("Local storage error", cause)
    class Scan(cause: Throwable) : DataError("Package scan error", cause)

    /** Another beta-status scan already holds the scan lock; this one was rejected. */
    class ScanInProgress : DataError("A scan is already running")

    /** The Google session is signed out or expired; a fresh sign-in is required. */
    class NeedsLogin : DataError("Sign-in required")

    /** Google answered 429/403 recently; scans are refused until [until] so the
     *  account is not hammered right back into the block. */
    class ScanBlocked(val until: Long) : DataError("Google is rate limiting; retry later")
}
