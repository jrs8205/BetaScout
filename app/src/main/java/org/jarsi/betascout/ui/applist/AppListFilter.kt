package org.jarsi.betascout.ui.applist

import org.jarsi.betascout.R
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership
import org.jarsi.betascout.domain.UserBetaState
import org.jarsi.betascout.domain.isRelevantApp

data class AppFilters(
    val query: String = "",
    val onlyBeta: Boolean = false,
    val onlyWatched: Boolean = false,
    val onlySystem: Boolean = false,
)

fun filterApps(rows: List<AppBetaOverview>, filters: AppFilters): List<AppBetaOverview> {
    val query = filters.query.trim()
    return rows.asSequence()
        // Framework packages (no launcher, never store-updated) are never shown —
        // they are not apps to the user and have no Play page.
        .filter { it.app.isRelevantApp }
        .filter { !filters.onlySystem || it.app.isSystem }
        .filter { !filters.onlyBeta || it.hasKnownBeta() }
        .filter { !filters.onlyWatched || it.userStatus?.watching == true }
        .filter {
            query.isEmpty() ||
                it.app.label.contains(query, ignoreCase = true) ||
                it.app.packageName.contains(query, ignoreCase = true)
        }
        .toList()
}

/** Per-tab totals of the already-filtered rows, shown in the tab titles. */
fun tabCounts(filteredRows: List<AppBetaOverview>): Map<BetaMembership, Int> =
    filteredRows.groupingBy { it.betaMembership() }.eachCount()

/** Row badge for an app's beta state; null = nothing worth badging. */
enum class StatusBadge(val labelRes: Int) {
    OPEN(R.string.badge_open),
    FULL(R.string.badge_full),
    CLOSED(R.string.badge_closed),
    JOINED(R.string.badge_joined),
    BETA(R.string.badge_beta),
}

/** Plain-language one-liner for an app row — tells the user what the state
 *  means for them instead of echoing an enum. */
fun AppBetaOverview.statusLineRes(): Int = when {
    betaMembership() == BetaMembership.JOINED -> R.string.status_line_joined
    betaMembership() == BetaMembership.NONE -> R.string.status_line_no_beta
    observation?.liveStatus == LiveBetaStatus.OPEN -> R.string.status_line_open
    observation?.liveStatus == LiveBetaStatus.FULL -> R.string.status_line_full
    observation?.liveStatus == LiveBetaStatus.CLOSED -> R.string.status_line_closed
    else -> R.string.status_line_has_beta
}

fun AppBetaOverview.statusBadge(): StatusBadge? = when {
    betaMembership() == BetaMembership.JOINED -> StatusBadge.JOINED
    betaMembership() == BetaMembership.NONE -> null
    observation?.liveStatus == LiveBetaStatus.OPEN -> StatusBadge.OPEN
    observation?.liveStatus == LiveBetaStatus.FULL -> StatusBadge.FULL
    observation?.liveStatus == LiveBetaStatus.CLOSED -> StatusBadge.CLOSED
    else -> StatusBadge.BETA
}

/** Label for the user's own manual marking (detail screen and status chips). */
fun UserBetaState.labelRes(): Int = when (this) {
    UserBetaState.UNKNOWN -> R.string.state_unknown
    UserBetaState.JOINED -> R.string.state_joined
    UserBetaState.NOT_JOINED -> R.string.state_not_joined
    UserBetaState.FULL -> R.string.state_full
    UserBetaState.NO_PROGRAM -> R.string.state_no_program
}

/** The "Open betas" rail: joinable-right-now apps, freshest reading first. */
fun openBetas(rows: List<AppBetaOverview>): List<AppBetaOverview> = rows
    .filter { it.observation?.liveStatus == LiveBetaStatus.OPEN }
    .filter { it.betaMembership() == BetaMembership.AVAILABLE }
    .sortedByDescending { it.observation?.checkedAt ?: 0L }
    .take(OPEN_BETAS_CAP)

private const val OPEN_BETAS_CAP = 10

/** Rule for the "Beta available" badge: the program is known and not marked non-existent. */
fun AppBetaOverview.hasKnownBeta(): Boolean {
    // A positive membership signal (scrape saw a leave form, or the user manually
    // marked JOINED) means there IS a program, even if this account's scrape reads
    // NO_PROGRAM — the user may have joined on a different Google account.
    if (observation?.observedMembership == ObservedMembership.JOINED) return true
    if (userStatus?.state == UserBetaState.JOINED) return true
    return when (observation?.liveStatus) {
        LiveBetaStatus.OPEN, LiveBetaStatus.FULL, LiveBetaStatus.CLOSED -> true
        LiveBetaStatus.NO_PROGRAM -> false
        LiveBetaStatus.UNKNOWN, null ->
            betaProgram != null && betaProgram.knownStatus != KnownBetaStatus.NO_PROGRAM
    }
}

/** Which beta tab an app belongs to. */
enum class BetaMembership { JOINED, AVAILABLE, NONE }

/**
 * Classifies an app for the Joined / Available tabs from the recorded status.
 * A version-comparison guess was tried and removed: it produced both false
 * positives (stale production reference) and false negatives (beta and production
 * sharing a version code). Authoritative membership requires the user's Google
 * account, which the sign-in feature writes into this status.
 *
 * Positive membership signals (scraped JOINED, or a manual JOINED marking) win over
 * any "no program" reading so a single wrong scrape cannot hide a beta the user has
 * actually joined. FULL/CLOSED programs the user is not in stay in the Available tab
 * (that is the "not joined" tab); their live status is surfaced on the detail screen.
 */
/** Whether the detail screen should offer joining this app's testing program. */
fun AppBetaOverview.canJoinBeta(): Boolean = betaMembership() == BetaMembership.AVAILABLE

fun AppBetaOverview.betaMembership(): BetaMembership {
    if (observation?.observedMembership == ObservedMembership.JOINED) return BetaMembership.JOINED
    if (userStatus?.state == UserBetaState.JOINED) return BetaMembership.JOINED
    if (!hasKnownBeta()) return BetaMembership.NONE
    if (observation?.observedMembership == ObservedMembership.NOT_JOINED) return BetaMembership.AVAILABLE
    return when (userStatus?.state) {
        UserBetaState.NO_PROGRAM -> BetaMembership.NONE
        else -> BetaMembership.AVAILABLE
    }
}
