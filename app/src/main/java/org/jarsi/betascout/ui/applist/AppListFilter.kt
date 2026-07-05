package org.jarsi.betascout.ui.applist

import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.UserBetaState

data class AppFilters(
    val query: String = "",
    val onlyBeta: Boolean = false,
    val onlyWatched: Boolean = false,
    val showSystem: Boolean = false,
)

fun filterApps(rows: List<AppBetaOverview>, filters: AppFilters): List<AppBetaOverview> {
    val query = filters.query.trim()
    return rows.asSequence()
        .filter { filters.showSystem || !it.app.isSystem }
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
fun AppBetaOverview.hasKnownBeta(): Boolean =
    betaProgram != null && betaProgram.knownStatus != KnownBetaStatus.NO_PROGRAM

/** Which beta tab an app belongs to. */
enum class BetaMembership { JOINED, AVAILABLE, NONE }

/**
 * Classifies an app for the Joined / Available tabs from the recorded status.
 * A version-comparison guess was tried and removed: it produced both false
 * positives (stale production reference) and false negatives (beta and production
 * sharing a version code). Authoritative membership requires the user's Google
 * account, which the sign-in feature writes into this status.
 */
fun AppBetaOverview.betaMembership(): BetaMembership {
    if (!hasKnownBeta()) return BetaMembership.NONE
    return when (userStatus?.state) {
        UserBetaState.JOINED -> BetaMembership.JOINED
        UserBetaState.NO_PROGRAM -> BetaMembership.NONE
        else -> BetaMembership.AVAILABLE
    }
}
