package org.jarsi.betascout.data.betadb

import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.LiveBetaStatus
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

    @Test
    fun `parses live status and check timestamp from a v2 catalog entry`() {
        val json = """
            {
              "version": 2,
              "programs": [
                {
                  "packageName": "com.instagram.android",
                  "appName": "Instagram",
                  "liveStatus": "OPEN",
                  "statusCheckedAt": 1720000000000
                }
              ]
            }
        """.trimIndent()

        val program = BetaSeedParser.parse(json).single()

        assertEquals(LiveBetaStatus.OPEN, program.liveStatus)
        assertEquals(1720000000000L, program.statusCheckedAt)
    }

    @Test
    fun `live status defaults to UNKNOWN and check timestamp to null`() {
        val json = """{"programs":[{"packageName":"com.whatsapp"}]}"""

        val program = BetaSeedParser.parse(json).single()

        assertEquals(LiveBetaStatus.UNKNOWN, program.liveStatus)
        assertNull(program.statusCheckedAt)
    }

    @Test
    fun `unknown live status value falls back to UNKNOWN`() {
        val json = """{"programs":[{"packageName":"com.example","liveStatus":"BRAND_NEW"}]}"""

        assertEquals(LiveBetaStatus.UNKNOWN, BetaSeedParser.parse(json).single().liveStatus)
    }

    @Test
    fun `entries are tagged with the requested source`() {
        val json = """{"programs":[{"packageName":"com.spotify.music"}]}"""

        assertEquals(BetaSource.REMOTE, BetaSeedParser.parse(json, source = BetaSource.REMOTE).single().source)
    }

    @Test
    fun `source defaults to BUNDLED`() {
        val json = """{"programs":[{"packageName":"com.spotify.music"}]}"""

        assertEquals(BetaSource.BUNDLED, BetaSeedParser.parse(json).single().source)
    }
}
