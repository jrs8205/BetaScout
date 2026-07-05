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
 * Classifies an app for the Joined / Available tabs. The user's own marking wins;
 * otherwise the version heuristic auto-guesses membership (a third-party app cannot
 * read Google Play enrollment). Apps without a known program are excluded.
 */
fun AppBetaOverview.betaMembership(): BetaMembership {
    if (!hasKnownBeta()) return BetaMembership.NONE
    return when (userStatus?.state) {
        UserBetaState.JOINED -> BetaMembership.JOINED
        UserBetaState.NOT_JOINED, UserBetaState.FULL -> BetaMembership.AVAILABLE
        UserBetaState.NO_PROGRAM -> BetaMembership.NONE
        UserBetaState.UNKNOWN, null ->
            if (looksJoinedByVersion()) BetaMembership.JOINED else BetaMembership.AVAILABLE
    }
}

/**
 * Guesses that the user is on the beta when their installed build is newer than
 * the catalog's production version code. Best-effort — the honest limit of what
 * an app can detect without the user's Google account.
 */
fun AppBetaOverview.looksJoinedByVersion(): Boolean {
    val production = betaProgram?.productionVersionCode ?: return false
    return app.versionCode > production
}
