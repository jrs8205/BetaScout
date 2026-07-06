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
import org.jarsi.betascout.domain.PlaySession

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionCookieCipher: SessionCookieCipher,
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

    /** The stored Play web session, or null if not signed in or the encrypted cookie is unreadable. */
    val playSession: Flow<PlaySession?> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val email = prefs[sessionEmailKey]
            val raw = prefs[sessionCookieKey]
            val cookie = when {
                raw.isNullOrBlank() -> null
                // A legacy plaintext cookie is still a valid session — read it directly
                // so an upgrade never signs the user out; it is re-encrypted at rest by
                // migratePlaintextPlaySessionIfNeeded() on the next launch.
                !sessionCookieCipher.isEncrypted(raw) -> raw
                // Encrypted but unreadable (Keystore key lost/invalidated) => treat as
                // signed out; the account screen then prompts a fresh sign-in.
                else -> sessionCookieCipher.decrypt(raw)
            }
            if (!cookie.isNullOrBlank()) {
                PlaySession(accountEmail = email.orEmpty(), cookieHeader = cookie)
            } else {
                null
            }
    }

    suspend fun savePlaySession(email: String, cookieHeader: String) {
        val encryptedCookie = sessionCookieCipher.encrypt(cookieHeader)
        context.dataStore.edit {
            it[sessionEmailKey] = email
            it[sessionCookieKey] = encryptedCookie
        }
    }

    suspend fun migratePlaintextPlaySessionIfNeeded() {
        context.dataStore.edit { prefs ->
            val cookie = prefs[sessionCookieKey]
            if (!cookie.isNullOrBlank() && !sessionCookieCipher.isEncrypted(cookie)) {
                // On a transient Keystore failure, keep the plaintext cookie and retry on
                // the next launch. Deleting it would sign the user out over an error that
                // would very likely succeed on the next attempt — playSession still reads
                // the plaintext value, so the session keeps working meanwhile.
                runCatching { sessionCookieCipher.encrypt(cookie) }.getOrNull()?.let { encrypted ->
                    prefs[sessionCookieKey] = encrypted
                }
            }
        }
    }

    suspend fun clearPlaySession() {
        context.dataStore.edit {
            it.remove(sessionEmailKey)
            it.remove(sessionCookieKey)
        }
    }
}
