package org.jarsi.betascout.domain

import kotlinx.coroutines.flow.Flow

interface AppRepository {

    /** Combined view: installed apps + beta info + the user's own marking. */
    fun observeApps(): Flow<List<AppBetaOverview>>

    /** Loads the bundled seed database into Room (idempotent, never overwrites). */
    suspend fun ensureSeeded(): Result<Unit>

    /** Scans installed apps and replaces the cache. */
    suspend fun refreshApps(): Result<Unit>

    suspend fun setUserState(packageName: String, state: UserBetaState): Result<Unit>

    suspend fun setWatching(
        packageName: String,
        watching: Boolean,
        reminderIntervalDays: Int? = null,
    ): Result<Unit>

    suspend fun setUserNote(packageName: String, note: String?): Result<Unit>

    suspend fun markCheckedNow(packageName: String): Result<Unit>
}
