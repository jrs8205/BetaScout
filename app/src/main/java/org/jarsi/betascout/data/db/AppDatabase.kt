package org.jarsi.betascout.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        InstalledAppEntity::class,
        BetaProgramEntity::class,
        UserBetaStatusEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun installedAppDao(): InstalledAppDao
    abstract fun betaProgramDao(): BetaProgramDao
    abstract fun userBetaStatusDao(): UserBetaStatusDao
}
