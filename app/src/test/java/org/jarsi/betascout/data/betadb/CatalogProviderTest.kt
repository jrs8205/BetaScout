package org.jarsi.betascout.data.betadb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogProviderTest {

    @Test
    fun `remote success is returned and cached`() = runTest {
        var cached: String? = null
        val provider = CatalogProvider(
            fetchRemote = { "REMOTE" },
            readCache = { cached },
            writeCache = { cached = it },
            readBundled = { "BUNDLED" },
        )

        assertEquals("REMOTE", provider.catalogJson())
        assertEquals("REMOTE", cached)
    }

    @Test
    fun `remote failure falls back to cache without overwriting it`() = runTest {
        var writes = 0
        val provider = CatalogProvider(
            fetchRemote = { null },
            readCache = { "CACHED" },
            writeCache = { writes++ },
            readBundled = { "BUNDLED" },
        )

        assertEquals("CACHED", provider.catalogJson())
        assertEquals(0, writes)
    }

    @Test
    fun `remote and cache both empty falls back to bundled`() = runTest {
        val provider = CatalogProvider(
            fetchRemote = { null },
            readCache = { null },
            writeCache = {},
            readBundled = { "BUNDLED" },
        )

        assertEquals("BUNDLED", provider.catalogJson())
    }
}
