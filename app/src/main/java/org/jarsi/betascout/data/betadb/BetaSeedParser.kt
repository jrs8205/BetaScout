package org.jarsi.betascout.data.betadb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jarsi.betascout.domain.BetaProgramInfo
import org.jarsi.betascout.domain.BetaSource
import org.jarsi.betascout.domain.KnownBetaStatus
import org.jarsi.betascout.domain.LiveBetaStatus

@Serializable
private data class BetaSeedFile(
    val version: Int = 1,
    val programs: List<BetaSeedEntry> = emptyList(),
)

@Serializable
private data class BetaSeedEntry(
    val packageName: String,
    val appName: String? = null,
    val testingUrl: String? = null,
    val knownStatus: KnownBetaStatus = KnownBetaStatus.UNKNOWN,
    val liveStatus: LiveBetaStatus = LiveBetaStatus.UNKNOWN,
    val statusCheckedAt: Long? = null,
    val productionVersionCode: Long? = null,
    val notes: String? = null,
)

object BetaSeedParser {

    private val json = Json {
        ignoreUnknownKeys = true
        // An unknown knownStatus value coerces to the default (UNKNOWN) so an
        // older app doesn't crash on a newer seed file.
        coerceInputValues = true
    }

    fun parse(jsonText: String, source: BetaSource = BetaSource.BUNDLED): List<BetaProgramInfo> =
        json.decodeFromString<BetaSeedFile>(jsonText).programs
            .filter { it.packageName.isNotBlank() }
            .map { entry ->
                val pkg = entry.packageName.trim()
                BetaProgramInfo(
                    packageName = pkg,
                    appName = entry.appName?.takeIf { it.isNotBlank() } ?: pkg,
                    testingUrl = entry.testingUrl?.takeIf(::isTrustedTestingUrl),
                    knownStatus = entry.knownStatus,
                    liveStatus = entry.liveStatus,
                    statusCheckedAt = entry.statusCheckedAt,
                    productionVersionCode = entry.productionVersionCode,
                    notes = entry.notes,
                    source = source,
                )
            }

    /** The catalog is remote data whose testingUrl is opened in a Custom Tab /
     *  ACTION_VIEW; anything but https on play.google.com is dropped so the app
     *  derives the canonical URL from the package name instead. */
    private fun isTrustedTestingUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        return "https".equals(uri.scheme, ignoreCase = true) &&
            "play.google.com".equals(uri.host, ignoreCase = true)
    }
}
