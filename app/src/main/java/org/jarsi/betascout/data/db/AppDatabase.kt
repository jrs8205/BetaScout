package org.jarsi.betascout.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        InstalledAppEntity::class,
        BetaProgramEntity::class,
        BetaObservationEntity::class,
        UserBetaStatusEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun installedAppDao(): InstalledAppDao
    abstract fun betaProgramDao(): BetaProgramDao
    abstract fun betaObservationDao(): BetaObservationDao
    abstract fun userBetaStatusDao(): UserBetaStatusDao
}

/** Adds the production version code used by the "am I on the beta" heuristic.
 *  Only the re-seeded beta_programs table gains a column; user data is untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE beta_programs ADD COLUMN productionVersionCode INTEGER")
    }
}

/** Adds the per-user scrape results table. Additive only: existing user data and
 *  catalog data are untouched, so v0.3.2 installs upgrade without data loss. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `beta_observations` (" +
                "`packageName` TEXT NOT NULL, " +
                "`liveStatus` TEXT NOT NULL, " +
                "`observedMembership` TEXT NOT NULL, " +
                "`checkedAt` INTEGER NOT NULL, " +
                "`lastError` TEXT, " +
                "PRIMARY KEY(`packageName`))",
        )
    }
}
