package org.jarsi.betavahti.data.betadb

import org.jarsi.betavahti.domain.BetaSource
import org.jarsi.betavahti.domain.KnownBetaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BetaSeedParserTest {

    @Test
    fun `parses seed entries into bundled beta programs`() {
        val json = """
            {
              "version": 1,
              "programs": [
                {
                  "packageName": "com.whatsapp",
                  "appName": "WhatsApp Messenger",
                  "knownStatus": "OFTEN_FULL",
                  "notes": "Beta fills up quickly"
                }
              ]
            }
        """.trimIndent()

        val result = BetaSeedParser.parse(json)

        assertEquals(1, result.size)
        val program = result.single()
        assertEquals("com.whatsapp", program.packageName)
        assertEquals("WhatsApp Messenger", program.appName)
        assertEquals(KnownBetaStatus.OFTEN_FULL, program.knownStatus)
        assertEquals("Beta fills up quickly", program.notes)
        assertEquals(BetaSource.BUNDLED, program.source)
    }

    @Test
    fun `optional fields default sensibly`() {
        val json = """{"programs":[{"packageName":"com.android.chrome"}]}"""

        val program = BetaSeedParser.parse(json).single()

        assertEquals("com.android.chrome", program.appName)
        assertEquals(KnownBetaStatus.UNKNOWN, program.knownStatus)
        assertNull(program.testingUrl)
        assertNull(program.notes)
    }

    @Test
    fun `unknown status value falls back to UNKNOWN`() {
        val json = """{"programs":[{"packageName":"com.example","knownStatus":"WILD_NEW_STATUS"}]}"""

        assertEquals(KnownBetaStatus.UNKNOWN, BetaSeedParser.parse(json).single().knownStatus)
    }

    @Test
    fun `entries with blank package name are skipped`() {
        val json = """{"programs":[{"packageName":"  "},{"packageName":"com.whatsapp"}]}"""

        assertEquals(listOf("com.whatsapp"), BetaSeedParser.parse(json).map { it.packageName })
    }

    @Test
    fun `unknown json keys are ignored`() {
        val json = """{"futureField":true,"programs":[{"packageName":"com.whatsapp","futureFlag":1}]}"""

        assertEquals(1, BetaSeedParser.parse(json).size)
    }
}
