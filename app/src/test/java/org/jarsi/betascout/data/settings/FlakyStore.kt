package org.jarsi.betascout.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * A store in memory whose reads and writes can fail. As with the real one, a
 * read that fails fails for whoever is reading at that moment, and the next
 * read is a new attempt.
 */
internal class FlakyStore(
    /** How many of the next reads fail. */
    var failingReads: Int = 0,
) : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val failures = MutableStateFlow(0)

    /** Whether writes fail. */
    var failingWrites = false

    override val data: Flow<Preferences> = flow {
        if (failingReads > 0) {
            failingReads--
            throw IOException("disk")
        }
        val failuresAtStart = failures.value
        emitAll(
            state.combine(failures) { prefs, failed ->
                if (failed != failuresAtStart) throw IOException("disk")
                prefs
            },
        )
    }

    /** Every read under way fails now. */
    fun failReaders() {
        failures.value++
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        if (failingWrites) throw IOException("disk")
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
