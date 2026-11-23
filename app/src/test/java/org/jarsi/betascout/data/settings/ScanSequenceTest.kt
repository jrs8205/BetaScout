package org.jarsi.betascout.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order scan events are given. It has to hold across records written out of
 * order, across a restart of the process, and whatever the device's clock does
 * — so no clock is involved at all.
 */
class ScanSequenceTest {

    private val account = "a@example.com"

    @Test
    fun `records written out of order keep the order of their events`() {
        val sequence = ScanSequence()
        val prefs = mutablePreferencesOf()
        val first = sequence.ticket()
        val second = sequence.ticket()

        // The later event's record is written first.
        val secondOrder = sequence.orderOf(second, prefs, heldElsewhere = 0)
        val firstOrder = sequence.orderOf(first, prefs, heldElsewhere = 0)

        assertTrue("$firstOrder should be below $secondOrder", firstOrder < secondOrder)
    }

    @Test
    fun `a new process continues above everything stored`() {
        // Tickets start from one again in every process. What an earlier
        // process stored is older than anything that happens now.
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordRefusal(prefs, ScanRefusal("scan_blocked", account, order = 40))
        ScanOutcomes.recordDone(prefs, account, order = 57, summary = null)
        val sequence = ScanSequence()

        val order = sequence.orderOf(sequence.ticket(), prefs, heldElsewhere = 0)

        assertTrue("$order should be above 57", order > 57)
    }

    @Test
    fun `where a process starts from is settled once`() {
        // Decided when the process first writes a record, and not again: the
        // records it writes itself raise the stored maximum, and an earlier
        // ticket written after a later one must still come out below it.
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, account, order = 10, summary = null)
        val sequence = ScanSequence()
        val first = sequence.ticket()
        val second = sequence.ticket()
        val third = sequence.ticket()

        val thirdOrder = sequence.orderOf(third, prefs, heldElsewhere = 0)
        ScanOutcomes.recordDone(prefs, account, order = thirdOrder, summary = null)
        val firstOrder = sequence.orderOf(first, prefs, heldElsewhere = 0)
        val secondOrder = sequence.orderOf(second, prefs, heldElsewhere = 0)

        assertEquals(13L, thirdOrder)
        assertEquals(11L, firstOrder)
        assertEquals(12L, secondOrder)
    }

    @Test
    fun `a new process continues above an order that only reached the work record`() {
        // The earlier process could not write its record. The order it gave
        // the failed request went into that request's work result and nowhere
        // else: the store still ends at 10.
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, account, order = 10, summary = null)
        val earlier = ScanSequence()
        val unrecorded = earlier.orderOf(earlier.ticket(), prefs, heldElsewhere = 0)
        val sequence = ScanSequence()

        // What the new process finds: the same store, and the work record.
        val order = sequence.orderOf(sequence.ticket(), prefs, heldElsewhere = unrecorded)

        assertEquals(11L, unrecorded)
        assertTrue("$order should be above $unrecorded", order > unrecorded)
    }

    @Test
    fun `an order kept elsewhere that is below the stored ones changes nothing`() {
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, account, order = 57, summary = null)
        val sequence = ScanSequence()

        assertEquals(58L, sequence.orderOf(sequence.ticket(), prefs, heldElsewhere = 40))
    }

    @Test
    fun `what is kept elsewhere is taken in once, like what is stored`() {
        // The process's own unrecorded orders end up in the work record too.
        // They must not push its later tickets further up a second time: an
        // earlier ticket resolved afterwards would land above a later one.
        val prefs = mutablePreferencesOf()
        val sequence = ScanSequence()
        val first = sequence.ticket()
        val second = sequence.ticket()

        val secondOrder = sequence.orderOf(second, prefs, heldElsewhere = 20)
        val firstOrder = sequence.orderOf(first, prefs, heldElsewhere = secondOrder)

        assertEquals(22L, secondOrder)
        assertEquals(21L, firstOrder)
    }

    @Test
    fun `once a process has started counting, a ticket needs no store to get its place`() {
        // The store is read for one thing only: where the process starts.
        val prefs = mutablePreferencesOf()
        ScanOutcomes.recordDone(prefs, account, order = 10, summary = null)
        val sequence = ScanSequence()
        val first = sequence.ticket()
        val second = sequence.ticket()

        // Not started yet: there is nothing to go by.
        assertEquals(null, sequence.settledOrderOf(second))
        sequence.orderOf(first, prefs, heldElsewhere = 0)

        assertEquals(12L, sequence.settledOrderOf(second))
    }
}
