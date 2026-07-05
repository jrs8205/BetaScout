package org.jarsi.betascout.data.betadb

import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.toEntity

class BetaSeeder(
    private val readSeedJson: suspend () -> String,
    private val dao: BetaProgramDao,
) {
    /** Loads the catalog into beta_programs, updating existing rows and adding new ones. */
    suspend fun seed() {
        val programs = BetaSeedParser.parse(readSeedJson())
        dao.upsertAll(programs.map { it.toEntity() })
    }
}
