package org.jarsi.betascout.ui.applist

import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.BetaProgramInfo
import org.jarsi.betascout.domain.InstalledAppInfo
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.UserBetaState
import org.jarsi.betascout.domain.UserBetaStatusInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun row(
    packageName: String,
    label: String = packageName,
    isSystem: Boolean = false,
    betaStatus: KnownBetaStatus? = null,
    watching: Boolean = false,
    userState: UserBetaState = UserBetaState.UNKNOWN,
    installedVersionCode: Long = 1L,
    productionVersionCode: Long? = null,
) = AppBetaOverview(
    app = InstalledAppInfo(packageName, label, "1.0", installedVersionCode, null, isSystem, 0L),
    betaProgram = betaStatus?.let {
        BetaProgramInfo(
            packageName = packageName,
            appName = label,
            knownStatus = it,
            productionVersionCode = productionVersionCode,
        )
    },
    userStatus = if (watching || userState != UserBetaState.UNKNOWN) {
        UserBetaStatusInfo(packageName = packageName, watching = watching, state = userState)
    } else {
        null
    },
)

class AppListFilterTest {

    @Test
    fun `system apps are hidden by default and shown on demand`() {
        val rows = listOf(row("com.user.app"), row("com.android.sys", isSystem = true))

        assertEquals(listOf("com.user.app"), filterApps(rows, AppFilters()).map { it.app.packageName })
        assertEquals(2, filterApps(rows, AppFilters(showSystem = true)).size)
    }

    @Test
    fun `onlyBeta keeps apps with a known beta program`() {
        val rows = listOf(
            row("com.beta", betaStatus = KnownBetaStatus.OFTEN_OPEN),
            row("com.nobeta"),
            row("com.noprogram", betaStatus = KnownBetaStatus.NO_PROGRAM),
        )

        assertEquals(
            listOf("com.beta"),
            filterApps(rows, AppFilters(onlyBeta = true)).map { it.app.packageName },
        )
    }

    @Test
    fun `onlyWatched keeps watched apps`() {
        val rows = listOf(row("com.watched", watching = true), row("com.other"))

        assertEquals(
            listOf("com.watched"),
            filterApps(rows, AppFilters(onlyWatched = true)).map { it.app.packageName },
        )
    }

    @Test
    fun `query matches label and package name case-insensitively`() {
        val rows = listOf(
            row("com.whatsapp", label = "WhatsApp"),
            row("org.telegram.messenger", label = "Telegram"),
        )

        assertEquals(1, filterApps(rows, AppFilters(query = "whats")).size)
        assertEquals(1, filterApps(rows, AppFilters(query = "TELEGRAM")).size)
        assertEquals(1, filterApps(rows, AppFilters(query = "messenger")).size)
        assertEquals(0, filterApps(rows, AppFilters(query = "signal")).size)
    }

    @Test
    fun `hasKnownBeta requires program that is not marked NO_PROGRAM`() {
        assertTrue(row("a", betaStatus = KnownBetaStatus.UNKNOWN).hasKnownBeta())
        assertTrue(row("b", betaStatus = KnownBetaStatus.OFTEN_FULL).hasKnownBeta())
        assertFalse(row("c", betaStatus = KnownBetaStatus.NO_PROGRAM).hasKnownBeta())
        assertFalse(row("d").hasKnownBeta())
    }

    @Test
    fun `beta app the user has not joined is AVAILABLE`() {
        assertEquals(
            BetaMembership.AVAILABLE,
            row("a", betaStatus = KnownBetaStatus.OFTEN_OPEN).betaMembership(),
        )
    }

    @Test
    fun `beta app the user marked joined is JOINED`() {
        assertEquals(
            BetaMembership.JOINED,
            row("a", betaStatus = KnownBetaStatus.OFTEN_OPEN, userState = UserBetaState.JOINED).betaMembership(),
        )
    }

    @Test
    fun `beta app marked not-joined or full stays AVAILABLE`() {
        assertEquals(
            BetaMembership.AVAILABLE,
            row("a", betaStatus = KnownBetaStatus.OFTEN_OPEN, userState = UserBetaState.NOT_JOINED).betaMembership(),
        )
        assertEquals(
            BetaMembership.AVAILABLE,
            row("b", betaStatus = KnownBetaStatus.OFTEN_FULL, userState = UserBetaState.FULL).betaMembership(),
        )
    }

    @Test
    fun `app with no beta program is NONE`() {
        assertEquals(BetaMembership.NONE, row("a").betaMembership())
        assertEquals(BetaMembership.NONE, row("b", betaStatus = KnownBetaStatus.NO_PROGRAM).betaMembership())
    }

    @Test
    fun `beta app the user marked as having no program is NONE`() {
        assertEquals(
            BetaMembership.NONE,
            row("a", betaStatus = KnownBetaStatus.UNKNOWN, userState = UserBetaState.NO_PROGRAM).betaMembership(),
        )
    }

}
