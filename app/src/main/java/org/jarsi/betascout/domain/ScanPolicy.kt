package org.jarsi.betascout.domain

/** A package eligible for a status scan, with its last known observation. */
data class ScanCandidate(
    val packageName: String,
    val lastStatus: LiveBetaStatus,
    val checkedAt: Long?,
)

/**
 * Decides which installed packages to scan on a given run and how long an
 * observation stays fresh. This is the throttle that keeps request volume — and
 * therefore account risk — low: a package is only re-checked once its status-based
 * TTL has elapsed, and each run is capped.
 */
object ScanPolicy {

    private const val HOUR = 3_600_000L

    fun ttlMillis(status: LiveBetaStatus): Long = when (status) {
        LiveBetaStatus.OPEN, LiveBetaStatus.FULL -> 24 * HOUR
        LiveBetaStatus.CLOSED -> 72 * HOUR
        LiveBetaStatus.NO_PROGRAM -> 168 * HOUR
        // Inconclusive: retry sooner than a settled status, but not every run.
        LiveBetaStatus.UNKNOWN -> 12 * HOUR
    }

    fun isDue(status: LiveBetaStatus, checkedAt: Long?, now: Long): Boolean {
        if (checkedAt == null) return true
        return now - checkedAt >= ttlMillis(status)
    }

    /**
     * The due packages to scan this run, never-checked first (discover new installs),
     * then the most stale, capped so a single run stays gentle. [ignoreTtl] bypasses
     * the freshness check for user-initiated scans — the user may have joined or left
     * a beta outside the app, so "Scan now" must always re-check everything.
     */
    fun selectDue(
        candidates: List<ScanCandidate>,
        now: Long,
        cap: Int,
        ignoreTtl: Boolean = false,
    ): List<ScanCandidate> =
        candidates
            .filter { ignoreTtl || isDue(it.lastStatus, it.checkedAt, now) }
            .sortedWith(compareBy(nullsFirst()) { it.checkedAt })
            .take(cap)
}
