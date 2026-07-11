package org.jarsi.betascout.domain

/** Data-layer errors in domain form; platform exceptions never leak to ViewModels. */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Local(cause: Throwable) : DataError("Local storage error", cause)
    class Scan(cause: Throwable) : DataError("Package scan error", cause)

    /** Another beta-status scan already holds the scan lock; this one was rejected. */
    class ScanInProgress : DataError("A scan is already running")
}
