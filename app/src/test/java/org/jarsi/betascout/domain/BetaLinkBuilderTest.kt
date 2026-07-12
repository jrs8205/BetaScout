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
    fun `the Play Store's own testing page is aliased to the Google system services program`() {
        // com.android.vending's testing page 302-redirects to the GMS program page
        // ("Google system services") and the direct fetch times out on-device every
        // time; pointing at the redirect target gives the real status in one hop.
        assertEquals(
            "https://play.google.com/apps/testing/com.google.android.gms",
            BetaLinkBuilder.testingUrl("com.android.vending"),
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
