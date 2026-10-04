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

    /** Identity of the catalog the caller confirmed it applied. */
    private var appliedFingerprint: CatalogFingerprint? = null

    /** The catalog to mirror, or null when the database already holds exactly
     *  this catalog (see [markApplied]) and there is nothing new to apply. */
    suspend fun catalog(): CatalogSnapshot? = lock.withLock {
        val now = clock()
        val remoteIsFresh = remoteFetchedAt?.let { now - it < remoteFreshFor } == true
        if (!remoteIsFresh) {
            val remote = fetchRemote()
            val remotePrograms = remote?.let { parse(it, BetaSource.REMOTE) }
            if (remote != null && remotePrograms != null) {
                // Best effort: a failed cache write (disk full) must not throw away
                // a perfectly good download.
                try {
                    writeCache(remote)
                } catch (e: Exception) {
                    android.util.Log.d("BetaScout", "catalog cache write failed: $e")
                }
                remoteFetchedAt = now
                return@withLock deliver(CatalogFingerprint.of(remote), remotePrograms, BetaSource.REMOTE)
            }
        }
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

    private fun deliver(
        fingerprint: CatalogFingerprint,
        programs: List<BetaProgramInfo>,
        source: BetaSource,
    ): CatalogSnapshot? =
        if (fingerprint == appliedFingerprint) null else CatalogSnapshot(programs, source, fingerprint)

    private companion object {
        /** Matches the catalog Worker's `cache-control: max-age=3600`. */
        const val DEFAULT_REMOTE_FRESH_FOR_MS = 60 * 60 * 1_000L
    }
}
