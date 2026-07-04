package org.jarsi.betascout.data.db

import org.jarsi.betascout.domain.BetaProgramInfo
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.InstalledAppInfo
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.UserBetaState
import org.jarsi.betascout.domain.UserBetaStatusInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test
    fun `installed app survives entity round trip`() {
        val domain = InstalledAppInfo(
            packageName = "com.whatsapp",
            label = "WhatsApp",
            versionName = "2.26.1",
            versionCode = 261L,
            installerPackage = "com.android.vending",
            isSystem = false,
            lastScanned = 42L,
        )
        assertEquals(domain, domain.toEntity().toDomain())
    }

    @Test
    fun `beta program survives entity round trip`() {
        val domain = BetaProgramInfo(
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            testingUrl = "https://play.google.com/apps/testing/com.whatsapp",
            knownStatus = KnownBetaStatus.OFTEN_FULL,
            notes = "Beta fills up quickly",
            source = BetaSource.BUNDLED,
        )
        assertEquals(domain, domain.toEntity().toDomain())
    }

    @Test
    fun `user beta status survives entity round trip`() {
        val domain = UserBetaStatusInfo(
            packageName = "org.telegram.messenger",
            state = UserBetaState.JOINED,
            watching = true,
            reminderIntervalDays = 14,
            lastCheckedByUser = 1_720_000_000_000L,
            userNote = "Joined 2026-06",
            lastRemindedAt = 1_720_000_100_000L,
        )
        assertEquals(domain, domain.toEntity().toDomain())
    }
}
