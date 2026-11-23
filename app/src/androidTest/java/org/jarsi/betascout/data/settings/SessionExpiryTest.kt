package org.jarsi.betascout.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jarsi.betascout.domain.PlaySession
import org.jarsi.betascout.domain.SignOutUseCase
import org.jarsi.betascout.ui.account.AccountViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "session expired" reason against a real DataStore and the real Keystore
 * cipher — with a key and preferences file unique to each test. The app's "settings" file is
 * never opened: this may run on an install that someone is signed in to, and
 * must not sign them out.
 *
 * Each test has a file to itself, and the store's work is waited out before the
 * file is removed, so no test ever opens a file another one still holds.
 */
@RunWith(AndroidJUnit4::class)
class SessionExpiryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storeName = "session_expiry_test_${UUID.randomUUID()}"
    private val storeJob = SupervisorJob()
    private val settings = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(storeJob + Dispatchers.IO)) {
            context.preferencesDataStoreFile(storeName)
        },
        SessionCookieCipher(storeName),
    )

    @After
    fun removeTestStore() {
        // cancel() alone only asks; the store may still be writing on its IO
        // thread when the file goes.
        runBlocking { (storeJob as Job).cancelAndJoin() }
        try {
            context.preferencesDataStoreFile(storeName).delete()
        } finally {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry(storeName)
            assertFalse(keys.containsAlias(storeName))
        }
    }

    private suspend fun signIn(cookie: String): PlaySession {
        settings.savePlaySession("user@example.com", cookie)
        return checkNotNull(settings.playSession.first())
    }

    @Test
    fun failedSignOutLeavesTheAccountAvailableForRetry() = runBlocking {
        signIn("SID=fixture")
        var fail = true
        val action = SignOutUseCase(
            cancelScanWork = {},
            withScanLock = { it() },
            clearObservations = { if (fail) Result.failure(IOException("disk")) else Result.success(Unit) },
            clearSession = settings::clearPlaySession,
            clearWebViewCookies = {},
            rescheduleBackgroundScans = {},
        )
        val vm = AccountViewModel(settings, WorkManager.getInstance(context), action)
        try {
            withTimeout(5_000) { vm.state.first { it.signedIn } }
            withContext(Dispatchers.Main) { vm.signOut() }
            val failed = withTimeout(5_000) { vm.state.first { it.signOutFailed } }
            assertTrue(failed.signedIn)
            assertFalse(failed.busy)
            fail = false
            withContext(Dispatchers.Main) { vm.signOut() }
            withTimeout(5_000) { vm.state.first { !it.signedIn && !it.signOutFailed && !it.busy } }
            assertNull(settings.playSession.first())
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun sessionEncryptionUsesTheTestKey() = runBlocking {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keys.containsAlias(storeName))
        signIn("SID=fixture")
        assertTrue("the isolated encryption key was created", keys.containsAlias(storeName))
    }

    @Test
    fun theTestStoreIsNotTheAppsSettingsFile() {
        assertFalse(
            context.preferencesDataStoreFile(storeName) ==
                context.preferencesDataStoreFile("settings"),
        )
    }

    @Test
    fun anExpiredSessionIsDroppedAndRememberedUntilTheNextSignIn() = runBlocking {
        val session = signIn("SID=abc")
        assertFalse(settings.sessionExpired.first())

        assertTrue(settings.expirePlaySession(session))

        assertNull(settings.playSession.first())
        assertTrue(settings.sessionExpired.first())

        signIn("SID=def")

        assertEquals("SID=def", settings.playSession.first()?.cookieHeader)
        assertFalse(settings.sessionExpired.first())
    }

    @Test
    fun aLateRejectionAfterSignOutIsNotAnExpiry() = runBlocking {
        val session = signIn("SID=abc")
        settings.clearPlaySession()

        assertFalse(settings.expirePlaySession(session))

        assertNull(settings.playSession.first())
        assertFalse(settings.sessionExpired.first())
    }

    @Test
    fun aLateRejectionOfAnOldSessionLeavesTheNewOneSignedIn() = runBlocking {
        val old = signIn("SID=old")
        signIn("SID=new")

        assertFalse(settings.expirePlaySession(old))

        assertNotNull(settings.playSession.first())
        assertEquals("SID=new", settings.playSession.first()?.cookieHeader)
        assertFalse(settings.sessionExpired.first())
    }

    @Test
    fun aDeliberateSignOutSettlesAnEarlierExpiry() = runBlocking {
        val session = signIn("SID=abc")
        assertTrue(settings.expirePlaySession(session))

        settings.clearPlaySession()

        assertNull(settings.playSession.first())
        assertFalse(settings.sessionExpired.first())
    }
}
