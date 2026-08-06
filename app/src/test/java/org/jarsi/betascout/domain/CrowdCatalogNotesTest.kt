package org.jarsi.betascout.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrowdCatalogNotesTest {

    @Test
    fun `matches the exact notes text the crowd harvester writes`() {
        assertTrue(isCrowdCatalogNotes("Reported by users, confirmed via Google Play"))
    }

    @Test
    fun `rejects null and blank notes`() {
        assertFalse(isCrowdCatalogNotes(null))
        assertFalse(isCrowdCatalogNotes(""))
    }

    @Test
    fun `rejects other notes so curated entries keep their original text`() {
        assertFalse(isCrowdCatalogNotes("Curated seed entry"))
        assertFalse(isCrowdCatalogNotes("reported by users, confirmed via google play"))
        assertFalse(isCrowdCatalogNotes("Reported by users, confirmed via Google Play "))
    }
}
