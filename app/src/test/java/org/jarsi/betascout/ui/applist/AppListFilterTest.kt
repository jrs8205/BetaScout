package org.jarsi.betascout.ui.applist

import org.jarsi.betascout.R
import org.jarsi.betascout.domain.AppBetaOverview
import org.jarsi.betascout.domain.BetaObservation
import org.jarsi.betascout.domain.BetaProgramInfo
import org.jarsi.betascout.domain.InstalledAppInfo
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership
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
    hasLauncher: Boolean = !isSystem,
    installerPackage: String? = null,
    betaStatus: KnownBetaStatus? = null,
    watching: Boolean = false,
    userState: UserBetaState = UserBetaState.UNKNOWN,
    installedVersionCode: Long = 1L,
    productionVersionCode: Long? = null,
    observedLiveStatus: LiveBetaStatus? = null,
    observedMembership: ObservedMembership = ObservedMembership.UNKNOWN,
    checkedAt: Long = 0L,
) = AppBetaOverview(
    app = InstalledAppInfo(
        packageName, label, "1.0", installedVersionCode, installerPackage, isSystem, hasLauncher, 0L,
    ),
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
    observation = observedLiveStatus?.let {
        BetaObservation(
            accountKey = "user@example.com",
            packageName = packageName,
            liveStatus = it,
            observedMembership = observedMembership,
            checkedAt = checkedAt,
        )
    },
)

class AppListFilterTest {

    @Test
    fun `status line and badge follow membership and live status`() {
        val joined = row(
            "com.joined",
            observedLiveStatus = LiveBetaStatus.OPEN,
            observedMembership = ObservedMembership.JOINED,
        )
        assertEquals(R.string.status_line_joined, joined.statusLineRes())
        assertEquals(StatusBadge.JOINED, joined.statusBadge())

        val manuallyJoined = row("com.manual", userState = UserBetaState.JOINED)
        assertEquals(R.string.status_line_joined, manuallyJoined.statusLineRes())
        assertEquals(StatusBadge.JOINED, manuallyJoined.statusBadge())

        val open = row(
            "com.open",
            observedLiveStatus = LiveBetaStatus.OPEN,
            observedMembership = ObservedMembership.NOT_JOINED,
        )
        assertEquals(R.string.status_line_open, open.statusLineRes())
        assertEquals(StatusBadge.OPEN, open.statusBadge())

        val full = row("com.full", observedLiveStatus = LiveBetaStatus.FULL)
        assertEquals(R.string.status_line_full, full.statusLineRes())
        assertEquals(StatusBadge.FULL, full.statusBadge())

        val closed = row("com.closed", observedLiveStatus = LiveBetaStatus.CLOSED)
        assertEquals(R.string.status_line_closed, closed.statusLineRes())
        assertEquals(StatusBadge.CLOSED, closed.statusBadge())

        // Known from the catalog only: there is a program, but no live reading.
        val catalogOnly = row("com.catalog", betaStatus = KnownBetaStatus.OFTEN_FULL)
        assertEquals(R.string.status_line_has_beta, catalogOnly.statusLineRes())
        assertEquals(StatusBadge.BETA, catalogOnly.statusBadge())

        val none = row("com.none")
        assertEquals(R.string.status_line_no_beta, none.statusLineRes())
        assertEquals(null, none.statusBadge())
    }

    @Test
    fun `openBetas lists open not-joined betas newest first capped at ten`() {
        val rows = buildList {
            add(
                row(
                    "com.joined",
                    observedLiveStatus = LiveBetaStatus.OPEN,
                    observedMembership = ObservedMembership.JOINED,
                ),
            )
            add(row("com.full", observedLiveStatus = LiveBetaStatus.FULL))
            (1..12).forEach {
                add(
                    row(
                        "com.open$it",
                        observedLiveStatus = LiveBetaStatus.OPEN,
                        observedMembership = ObservedMembership.NOT_JOINED,
                        checkedAt = it.toLong(),
                    ),
                )
            }
        }

        val open = openBetas(rows)

        assertEquals(10, open.size)
        assertEquals("com.open12", open.first().app.packageName)
        assertTrue(open.none { it.app.packageName == "com.joined" })
        assertTrue(open.none { it.app.packageName == "com.full" })
    }

    @Test
    fun `tabCounts groups the filtered rows by membership tab`() {
        val rows = listOf(
            row(
                "com.joined",
                observedLiveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
            ),
            row("com.available", betaStatus = KnownBetaStatus.OFTEN_FULL),
            row("com.none"),
            // Framework package: filtered out entirely, must not count anywhere.
            row("com.android.providers.media", isSystem = true, hasLauncher = false),
        )

        val counts = tabCounts(filterApps(rows, AppFilters()))

        assertEquals(1, counts[BetaMembership.JOINED] ?: 0)
        assertEquals(1, counts[BetaMembership.AVAILABLE] ?: 0)
        assertEquals(1, counts[BetaMembership.NONE] ?: 0)
    }

    @Test
    fun `tab counts follow the active search query`() {
        val rows = listOf(
            row("com.whatsapp", label = "WhatsApp", betaStatus = KnownBetaStatus.OFTEN_FULL),
            row("com.spotify", label = "Spotify"),
        )

        val counts = tabCounts(filterApps(rows, AppFilters(query = "whats")))

        assertEquals(mapOf(BetaMembership.AVAILABLE to 1), counts)
    }

    @Test
    fun `launchable system apps are listed but framework packages are not`() {
        val rows = listOf(
            row("com.user.app"),
            row("com.google.android.gm", isSystem = true, hasLauncher = true),
            row("com.android.providers.media", isSystem = true, hasLauncher = false),
        )

        assertEquals(
            listOf("com.user.app", "com.google.android.gm"),
            filterApps(rows, AppFilters()).map { it.app.packageName },
        )
    }

    @Test
    fun `store-updated system app without a launcher is listed`() {
        // WebView and Play services have beta programs but no launcher icon.
        val rows = listOf(
            row(
                "com.google.android.webview",
                isSystem = true,
                hasLauncher = false,
                installerPackage = "com.android.vending",
            ),
        )

        assertEquals(1, filterApps(rows, AppFilters()).size)
    }

    @Test
    fun `onlySystem keeps only system apps`() {
        val rows = listOf(
            row("com.user.app"),
            row("com.google.android.gm", isSystem = true, hasLauncher = true),
        )

        assertEquals(
            listOf("com.google.android.gm"),
            filterApps(rows, AppFilters(onlySystem = true)).map { it.app.packageName },
        )
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
    fun `hasKnownBeta accepts a scraped beta even when catalog is missing`() {
        assertTrue(row("a", observedLiveStatus = LiveBetaStatus.OPEN).hasKnownBeta())
        assertTrue(row("b", observedLiveStatus = LiveBetaStatus.FULL).hasKnownBeta())
        assertTrue(row("c", observedLiveStatus = LiveBetaStatus.CLOSED).hasKnownBeta())
    }

    @Test
    fun `hasKnownBeta lets scraped no-program override catalog`() {
        assertFalse(
            row(
                "a",
                betaStatus = KnownBetaStatus.OFTEN_OPEN,
                observedLiveStatus = LiveBetaStatus.NO_PROGRAM,
            ).hasKnownBeta(),
        )
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
    fun `scraped joined membership is JOINED even without catalog`() {
        assertEquals(
            BetaMembership.JOINED,
            row(
                "a",
                observedLiveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.JOINED,
            ).betaMembership(),
        )
    }

    @Test
    fun `scraped not joined membership is AVAILABLE even without catalog`() {
        assertEquals(
            BetaMembership.AVAILABLE,
            row(
                "a",
                observedLiveStatus = LiveBetaStatus.OPEN,
                observedMembership = ObservedMembership.NOT_JOINED,
            ).betaMembership(),
        )
    }

    @Test
    fun `a manual joined marking survives a scraped no-program reading`() {
        // The user joined on the web (or with a different account); one scrape that
        // reads NO_PROGRAM must not hide the app from the Joined tab.
        val app = row(
            "a",
            userState = UserBetaState.JOINED,
            observedLiveStatus = LiveBetaStatus.NO_PROGRAM,
        )
        assertTrue(app.hasKnownBeta())
        assertEquals(BetaMembership.JOINED, app.betaMembership())
    }

    @Test
    fun `a scraped joined membership survives a no-program live status`() {
        val app = row(
            "a",
            observedLiveStatus = LiveBetaStatus.NO_PROGRAM,
            observedMembership = ObservedMembership.JOINED,
        )
        assertEquals(BetaMembership.JOINED, app.betaMembership())
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

    @Test
    fun `join action is offered when a beta is available and the user is not in it`() {
        val available = row(
            "com.a",
            observedLiveStatus = LiveBetaStatus.OPEN,
            observedMembership = ObservedMembership.NOT_JOINED,
        )

        assertTrue(available.canJoinBeta())
    }

    @Test
    fun `join action is hidden when already joined or there is no beta`() {
        val joined = row(
            "com.a",
            observedLiveStatus = LiveBetaStatus.OPEN,
            observedMembership = ObservedMembership.JOINED,
        )
        val noBeta = row("com.b")

        assertFalse(joined.canJoinBeta())
        assertFalse(noBeta.canJoinBeta())
    }

}
