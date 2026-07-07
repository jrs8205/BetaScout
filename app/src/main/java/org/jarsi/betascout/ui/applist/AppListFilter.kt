package org.jarsi.betascout.ui.applist

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
