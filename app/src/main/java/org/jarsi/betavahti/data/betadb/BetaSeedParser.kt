package org.jarsi.betavahti.data.betadb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jarsi.betavahti.domain.BetaProgramInfo
import org.jarsi.betavahti.domain.BetaSource
import org.jarsi.betavahti.domain.KnownBetaStatus

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
    val notes: String? = null,
)

object BetaSeedParser {

    private val json = Json {
        ignoreUnknownKeys = true
        // Tuntematon knownStatus-arvo → oletus (UNKNOWN), jottei vanha sovellus
        // kaadu uudempaan seed-tiedostoon.
        coerceInputValues = true
    }

    fun parse(jsonText: String): List<BetaProgramInfo> =
        json.decodeFromString<BetaSeedFile>(jsonText).programs
            .filter { it.packageName.isNotBlank() }
            .map { entry ->
                val pkg = entry.packageName.trim()
                BetaProgramInfo(
                    packageName = pkg,
                    appName = entry.appName?.takeIf { it.isNotBlank() } ?: pkg,
                    testingUrl = entry.testingUrl,
                    knownStatus = entry.knownStatus,
                    notes = entry.notes,
                    source = BetaSource.BUNDLED,
                )
            }
}
