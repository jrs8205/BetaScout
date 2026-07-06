package org.jarsi.betascout.domain

/** Known status of a beta program in the knowledge base (seed/user). */
enum class KnownBetaStatus { UNKNOWN, OFTEN_OPEN, OFTEN_FULL, NO_PROGRAM }

/** Freshly observed state of a testing program. NO_PROGRAM means the app has no
 *  open-testing page at all. */
enum class LiveBetaStatus { UNKNOWN, OPEN, FULL, CLOSED, NO_PROGRAM }

/** Membership as observed from the authenticated testing page (not user-declared). */
enum class ObservedMembership { UNKNOWN, JOINED, NOT_JOINED }

/** Origin of a beta record. */
enum class BetaSource { BUNDLED, REMOTE, USER }

/** The user's Google Play web session, as a cookie header for authenticated requests. */
data class PlaySession(val cookieHeader: String)

/** Summary of one status-scan run. */
data class ScanSummary(val checked: Int, val joined: Int, val needsLogin: Boolean)

/** Live progress of a status-scan run: the app being checked now (1-based). */
data class ScanProgress(val index: Int, val total: Int, val currentLabel: String)

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
    /** Latest observed live state of the testing program (open/full/closed). */
    val liveStatus: LiveBetaStatus = LiveBetaStatus.UNKNOWN,
    /** Epoch millis when liveStatus was last verified; null if never. */
    val statusCheckedAt: Long? = null,
    /** Current production version code, used to guess if an installed build is a beta. */
    val productionVersionCode: Long? = null,
    val notes: String? = null,
    val source: BetaSource = BetaSource.BUNDLED,
)

/** What the authenticated testing page reported for one app, per user/device. */
data class BetaObservation(
    val packageName: String,
    val liveStatus: LiveBetaStatus = LiveBetaStatus.UNKNOWN,
    val observedMembership: ObservedMembership = ObservedMembership.UNKNOWN,
    val checkedAt: Long,
    val lastError: String? = null,
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

/** Combined row for the UI: installed app + optional beta info + user marking + scrape. */
data class AppBetaOverview(
    val app: InstalledAppInfo,
    val betaProgram: BetaProgramInfo? = null,
    val userStatus: UserBetaStatusInfo? = null,
    val observation: BetaObservation? = null,
)
