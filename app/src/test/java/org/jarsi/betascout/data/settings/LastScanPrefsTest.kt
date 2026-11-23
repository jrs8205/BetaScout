package org.jarsi.betascout.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastScanPrefsTest {

    private val summary = LastScanInfo(
        at = 1_000L,
        checked = 3,
        joined = 1,
        notJoined = 2,
        failed = 1,
        noProgram = 4,
        failureReason = "timeout",
        scanType = ScanType.BACKGROUND,
        accountKey = "a@example.com",
    )

    @Test
    fun `a summary is read back as written, with the account it belongs to`() {
        val prefs = mutablePreferencesOf()

        LastScanPrefs.write(prefs, summary)

        assertEquals(summary, LastScanPrefs.read(prefs))
    }

    @Test
    fun `nothing stored reads as no summary`() {
        assertNull(LastScanPrefs.read(mutablePreferencesOf()))
    }

    @Test
    fun `a summary stored by an earlier version reads with no owner and a short reason`() {
        // 0.6.9 stored neither the account nor a bounded reason, and the reason
        // goes straight onto the main screen, in a line that does not scroll:
        // an exception message of thousands of characters would push the whole
        // app list off the screen right after the update.
        val prefs = mutablePreferencesOf(
            LastScanPrefs.at to 1_000L,
            LastScanPrefs.failed to 5,
            LastScanPrefs.failureReason to "java.io.IOException: " + "x".repeat(20_000),
        )

        val read = LastScanPrefs.read(prefs)!!

        assertNull(read.accountKey)
        assertEquals(5, read.failed)
        assertEquals(ScanType.MANUAL, read.scanType)
        assertTrue(read.failureReason!!.length <= 200)
        assertTrue(read.failureReason!!.startsWith("java.io.IOException: xxx"))
    }

    @Test
    fun `a long reason is cut when written, too`() {
        val prefs = mutablePreferencesOf()

        LastScanPrefs.write(prefs, summary.copy(failureReason = "y".repeat(5_000)))

        assertTrue(prefs[LastScanPrefs.failureReason]!!.length <= 200)
    }

    @Test
    fun `a short reason, or none, passes through unchanged`() {
        assertEquals("HTTP 429", boundedFailureReason("HTTP 429"))
        assertNull(boundedFailureReason(null))

        val prefs = mutablePreferencesOf()
        LastScanPrefs.write(prefs, summary.copy(failureReason = null))
        assertNull(LastScanPrefs.read(prefs)!!.failureReason)
    }

    @Test
    fun `clearing leaves nothing of the summary behind`() {
        val prefs = mutablePreferencesOf()
        LastScanPrefs.write(prefs, summary)

        LastScanPrefs.clear(prefs)

        assertNull(LastScanPrefs.read(prefs))
        assertTrue(prefs.asMap().isEmpty())
    }

    @Test
    fun `a summary keeps the place of its scan, and one stored without reads as the oldest`() {
        val prefs = mutablePreferencesOf()
        val info = LastScanInfo(at = 5L, checked = 1, joined = 1, notJoined = 0, accountKey = "a@example.com", order = 42)

        LastScanPrefs.write(prefs, info)
        assertEquals(info, LastScanPrefs.read(prefs))

        prefs.remove(LastScanPrefs.order)
        assertEquals(0L, LastScanPrefs.read(prefs)!!.order)
    }
}
