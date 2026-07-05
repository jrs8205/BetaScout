package org.jarsi.betascout.data.betadb

/**
 * Resolves the beta catalog JSON, preferring fresh remote data, then the last
 * cached copy, and finally the bundled seed so the app always has something to
 * show — even offline on first launch.
 */
class CatalogProvider(
    private val fetchRemote: suspend () -> String?,
    private val readCache: () -> String?,
    private val writeCache: (String) -> Unit,
    private val readBundled: () -> String,
) {
    suspend fun catalogJson(): String {
        fetchRemote()?.let { remote ->
            writeCache(remote)
            return remote
        }
        return readCache() ?: readBundled()
    }
}
