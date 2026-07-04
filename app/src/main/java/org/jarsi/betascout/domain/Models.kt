package org.jarsi.betascout.domain

/** Known status of a beta program in the knowledge base (seed/user). */
enum class KnownBetaStatus { UNKNOWN, OFTEN_OPEN, OFTEN_FULL, NO_PROGRAM }

/** Origin of a beta record. */
enum class BetaSource { BUNDLED, USER }

/** The user's self-reported beta status. */
enum class UserBetaState { UNKNOWN, JOINED, NOT_JOINED, FULL, NO_PROGRAM }

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val installerPackage: String?,
    val isSystem: Boolean,
    val lastScanned: Long,
)

data class BetaProgramInfo(
    val packageName: String,
    val appName: String,
    /** null = derived from the package name via BetaLinkBuilder. */
    val testingUrl: String? = null,
    val knownStatus: KnownBetaStatus = KnownBetaStatus.UNKNOWN,
    val notes: String? = null,
    val source: BetaSource = BetaSource.BUNDLED,
)

data class UserBetaStatusInfo(
    val packageName: String,
    val state: UserBetaState = UserBetaState.UNKNOWN,
    val watching: Boolean = false,
    val reminderIntervalDays: Int = 7,
    val lastCheckedByUser: Long? = null,
    val userNote: String? = null,
    /** When a reminder notification was last shown; throttles repeated reminders. */
    val lastRemindedAt: Long? = null,
)

/** Combined row for the UI: installed app + optional beta info + user marking. */
data class AppBetaOverview(
    val app: InstalledAppInfo,
    val betaProgram: BetaProgramInfo? = null,
    val userStatus: UserBetaStatusInfo? = null,
)
