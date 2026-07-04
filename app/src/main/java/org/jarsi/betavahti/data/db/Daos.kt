package org.jarsi.betavahti.data.db

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

    @Upsert
    suspend fun upsertAll(apps: List<InstalledAppEntity>)

    @Query("DELETE FROM installed_apps WHERE packageName NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<String>)

    /** Korvaa skannaustuloksen: poistaa poisasennetut ja päivittää loput. */
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

    /** Seed-lataus: ei koskaan ylikirjoita olemassa olevia (esim. USER-lähteisiä) rivejä. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(programs: List<BetaProgramEntity>)

    @Upsert
    suspend fun upsert(program: BetaProgramEntity)

    @Query("SELECT COUNT(*) FROM beta_programs")
    suspend fun count(): Int
}

@Dao
interface UserBetaStatusDao {

    @Query("SELECT * FROM user_beta_status")
    fun observeAll(): Flow<List<UserBetaStatusEntity>>

    @Query("SELECT * FROM user_beta_status WHERE packageName = :packageName")
    fun observe(packageName: String): Flow<UserBetaStatusEntity?>

    @Upsert
    suspend fun upsert(status: UserBetaStatusEntity)
}
