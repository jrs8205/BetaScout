package org.jarsi.betascout.data.betadb

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.jarsi.betascout.domain.BetaProgramInfo
import org.jarsi.betascout.domain.BetaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogProviderTest {

    private var now = 1_000_000L
    private var remoteFetches = 0

    /** A toy catalog format: the text is the single program's package name;
     *  "EMPTY" and "CORRUPT" are the two shapes of an unusable catalog. */
    private val parse: (String, BetaSource) -> List<BetaProgramInfo>? = { text, source ->
        when (text) {
            "EMPTY", "CORRUPT" -> null
            else -> listOf(BetaProgramInfo(packageName = text, appName = text, source = source))
        }
    }

    private fun provider(
        fetchRemote: suspend () -> String?,
        readCache: () -> String?,
        writeCache: (String) -> Unit = {},
        readBundled: () -> String = { "BUNDLED" },
        remoteFreshFor: Long = 3_600_000L,
    ) = CatalogProvider(
        fetchRemote = { remoteFetches++; fetchRemote() },
        readCache = readCache,
        writeCache = writeCache,
        readBundled = readBundled,
        parse = parse,
        clock = { now },
        remoteFreshFor = remoteFreshFor,
    )

    private fun CatalogSnapshot?.singlePackage(): String = this!!.programs.single().packageName

    /** Hands the catalog out and confirms it reached the database, like the seeder does. */
    private suspend fun CatalogProvider.catalogApplied(): CatalogSnapshot? =
        catalog()?.also { markApplied(it) }

    @Test
    fun `remote success is returned, cached and marked as remote data`() = runTest {
        var cached: String? = null
        val provider = provider(
            fetchRemote = { "REMOTE" },
            readCache = { cached },
            writeCache = { cached = it },
        )

        val snapshot = provider.catalog()

        assertEquals("REMOTE", snapshot.singlePackage())
        assertEquals(BetaSource.REMOTE, snapshot!!.source)
        assertEquals(BetaSource.REMOTE, snapshot.programs.single().source)
        assertEquals("REMOTE", cached)
    }

    @Test
    fun `remote failure falls back to cache without overwriting it`() = runTest {
        var writes = 0
        val provider = provider(
            fetchRemote = { null },
            readCache = { "CACHED" },
            writeCache = { writes++ },
        )

        val snapshot = provider.catalog()

        assertEquals("CACHED", snapshot.singlePackage())
        // A cached copy is a previously downloaded catalog, so it is still remote data.
        assertEquals(BetaSource.REMOTE, snapshot!!.source)
        assertEquals(0, writes)
    }

    @Test
    fun `remote and cache both empty falls back to the bundled seed`() = runTest {
        val provider = provider(fetchRemote = { null }, readCache = { null })

        val snapshot = provider.catalog()

        assertEquals("BUNDLED", snapshot.singlePackage())
        assertEquals(BetaSource.BUNDLED, snapshot!!.source)
    }

    @Test
    fun `an invalid remote catalog is discarded, not cached`() = runTest {
        // The catalog Worker answers a missing KV key with HTTP 200 and an empty
        // catalog; accepting it would leave a fresh install with zero programs
        // and poison the cache.
        var cached: String? = "CACHED"
        val provider = provider(
            fetchRemote = { "EMPTY" },
            readCache = { cached },
            writeCache = { cached = it },
        )

        assertEquals("CACHED", provider.catalog().singlePackage())
        assertEquals("CACHED", cached)
    }

    @Test
    fun `an invalid cached catalog falls back to bundled`() = runTest {
        val provider = provider(fetchRemote = { null }, readCache = { "CORRUPT" })

        assertEquals("BUNDLED", provider.catalog().singlePackage())
    }

    @Test
    fun `a failing cache write does not discard a good remote catalog`() = runTest {
        // Disk full after a perfectly good download: the cache is best effort, the
        // fresh catalog must still reach the database.
        val provider = provider(
            fetchRemote = { "REMOTE" },
            readCache = { null },
            writeCache = { throw IOException("ENOSPC") },
        )

        assertEquals("REMOTE", provider.catalog().singlePackage())
    }

    @Test
    fun `a stale disk cache cannot replace a download whose cache write failed`() = runTest {
        // The download reached the database but not the disk. Inside the freshness
        // window the provider must keep serving that download from memory — going
        // back to the disk would hand out the OLD catalog as fresh remote data and
        // make the seeder delete every program the download had just added.
        val provider = provider(
            fetchRemote = { "REMOTE2" },
            readCache = { "REMOTE1" },
            writeCache = { throw IOException("ENOSPC") },
        )

        assertEquals("REMOTE2", provider.catalogApplied().singlePackage())
        now += 60_000L

        assertNull(provider.catalog())
    }

    @Test
    fun `the remote is not fetched again inside the freshness window`() = runTest {
        // The list screen refreshes on every resume (every back-navigation from a
        // detail screen); the worker's cache-control allows an hour, so hitting the
        // network each time only burns the user's data plan.
        val provider = provider(fetchRemote = { "REMOTE" }, readCache = { "REMOTE" })

        provider.catalogApplied()
        now += 30 * 60_000L
        provider.catalog()

        assertEquals(1, remoteFetches)
    }

    @Test
    fun `the remote is fetched again once the freshness window has passed`() = runTest {
        val provider = provider(fetchRemote = { "REMOTE" }, readCache = { "REMOTE" })

        provider.catalogApplied()
        now += 3_600_000L
        provider.catalog()

        assertEquals(2, remoteFetches)
    }

    @Test
    fun `a failed remote fetch opens no freshness window`() = runTest {
        // Offline at first launch must not pin the bundled seed for an hour: the
        // next resume retries the download immediately.
        var online = false
        val provider = provider(
            fetchRemote = { if (online) "REMOTE" else null },
            readCache = { null },
        )

        provider.catalogApplied()
        online = true
        now += 1_000L

        assertEquals("REMOTE", provider.catalog().singlePackage())
    }

    @Test
    fun `a catalog is handed out again until the caller confirms it was applied`() = runTest {
        // The database write can fail or the seeding coroutine can be cancelled
        // after the hand-out; the next attempt must get the same catalog again, or
        // the table stays empty or stale until the process restarts.
        val provider = provider(fetchRemote = { "REMOTE" }, readCache = { "REMOTE" })

        assertNotNull(provider.catalog())

        assertEquals("REMOTE", provider.catalog().singlePackage())
    }

    @Test
    fun `a catalog confirmed as applied is not handed out again`() = runTest {
        // Re-seeding the same 2000 programs on every resume rewrites the whole
        // table and makes every observeApps() collector recompute and flicker.
        val provider = provider(fetchRemote = { "REMOTE" }, readCache = { "REMOTE" })

        provider.catalogApplied()

        assertNull(provider.catalog())
    }

    @Test
    fun `a changed remote catalog is handed out even when the previous one was fresh`() = runTest {
        var remote = "REMOTE"
        val provider = provider(fetchRemote = { remote }, readCache = { null })

        provider.catalogApplied()
        remote = "REMOTE2"
        now += 3_600_000L

        assertEquals("REMOTE2", provider.catalog().singlePackage())
    }
}
