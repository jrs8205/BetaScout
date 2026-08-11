package org.jarsi.betascout.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** SQLite before 3.32 (Android 8-11) rejects statements with more than 999 host
 *  parameters, so list-bound deletes are chunked well under that limit. */
internal const val SQL_VARIABLE_CHUNK = 500

@Dao
interface InstalledAppDao {

    @Query("SELECT * FROM installed_apps ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<InstalledAppEntity>>

    /** One-shot read for scan runs; a direct query, not an observable flow. */
    @Query("SELECT * FROM installed_apps ORDER BY label COLLATE NOCASE")
    suspend fun getAll(): List<InstalledAppEntity>

    @Query("SELECT packageName FROM installed_apps")
    suspend fun getAllPackageNames(): List<String>

    @Upsert
    suspend fun upsertAll(apps: List<InstalledAppEntity>)

    @Query("DELETE FROM installed_apps WHERE packageName IN (:packageNames)")
    suspend fun deleteIn(packageNames: List<String>)

    /** Replaces the scan result: removes uninstalled apps and updates the rest. */
    @Transaction
    suspend fun replaceAll(apps: List<InstalledAppEntity>) {
        val keep = apps.mapTo(HashSet()) { it.packageName }
        getAllPackageNames().filterNot { it in keep }
            .chunked(SQL_VARIABLE_CHUNK)
            .forEach { deleteIn(it) }
        upsertAll(apps)
    }
}

@Dao
interface BetaProgramDao {

    @Query("SELECT * FROM beta_programs")
    fun observeAll(): Flow<List<BetaProgramEntity>>

    @Query("SELECT * FROM beta_programs")
    suspend fun getAll(): List<BetaProgramEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(programs: List<BetaProgramEntity>)

    @Upsert
    suspend fun upsertAll(programs: List<BetaProgramEntity>)

    @Query("SELECT packageName FROM beta_programs")
    suspend fun getAllPackageNames(): List<String>

    @Query("DELETE FROM beta_programs WHERE packageName IN (:packageNames)")
    suspend fun deleteIn(packageNames: List<String>)

    /** Catalog load: mirrors the catalog exactly — beta_programs is fully derived
     *  from it, so programs the backend removed disappear here too. User data lives
     *  in separate tables and is untouched. */
    @Transaction
    suspend fun replaceAll(programs: List<BetaProgramEntity>) {
        val keep = programs.mapTo(HashSet()) { it.packageName }
        getAllPackageNames().filterNot { it in keep }
            .chunked(SQL_VARIABLE_CHUNK)
            .forEach { deleteIn(it) }
        upsertAll(programs)
    }

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

    @Query("SELECT * FROM beta_observations WHERE accountKey = :accountKey")
    suspend fun getAllForAccount(accountKey: String): List<BetaObservationEntity>

    @Query("SELECT * FROM beta_observations WHERE accountKey = :accountKey AND packageName = :packageName")
    suspend fun get(accountKey: String, packageName: String): BetaObservationEntity?

    @Query("DELETE FROM beta_observations")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsert(observation: BetaObservationEntity)
}

@Dao
interface UserBetaStatusDao {

    @Query("SELECT * FROM user_beta_status")
    fun observeAll(): Flow<List<UserBetaStatusEntity>>

    @Query("SELECT * FROM user_beta_status WHERE packageName = :packageName")
    fun observe(packageName: String): Flow<UserBetaStatusEntity?>

    /** One-shot read for worker runs; a direct query, not an observable flow. */
    @Query("SELECT * FROM user_beta_status")
    suspend fun getAll(): List<UserBetaStatusEntity>

    @Query("SELECT * FROM user_beta_status WHERE packageName = :packageName")
    suspend fun get(packageName: String): UserBetaStatusEntity?

    @Upsert
    suspend fun upsert(status: UserBetaStatusEntity)
}
