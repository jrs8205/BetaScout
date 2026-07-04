package org.jarsi.betascout.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.UserBetaState

@Entity(tableName = "installed_apps")
data class InstalledAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val installerPackage: String?,
    val isSystem: Boolean,
    val lastScanned: Long,
)

@Entity(tableName = "beta_programs")
data class BetaProgramEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val testingUrl: String?,
    val knownStatus: KnownBetaStatus,
    val notes: String?,
    val source: BetaSource,
)

@Entity(tableName = "user_beta_status")
data class UserBetaStatusEntity(
    @PrimaryKey val packageName: String,
    val state: UserBetaState,
    val watching: Boolean,
    val reminderIntervalDays: Int,
    val lastCheckedByUser: Long?,
    val userNote: String?,
)
