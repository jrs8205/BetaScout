package org.jarsi.betascout.domain

import java.security.MessageDigest
import java.util.Locale

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
data class PlaySession(
    val accountEmail: String,
    val cookieHeader: String,
) {
    val accountKey: String =
        accountEmail.trim().lowercase(Locale.ROOT).ifBlank { "cookie:${cookieHeader.sha256Hex()}" }
}

/** One app's observed live status changing between two scan runs. */
data class StatusTransition(
    val packageName: String,
    val from: LiveBetaStatus,
    val to: LiveBetaStatus,
)

/** Summary of one status-scan run. [checked] counts pages fetched this run and
 *  [failures] maps each due page whose fetch failed to a short reason (those are
 *  retried on the next run); [joined]/[notJoined]/[noProgram] are the signed-in
 *  account's totals after the run. */
data class ScanSummary(
    val checked: Int,
    val joined: Int,
    val notJoined: Int,
    val needsLogin: Boolean,
    val noProgram: Int = 0,
    val failures: Map<String, String> = emptyMap(),
    val transitions: List<StatusTransition> = emptyList(),
) {
    val failed: Int get() = failures.size

    /** The most common failure reason of the run — the one worth showing when a
     *  scan degrades into mass failures (rate limiting, captive portal, outage). */
    val topFailureReason: String?
        get() = failures.values
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
}

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
    /** True if the package has a launcher activity. Preinstalled apps (Chrome, Gmail…)
     *  carry the system flag yet have beta programs; this flag separates them from
     *  framework packages that have no Play page at all. */
    val hasLauncher: Boolean,
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
    val accountKey: String,
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

/** The Play Store's own package name, as reported in installerPackage. */
const val PLAY_STORE_PACKAGE = "com.android.vending"

/** True for the packages BetaScout treats as apps: everything the user installed,
 *  plus preinstalled packages that either have a launcher entry or are updated by
 *  the Play Store itself (WebView and Play services have beta programs but no
 *  launcher icon). Requiring the Play Store as the installer — not just any
 *  installer — keeps OEM/updater-managed system components out of the scan, where
 *  they would only produce pointless testing-page fetches. */
val InstalledAppInfo.isRelevantApp: Boolean
    get() = !isSystem || hasLauncher || installerPackage == PLAY_STORE_PACKAGE

/** Combined row for the UI: installed app + optional beta info + user marking + scrape. */
data class AppBetaOverview(
    val app: InstalledAppInfo,
    val betaProgram: BetaProgramInfo? = null,
    val userStatus: UserBetaStatusInfo? = null,
    val observation: BetaObservation? = null,
)

private fun String.sha256Hex(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
