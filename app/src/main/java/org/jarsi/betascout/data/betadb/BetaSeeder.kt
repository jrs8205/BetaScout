package org.jarsi.betascout.data.betadb

import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.toEntity

class BetaSeeder(
    private val readSeedJson: suspend () -> String,
    private val dao: BetaProgramDao,
) {
    /** Mirrors the catalog into beta_programs: rows the catalog dropped are deleted too,
     *  so a program the backend removed cannot linger on the device as a phantom beta. */
    suspend fun seed() {
        val programs = BetaSeedParser.parse(readSeedJson())
        // An empty catalog is never legitimate (the bundled seed alone has content);
        // replacing with it would wipe every known program.
        if (programs.isEmpty()) return
        dao.replaceAll(programs.map { it.toEntity() })
    }
}
