package org.jarsi.betascout.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val onboardingDoneKey = booleanPreferencesKey("onboarding_done")

    val onboardingDone: Flow<Boolean> = context.dataStore.data
        // IOException specifically: a broad catch would swallow CancellationException.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[onboardingDoneKey] ?: false }

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[onboardingDoneKey] = true }
    }

    private val gplayEmailKey = stringPreferencesKey("gplay_email")
    private val gplayAasTokenKey = stringPreferencesKey("gplay_aas_token")

    /** The stored Google account credential (email to AAS token), or null if not signed in. */
    val gplayCredential: Flow<Pair<String, String>?> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val email = prefs[gplayEmailKey]
            val token = prefs[gplayAasTokenKey]
            if (!email.isNullOrBlank() && !token.isNullOrBlank()) email to token else null
        }

    suspend fun saveGplayCredential(email: String, aasToken: String) {
        context.dataStore.edit {
            it[gplayEmailKey] = email
            it[gplayAasTokenKey] = aasToken
        }
    }

    suspend fun clearGplayCredential() {
        context.dataStore.edit {
            it.remove(gplayEmailKey)
            it.remove(gplayAasTokenKey)
        }
    }
}
