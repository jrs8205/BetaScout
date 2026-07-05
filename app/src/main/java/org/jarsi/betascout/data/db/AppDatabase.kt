package org.jarsi.betascout.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        InstalledAppEntity::class,
        BetaProgramEntity::class,
        UserBetaStatusEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun installedAppDao(): InstalledAppDao
    abstract fun betaProgramDao(): BetaProgramDao
    abstract fun userBetaStatusDao(): UserBetaStatusDao
}

/** Adds the production version code used by the "am I on the beta" heuristic.
 *  Only the re-seeded beta_programs table gains a column; user data is untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE beta_programs ADD COLUMN productionVersionCode INTEGER")
    }
}
