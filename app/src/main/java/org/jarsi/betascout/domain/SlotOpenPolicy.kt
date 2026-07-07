package org.jarsi.betascout.domain

/**
 * Decides which "a beta slot opened" notifications a background scan should fire.
 * An app qualifies when its observed live status changed to OPEN from a non-open
 * state, the user watches it, and the scan does not already see the user as a
 * member — a tester gains nothing from hearing that space is available.
 */
object SlotOpenPolicy {

    fun notifiable(
        transitions: List<StatusTransition>,
        rows: List<AppBetaOverview>,
    ): List<AppBetaOverview> {
        val rowsByPackage = rows.associateBy { it.app.packageName }
        return transitions
            .filter { it.to == LiveBetaStatus.OPEN && it.from != LiveBetaStatus.OPEN }
            .mapNotNull { rowsByPackage[it.packageName] }
            .filter { it.userStatus?.watching == true }
            .filter { it.observation?.observedMembership != ObservedMembership.JOINED }
    }
}
