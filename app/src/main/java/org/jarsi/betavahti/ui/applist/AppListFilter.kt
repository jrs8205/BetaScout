package org.jarsi.betavahti.ui.applist

import org.jarsi.betavahti.domain.AppBetaOverview
import org.jarsi.betavahti.domain.KnownBetaStatus

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

/** "Beta löytyy" -merkin sääntö: ohjelma tunnetaan eikä ole merkitty olemattomaksi. */
fun AppBetaOverview.hasKnownBeta(): Boolean =
    betaProgram != null && betaProgram.knownStatus != KnownBetaStatus.NO_PROGRAM
