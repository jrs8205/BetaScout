package org.jarsi.betascout.data.betadb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            isValid = { true },
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
            isValid = { true },
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
            isValid = { true },
        )

        assertEquals("BUNDLED", provider.catalogJson())
    }

    @Test
    fun `an invalid remote catalog is discarded, not cached`() = runTest {
        // The catalog Worker answers a missing KV key with HTTP 200 and an empty
        // catalog; accepting it would leave a fresh install with zero programs
        // and poison the cache.
        var cached: String? = "CACHED"
        val provider = CatalogProvider(
            fetchRemote = { "EMPTY" },
            readCache = { cached },
            writeCache = { cached = it },
            readBundled = { "BUNDLED" },
            isValid = { it != "EMPTY" },
        )

        assertEquals("CACHED", provider.catalogJson())
        assertEquals("CACHED", cached)
    }

    @Test
    fun `an invalid cached catalog falls back to bundled`() = runTest {
        val provider = CatalogProvider(
            fetchRemote = { null },
            readCache = { "CORRUPT" },
            writeCache = {},
            readBundled = { "BUNDLED" },
            isValid = { it != "CORRUPT" },
        )

        assertEquals("BUNDLED", provider.catalogJson())
    }
}
