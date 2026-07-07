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
    version = 5,
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

/** Re-scopes scrape observations to a signed-in Google account. Existing v3 rows were
 *  package-only and cannot be attributed to an account, so they are dropped. There is no
 *  automatic background sync yet: after upgrading, the Joined/Available tabs fall back to
 *  catalog + manual marking until the user runs the next scan from the Account screen,
 *  which repopulates observations for the signed-in account. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `beta_observations`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `beta_observations` (" +
                "`accountKey` TEXT NOT NULL, " +
                "`packageName` TEXT NOT NULL, " +
                "`liveStatus` TEXT NOT NULL, " +
                "`observedMembership` TEXT NOT NULL, " +
                "`checkedAt` INTEGER NOT NULL, " +
                "`lastError` TEXT, " +
                "PRIMARY KEY(`accountKey`, `packageName`))",
        )
    }
}

/** Adds the launcher-activity flag used to include preinstalled (system) apps in
 *  scans and lists. Existing rows default to 0; the next package scan — which runs
 *  on every app start — rewrites all rows with the real value. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE installed_apps ADD COLUMN hasLauncher INTEGER NOT NULL DEFAULT 0",
        )
    }
}
