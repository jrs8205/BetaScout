package org.jarsi.betascout.data.betadb

/**
 * Resolves the beta catalog JSON, preferring fresh remote data, then the last
 * cached copy, and finally the bundled seed so the app always has something to
 * show — even offline on first launch. Every candidate must pass [isValid] before
 * it is used or cached: the backend can serve an empty catalog (HTTP 200) for a
 * missing KV key, and a bad remote body must not poison the cache.
 */
class CatalogProvider(
    private val fetchRemote: suspend () -> String?,
    private val readCache: () -> String?,
    private val writeCache: (String) -> Unit,
    private val readBundled: () -> String,
    private val isValid: (String) -> Boolean,
) {
    suspend fun catalogJson(): String {
        fetchRemote()?.takeIf(isValid)?.let { remote ->
            writeCache(remote)
            return remote
        }
        return readCache()?.takeIf(isValid) ?: readBundled()
    }
}
