package org.jarsi.betascout.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledAppDao {

    @Query("SELECT * FROM installed_apps ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<InstalledAppEntity>>

    /** One-shot read for scan runs; a direct query, not an observable flow. */
    @Query("SELECT * FROM installed_apps ORDER BY label COLLATE NOCASE")
    suspend fun getAll(): List<InstalledAppEntity>

    @Upsert
    suspend fun upsertAll(apps: List<InstalledAppEntity>)

    @Query("DELETE FROM installed_apps WHERE packageName NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<String>)

    /** Replaces the scan result: removes uninstalled apps and updates the rest. */
    @Transaction
    suspend fun replaceAll(apps: List<InstalledAppEntity>) {
        deleteNotIn(apps.map { it.packageName })
        upsertAll(apps)
    }
}

@Dao
interface BetaProgramDao {

    @Query("SELECT * FROM beta_programs")
    fun observeAll(): Flow<List<BetaProgramEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(programs: List<BetaProgramEntity>)

    /** Catalog load: updates existing programs (e.g. productionVersionCode) and adds new ones.
     *  beta_programs is fully derived from the catalog; user data lives in a separate table. */
    @Upsert
    suspend fun upsertAll(programs: List<BetaProgramEntity>)

    @Upsert
    suspend fun upsert(program: BetaProgramEntity)

    @Query("SELECT COUNT(*) FROM beta_programs")
    suspend fun count(): Int
}

@Dao
interface BetaObservationDao {

    @Query("SELECT * FROM beta_observations")
    fun observeAll(): Flow<List<BetaObservationEntity>>

    /** One-shot read for scan runs; a direct query, not an observable flow. */
    @Query("SELECT * FROM beta_observations")
    suspend fun getAll(): List<BetaObservationEntity>

    @Query("SELECT * FROM beta_observations WHERE packageName = :packageName")
    suspend fun get(packageName: String): BetaObservationEntity?

    @Upsert
    suspend fun upsert(observation: BetaObservationEntity)
}

@Dao
interface UserBetaStatusDao {

    @Query("SELECT * FROM user_beta_status")
    fun observeAll(): Flow<List<UserBetaStatusEntity>>

    @Query("SELECT * FROM user_beta_status WHERE packageName = :packageName")
    fun observe(packageName: String): Flow<UserBetaStatusEntity?>

    @Query("SELECT * FROM user_beta_status WHERE packageName = :packageName")
    suspend fun get(packageName: String): UserBetaStatusEntity?

    @Upsert
    suspend fun upsert(status: UserBetaStatusEntity)
}
