package org.jarsi.betavahti.domain

/** Tunnetun beta-ohjelman tila tietämyskannassa (seed/käyttäjä). */
enum class KnownBetaStatus { UNKNOWN, OFTEN_OPEN, OFTEN_FULL, NO_PROGRAM }

/** Beta-tietueen alkuperä. */
enum class BetaSource { BUNDLED, USER }

/** Käyttäjän itse merkitsemä oma beta-tila. */
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
    /** null = johdetaan BetaLinkBuilderilla paketista. */
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
)

/** Yhdistetty rivi UI:lle: asennettu sovellus + mahdollinen beta-tieto + käyttäjän merkintä. */
data class AppBetaOverview(
    val app: InstalledAppInfo,
    val betaProgram: BetaProgramInfo? = null,
    val userStatus: UserBetaStatusInfo? = null,
)
