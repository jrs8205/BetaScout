package org.jarsi.betascout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogNotesTest {

    @Test
    fun `matches the exact notes text of every harvest source`() {
        assertEquals(
            CatalogNotesKind.CROWD,
            catalogNotesKind("Reported by users, confirmed via Google Play"),
        )
        assertEquals(
            CatalogNotesKind.GPLAY_VERIFIED,
            catalogNotesKind("Confirmed via Google Play"),
        )
        assertEquals(
            CatalogNotesKind.APKMIRROR_SIGHTING,
            catalogNotesKind("Beta build seen on APKMirror"),
        )
    }

    @Test
    fun `returns null for null and blank notes`() {
        assertNull(catalogNotesKind(null))
        assertNull(catalogNotesKind(""))
    }

    @Test
    fun `returns null for other notes so curated entries keep their original text`() {
        assertNull(catalogNotesKind("Curated seed entry"))
        assertNull(catalogNotesKind("reported by users, confirmed via google play"))
        assertNull(catalogNotesKind("Reported by users, confirmed via Google Play "))
        assertNull(catalogNotesKind("confirmed via Google Play"))
    }
}
