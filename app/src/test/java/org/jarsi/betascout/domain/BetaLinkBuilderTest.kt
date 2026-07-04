package org.jarsi.betascout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BetaLinkBuilderTest {

    @Test
    fun `testingUrl builds open testing page url from package name`() {
        assertEquals(
            "https://play.google.com/apps/testing/com.whatsapp",
            BetaLinkBuilder.testingUrl("com.whatsapp")
        )
    }

    @Test
    fun `playStoreUri builds market deep link from package name`() {
        assertEquals(
            "market://details?id=org.telegram.messenger",
            BetaLinkBuilder.playStoreUri("org.telegram.messenger")
        )
    }

    @Test
    fun `playStoreWebUrl builds browser fallback url from package name`() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.android.chrome",
            BetaLinkBuilder.playStoreWebUrl("com.android.chrome")
        )
    }

    @Test
    fun `package name is trimmed before building url`() {
        assertEquals(
            "https://play.google.com/apps/testing/com.whatsapp",
            BetaLinkBuilder.testingUrl(" com.whatsapp ")
        )
    }

    @Test
    fun `blank package name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BetaLinkBuilder.testingUrl("   ")
        }
    }
}
