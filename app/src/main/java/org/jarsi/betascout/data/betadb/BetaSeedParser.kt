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
                    testingUrl = entry.testingUrl,
                    knownStatus = entry.knownStatus,
                    liveStatus = entry.liveStatus,
                    statusCheckedAt = entry.statusCheckedAt,
                    notes = entry.notes,
                    source = source,
                )
            }
}
