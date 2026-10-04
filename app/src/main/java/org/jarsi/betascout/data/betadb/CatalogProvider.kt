package org.jarsi.betascout.data.betadb

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jarsi.betascout.domain.BetaProgramInfo
import org.jarsi.betascout.domain.BetaSource

/** A parsed catalog ready to be mirrored into the database, with its provenance:
 *  [BetaSource.REMOTE] for a download (or the cached copy of one), [BetaSource.BUNDLED]
 *  for the seed shipped inside the APK. */
data class CatalogSnapshot(
    val programs: List<BetaProgramInfo>,
    val source: BetaSource,
)

/**
 * Resolves the beta catalog, preferring fresh remote data, then the last cached
 * copy, and finally the bundled seed so the app always has something to show —
 * even offline on first launch. Every candidate must [parse] into a non-empty
 * program list before it is used or cached: the backend can serve an empty
 * catalog (HTTP 200) for a missing KV key, and a bad remote body must not poison
 * the cache.
 *
 * The provider also remembers what it already handed out: the list screen asks
 * on every resume, and neither a second download inside the backend's cache
 * window nor a second full table rewrite of an unchanged catalog is useful work.
 */
class CatalogProvider(
    private val fetchRemote: suspend () -> String?,
    private val readCache: () -> String?,
    private val writeCache: (String) -> Unit,
    private val readBundled: () -> String,
    /** Null when the text is not a usable, non-empty catalog. */
    private val parse: (json: String, source: BetaSource) -> List<BetaProgramInfo>?,
    private val clock: () -> Long,
    private val remoteFreshFor: Long = DEFAULT_REMOTE_FRESH_FOR_MS,
) {
    private val lock = Mutex()

    /** Clock time of the last successful download, or null before the first one. */
    private var remoteFetchedAt: Long? = null

    /** Identity of the catalog text last handed out, so an unchanged catalog is
     *  not delivered (and mirrored into the database) twice. */
    private var deliveredFingerprint: Fingerprint? = null

    /** The catalog to mirror, or null when the caller already received exactly
     *  this catalog from this provider and there is nothing new to apply. */
    suspend fun catalog(): CatalogSnapshot? = lock.withLock {
        val now = clock()
        val remoteIsFresh = remoteFetchedAt?.let { now - it < remoteFreshFor } == true
        if (!remoteIsFresh) {
            val remote = fetchRemote()
            val programs = remote?.let { parse(it, BetaSource.REMOTE) }
            if (remote != null && programs != null) {
                // Best effort: a failed cache write (disk full) must not throw away
                // a perfectly good download.
                try {
                    writeCache(remote)
                } catch (e: Exception) {
                    android.util.Log.d("BetaScout", "catalog cache write failed: $e")
                }
                remoteFetchedAt = now
                return@withLock deliver(remote, programs, BetaSource.REMOTE)
            }
        }
        readCache()?.let { cached ->
            parse(cached, BetaSource.REMOTE)?.let { programs ->
                return@withLock deliver(cached, programs, BetaSource.REMOTE)
            }
        }
        val bundled = readBundled()
        deliver(bundled, parse(bundled, BetaSource.BUNDLED).orEmpty(), BetaSource.BUNDLED)
    }

    private fun deliver(
        json: String,
        programs: List<BetaProgramInfo>,
        source: BetaSource,
    ): CatalogSnapshot? {
        val fingerprint = Fingerprint(json.length, json.hashCode())
        if (fingerprint == deliveredFingerprint) return null
        deliveredFingerprint = fingerprint
        return CatalogSnapshot(programs, source)
    }

    private data class Fingerprint(val length: Int, val hash: Int)

    private companion object {
        /** Matches the catalog Worker's `cache-control: max-age=3600`. */
        const val DEFAULT_REMOTE_FRESH_FOR_MS = 60 * 60 * 1_000L
    }
}
