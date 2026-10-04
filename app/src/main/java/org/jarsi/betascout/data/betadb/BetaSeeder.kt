package org.jarsi.betascout.data.betadb

import org.jarsi.betascout.data.db.BetaProgramDao
import org.jarsi.betascout.data.db.toEntity
import org.jarsi.betascout.domain.BetaSource

class BetaSeeder(
    private val readCatalog: suspend () -> CatalogSnapshot?,
    private val dao: BetaProgramDao,
) {
    /** Mirrors a downloaded catalog into beta_programs: rows the catalog dropped
     *  are deleted too, so a program the backend removed cannot linger on the
     *  device as a phantom beta. The bundled seed is only a first-launch/offline
     *  stopgap and merely fills gaps — it must never delete the programs an
     *  earlier download stored. */
    suspend fun seed() {
        val snapshot = readCatalog() ?: return
        // An empty catalog is never legitimate (the bundled seed alone has content);
        // replacing with it would wipe every known program.
        if (snapshot.programs.isEmpty()) return
        val entities = snapshot.programs.map { it.toEntity() }
        when (snapshot.source) {
            BetaSource.REMOTE -> dao.replaceAll(entities)
            BetaSource.BUNDLED -> dao.insertIgnoring(entities)
        }
    }
}
