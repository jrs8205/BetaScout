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

    private val sessionEmailKey = stringPreferencesKey("session_email")
    private val sessionCookieKey = stringPreferencesKey("session_cookie")

    /** The stored Play web session (email to cookie header), or null if not signed in. */
    val playSession: Flow<Pair<String, String>?> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val email = prefs[sessionEmailKey]
            val cookie = prefs[sessionCookieKey]
            if (!cookie.isNullOrBlank()) (email ?: "") to cookie else null
        }

    suspend fun savePlaySession(email: String, cookieHeader: String) {
        context.dataStore.edit {
            it[sessionEmailKey] = email
            it[sessionCookieKey] = cookieHeader
        }
    }

    suspend fun clearPlaySession() {
        context.dataStore.edit {
            it.remove(sessionEmailKey)
            it.remove(sessionCookieKey)
        }
    }
}
