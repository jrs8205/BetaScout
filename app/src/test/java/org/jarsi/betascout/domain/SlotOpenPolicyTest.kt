package org.jarsi.betascout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun overview(
    packageName: String,
    watching: Boolean = true,
    membership: ObservedMembership = ObservedMembership.NOT_JOINED,
) = AppBetaOverview(
    app = InstalledAppInfo(
        packageName = packageName,
        label = packageName,
        versionName = "1.0",
        versionCode = 1L,
        installerPackage = null,
        isSystem = false,
        hasLauncher = true,
        lastScanned = 0L,
    ),
    userStatus = UserBetaStatusInfo(packageName = packageName, watching = watching),
    observation = BetaObservation(
        accountKey = "user@example.com",
        packageName = packageName,
        liveStatus = LiveBetaStatus.OPEN,
        observedMembership = membership,
        checkedAt = 0L,
    ),
)

private fun opened(packageName: String, from: LiveBetaStatus = LiveBetaStatus.FULL) =
    StatusTransition(packageName, from = from, to = LiveBetaStatus.OPEN)

class SlotOpenPolicyTest {

    @Test
    fun `notifies for a watched app whose full program opened`() {
        val result = SlotOpenPolicy.notifiable(
            transitions = listOf(opened("com.a")),
            rows = listOf(overview("com.a")),
        )

        assertEquals(listOf("com.a"), result.map { it.app.packageName })
    }

    @Test
    fun `ignores apps the user does not watch`() {
        val result = SlotOpenPolicy.notifiable(
            transitions = listOf(opened("com.a")),
            rows = listOf(overview("com.a", watching = false)),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores transitions that do not end open`() {
        val result = SlotOpenPolicy.notifiable(
            transitions = listOf(
                StatusTransition("com.a", from = LiveBetaStatus.OPEN, to = LiveBetaStatus.FULL),
            ),
            rows = listOf(overview("com.a")),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores apps the scan already sees as joined`() {
        // Joining can happen in the same moment the program opens (leaveForm implies
        // OPEN); telling a tester that space is available would be noise.
        val result = SlotOpenPolicy.notifiable(
            transitions = listOf(opened("com.a")),
            rows = listOf(overview("com.a", membership = ObservedMembership.JOINED)),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores transitions for apps missing from the overview`() {
        val result = SlotOpenPolicy.notifiable(
            transitions = listOf(opened("com.gone")),
            rows = emptyList(),
        )

        assertTrue(result.isEmpty())
    }
}
