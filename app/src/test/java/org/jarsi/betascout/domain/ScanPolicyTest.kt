package org.jarsi.betascout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPolicyTest {

    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun `a never-checked package is always due`() {
        assertTrue(ScanPolicy.isDue(LiveBetaStatus.UNKNOWN, checkedAt = null, now = now))
    }

    @Test
    fun `a recently checked open program is not due`() {
        val checkedAt = now - 1 * hour
        assertFalse(ScanPolicy.isDue(LiveBetaStatus.OPEN, checkedAt, now))
    }

    @Test
    fun `an open program checked over a day ago is due`() {
        val checkedAt = now - 25 * hour
        assertTrue(ScanPolicy.isDue(LiveBetaStatus.OPEN, checkedAt, now))
    }

    @Test
    fun `a program with no testing page is rechecked far less often than an open one`() {
        assertTrue(ScanPolicy.ttlMillis(LiveBetaStatus.NO_PROGRAM) > ScanPolicy.ttlMillis(LiveBetaStatus.OPEN))
    }

    @Test
    fun `a closed program is rechecked less often than an open one`() {
        assertTrue(ScanPolicy.ttlMillis(LiveBetaStatus.CLOSED) > ScanPolicy.ttlMillis(LiveBetaStatus.OPEN))
    }

    @Test
    fun `selectDue never returns more than the cap`() {
        val candidates = (1..50).map {
            ScanCandidate("com.app$it", LiveBetaStatus.UNKNOWN, checkedAt = null)
        }

        assertEquals(30, ScanPolicy.selectDue(candidates, now, cap = 30).size)
    }

    @Test
    fun `selectDue excludes packages that are not yet due`() {
        val fresh = ScanCandidate("com.fresh", LiveBetaStatus.OPEN, checkedAt = now - 1 * hour)
        val stale = ScanCandidate("com.stale", LiveBetaStatus.OPEN, checkedAt = now - 48 * hour)

        val selected = ScanPolicy.selectDue(listOf(fresh, stale), now, cap = 10)

        assertEquals(listOf("com.stale"), selected.map { it.packageName })
    }

    @Test
    fun `selectDue prioritizes never-checked packages over stale checked ones`() {
        val stale = ScanCandidate("com.stale", LiveBetaStatus.OPEN, checkedAt = now - 100 * hour)
        val fresh = ScanCandidate("com.new", LiveBetaStatus.UNKNOWN, checkedAt = null)

        val selected = ScanPolicy.selectDue(listOf(stale, fresh), now, cap = 1)

        assertEquals(listOf("com.new"), selected.map { it.packageName })
    }

    @Test
    fun `selectDue with ignoreTtl includes fresh packages for a forced rescan`() {
        val fresh = ScanCandidate("com.fresh", LiveBetaStatus.OPEN, checkedAt = now - 1 * hour)
        val stale = ScanCandidate("com.stale", LiveBetaStatus.OPEN, checkedAt = now - 48 * hour)

        val selected = ScanPolicy.selectDue(listOf(fresh, stale), now, cap = 10, ignoreTtl = true)

        assertEquals(listOf("com.stale", "com.fresh"), selected.map { it.packageName })
    }

    @Test
    fun `among checked packages selectDue takes the most stale first`() {
        val a = ScanCandidate("com.a", LiveBetaStatus.OPEN, checkedAt = now - 30 * hour)
        val b = ScanCandidate("com.b", LiveBetaStatus.OPEN, checkedAt = now - 90 * hour)

        val selected = ScanPolicy.selectDue(listOf(a, b), now, cap = 1)

        assertEquals(listOf("com.b"), selected.map { it.packageName })
    }
}
