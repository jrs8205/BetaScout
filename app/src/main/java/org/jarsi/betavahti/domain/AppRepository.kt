package org.jarsi.betavahti.domain

import kotlinx.coroutines.flow.Flow

interface AppRepository {

    /** Yhdistetty näkymä: asennetut sovellukset + beta-tieto + käyttäjän merkintä. */
    fun observeApps(): Flow<List<AppBetaOverview>>

    /** Lataa seed-beta-tietokannan Roomiin (idempotentti, ei ylikirjoita). */
    suspend fun ensureSeeded(): Result<Unit>

    /** Skannaa asennetut sovellukset ja korvaa välimuistin. */
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
