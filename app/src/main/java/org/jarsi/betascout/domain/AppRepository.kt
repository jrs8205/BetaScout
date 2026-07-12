package org.jarsi.betascout.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AppRepository {

    /** Combined view: installed apps + beta info + the user's own marking. */
    fun observeApps(): Flow<List<AppBetaOverview>>

    /** True while a status scan holds the scan lock — including the tail of a
     *  cancelled run that is still unwinding its in-flight page fetch. UIs gate
     *  their scan controls on this, not on WorkManager state, which reports
     *  CANCELLED before the run has actually released the lock. */
    val scanRunning: StateFlow<Boolean>

    /** Loads the bundled seed database into Room (idempotent, never overwrites). */
    suspend fun ensureSeeded(): Result<Unit>

    /** Scans installed apps and replaces the cache. */
    suspend fun refreshApps(): Result<Unit>

    suspend fun setUserState(packageName: String, state: UserBetaState): Result<Unit>

    /** Deletes all scrape observations recorded for the given account (used on sign-out
     *  so a signed-out account's beta memberships do not linger on the device). */
    suspend fun clearObservations(accountKey: String): Result<Unit>

    /** Scrapes the authenticated testing page for the installed apps that are due a
     *  check, recording live status and observed membership. Returns a run summary.
     *  [onProgress] is invoked before each page fetch so the UI can show progress.
     *  [cap] bounds the number of apps per run (for scheduled background scans);
     *  null scans everything due — the crawl delay still paces the requests.
     *  [force] ignores the freshness TTL so a user-initiated scan always re-checks
     *  every app (memberships can change outside the app). */
    suspend fun refreshBetaStatus(
        session: PlaySession,
        cap: Int? = null,
        force: Boolean = false,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): Result<ScanSummary>

    suspend fun setWatching(
        packageName: String,
        watching: Boolean,
        reminderIntervalDays: Int? = null,
    ): Result<Unit>

    suspend fun setUserNote(packageName: String, note: String?): Result<Unit>

    suspend fun markCheckedNow(packageName: String): Result<Unit>

    /** Stamps lastRemindedAt = now for the given packages (reminder throttling). */
    suspend fun markReminded(packageNames: List<String>): Result<Unit>
}
