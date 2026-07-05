package org.jarsi.betascout.domain

import kotlinx.coroutines.flow.Flow

/** Reads which of the given packages the account is subscribed to the beta of. */
fun interface MembershipSource {
    suspend fun subscribedPackages(
        email: String,
        aasToken: String,
        packages: List<String>,
    ): Result<Set<String>>
}

interface AppRepository {

    /** Combined view: installed apps + beta info + the user's own marking. */
    fun observeApps(): Flow<List<AppBetaOverview>>

    /** Loads the bundled seed database into Room (idempotent, never overwrites). */
    suspend fun ensureSeeded(): Result<Unit>

    /** Scans installed apps and replaces the cache. */
    suspend fun refreshApps(): Result<Unit>

    suspend fun setUserState(packageName: String, state: UserBetaState): Result<Unit>

    /** Reads authoritative beta membership from Google Play for the installed
     *  beta apps and records Joined/Not joined. Returns the number found joined. */
    suspend fun syncMembership(email: String, aasToken: String): Result<Int>

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
