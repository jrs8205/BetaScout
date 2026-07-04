package org.jarsi.betavahti.data.betadb

import org.jarsi.betavahti.data.db.BetaProgramDao
import org.jarsi.betavahti.data.db.toEntity

class BetaSeeder(
    private val readSeedJson: () -> String,
    private val dao: BetaProgramDao,
) {
    /** Idempotentti: IGNORE-strategia ei koskaan ylikirjoita olemassa olevia rivejä. */
    suspend fun seed() {
        val programs = BetaSeedParser.parse(readSeedJson())
        dao.insertIgnoring(programs.map { it.toEntity() })
    }
}
