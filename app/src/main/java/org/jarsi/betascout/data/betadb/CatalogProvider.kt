package org.jarsi.betascout.data.betadb

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jarsi.betascout.domain.BetaProgramInfo
import org.jarsi.betascout.domain.BetaSource

/** Identity of one catalog text, cheap enough to keep for every candidate. */
data class CatalogFingerprint(val length: Int, val hash: Int) {
    companion object {
        fun of(json: String) = CatalogFingerprint(json.length, json.hashCode())
    }
}

/** A parsed catalog ready to be mirrored into the database, with its provenance:
 *  [BetaSource.REMOTE] for a download (or the cached copy of one), [BetaSource.BUNDLED]
 *  for the seed shipped inside the APK. */
data class CatalogSnapshot(
    val programs: List<BetaProgramInfo>,
    val source: BetaSource,
    val fingerprint: CatalogFingerprint,
)

/**
 * Resolves the beta catalog, preferring fresh remote data, then the last cached
 * copy, and finally the bundled seed so the app always has something to show —
 * even offline on first launch. Every candidate must [parse] into a non-empty
 * program list before it is used or cached: the backend can serve an empty
 * catalog (HTTP 200) for a missing KV key, and a bad remote body must not poison
 * the cache.
 *
 * The provider also remembers what the database already holds: the list screen
 * asks on every resume, and neither a second download inside the backend's cache
 * window nor a second full table rewrite of an unchanged catalog is useful work.
 * A catalog counts as applied only once the caller says so via [markApplied] —
 * a failed or cancelled database write must get the same catalog again.
 *
 * The disk cache is only ever allowed to be as new as the database. A download
 * is applied even when its cache write fails, so the older disk copy is deleted
 * then: handed out later as REMOTE data it would make the seeder delete the
 * programs that download had just added.
 */
class CatalogProvider(
    private val fetchRemote: suspend () -> String?,
    private val readCache: () -> String?,
    private val writeCache: (String) -> Unit,
    private val deleteCache: () -> Unit,
    private val readBundled: () -> String,
    /** Null when the text is not a usable, non-empty catalog. */
    private val parse: (json: String, source: BetaSource) -> List<BetaProgramInfo>?,
    private val clock: () -> Long,
    private val remoteFreshFor: Long = DEFAULT_REMOTE_FRESH_FOR_MS,
) {
    private val lock = Mutex()

    /** The last successful download. Inside the freshness window it is served
     *  without touching the network; after that it stays the preferred fallback
     *  for a failed download, because it is never older than the disk cache. */
    private var lastDownload: Download? = null

    /** Identity of the catalog the caller confirmed it applied. */
    private var appliedFingerprint: CatalogFingerprint? = null

    /** The catalog to mirror, or null when the database already holds exactly
     *  this catalog (see [markApplied]) and there is nothing new to apply. */
    suspend fun catalog(): CatalogSnapshot? = lock.withLock {
        val now = clock()
        lastDownload?.takeIf { now - it.fetchedAt < remoteFreshFor }?.let { fresh ->
            return@withLock deliver(fresh)
        }
        val remote = fetchRemote()
        val remotePrograms = remote?.let { parse(it, BetaSource.REMOTE) }
        if (remote != null && remotePrograms != null) {
            cacheBestEffort(remote)
            val download = Download(CatalogFingerprint.of(remote), remotePrograms, fetchedAt = now)
            lastDownload = download
            return@withLock deliver(download)
        }
        lastDownload?.let { return@withLock deliver(it) }
        readCache()?.let { cached ->
            parse(cached, BetaSource.REMOTE)?.let { programs ->
                return@withLock deliver(CatalogFingerprint.of(cached), programs, BetaSource.REMOTE)
            }
        }
        val bundled = readBundled()
        deliver(
            CatalogFingerprint.of(bundled),
            parse(bundled, BetaSource.BUNDLED).orEmpty(),
            BetaSource.BUNDLED,
        )
    }

    /** Confirms that [snapshot] reached the database; it will not be handed out again. */
    suspend fun markApplied(snapshot: CatalogSnapshot) = lock.withLock {
        appliedFingerprint = snapshot.fingerprint
    }

    /** The cache is best effort: a failed write (disk full) must not throw away a
     *  good download. The older copy must go, though — see the class comment. */
    private fun cacheBestEffort(remote: String) {
        try {
            writeCache(remote)
        } catch (e: Exception) {
            android.util.Log.d("BetaScout", "catalog cache write failed: $e")
            try {
                deleteCache()
            } catch (e: Exception) {
                android.util.Log.d("BetaScout", "stale catalog cache could not be removed: $e")
            }
        }
    }

    private fun deliver(download: Download): CatalogSnapshot? =
        deliver(download.fingerprint, download.programs, BetaSource.REMOTE)

    private fun deliver(
        fingerprint: CatalogFingerprint,
        programs: List<BetaProgramInfo>,
        source: BetaSource,
    ): CatalogSnapshot? =
        if (fingerprint == appliedFingerprint) null else CatalogSnapshot(programs, source, fingerprint)

    private class Download(
        val fingerprint: CatalogFingerprint,
        val programs: List<BetaProgramInfo>,
        val fetchedAt: Long,
    )

    private companion object {
        /** Matches the catalog Worker's `cache-control: max-age=3600`. */
        const val DEFAULT_REMOTE_FRESH_FOR_MS = 60 * 60 * 1_000L
    }
}
