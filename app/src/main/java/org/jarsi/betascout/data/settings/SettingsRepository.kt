package org.jarsi.betascout.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

/** Result of the most recent completed status scan, persisted so the account screen
 *  can show it across app restarts instead of re-prompting a signed-in user. */
data class LastScanInfo(
    val at: Long,
    val checked: Int,
    val joined: Int,
    val notJoined: Int,
    val failed: Int = 0,
)

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

    private val lastScanAtKey = longPreferencesKey("last_scan_at")
    private val lastScanCheckedKey = intPreferencesKey("last_scan_checked")
    private val lastScanJoinedKey = intPreferencesKey("last_scan_joined")
    private val lastScanNotJoinedKey = intPreferencesKey("last_scan_not_joined")
    private val lastScanFailedKey = intPreferencesKey("last_scan_failed")

    /** The most recent completed scan, or null if no scan has finished yet. */
    val lastScan: Flow<LastScanInfo?> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val at = prefs[lastScanAtKey] ?: return@map null
            LastScanInfo(
                at = at,
                checked = prefs[lastScanCheckedKey] ?: 0,
                joined = prefs[lastScanJoinedKey] ?: 0,
                notJoined = prefs[lastScanNotJoinedKey] ?: 0,
                failed = prefs[lastScanFailedKey] ?: 0,
            )
        }

    suspend fun saveLastScan(info: LastScanInfo) {
        context.dataStore.edit {
            it[lastScanAtKey] = info.at
            it[lastScanCheckedKey] = info.checked
            it[lastScanJoinedKey] = info.joined
            it[lastScanNotJoinedKey] = info.notJoined
            it[lastScanFailedKey] = info.failed
        }
    }

    /** Cleared on sign-out: the counts belong to the account whose observations were
     *  just deleted. Kept on session expiry, where the account stays the same. */
    suspend fun clearLastScan() {
        context.dataStore.edit {
            it.remove(lastScanAtKey)
            it.remove(lastScanCheckedKey)
            it.remove(lastScanJoinedKey)
            it.remove(lastScanNotJoinedKey)
            it.remove(lastScanFailedKey)
        }
    }
}
