package org.jarsi.betascout.data.betadb

import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.toEntity

class BetaSeeder(
    private val readSeedJson: () -> String,
    private val dao: BetaProgramDao,
) {
    /** Idempotent: the IGNORE strategy never overwrites existing rows. */
    suspend fun seed() {
        val programs = BetaSeedParser.parse(readSeedJson())
        dao.insertIgnoring(programs.map { it.toEntity() })
    }
}
