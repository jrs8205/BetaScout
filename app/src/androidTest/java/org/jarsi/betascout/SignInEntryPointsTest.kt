package org.jarsi.betascout

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jarsi.betascout.data.settings.SessionCookieCipher
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.work.NotificationGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** How long the main list may take to come up. */
private const val OPEN_TIMEOUT_MS = 15_000L

/** How long the main list is then watched. An accepted request would move to
 *  the account screen within a few frames. */
private const val WATCH_MS = 3_000L

/**
 * Who can make the sign-in page open. Opening it is not harmless: a WebView that
 * still holds valid Google cookies completes the sign-in — session saved, scan
 * started — without the user choosing anything. So only this app may ask: the
 * notification's entry point must not be reachable from other apps, and nothing
 * sent to the (necessarily exported) launcher activity may have that effect.
 */
@RunWith(AndroidJUnit4::class)
class SignInEntryPointsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @After
    fun reset() {
        SignInRequests.consume()
    }

    @Test
    fun theNotificationsEntryPointIsThisAppsOwnAndNotExported() {
        val intent = NotificationGate(context).signInIntent()

        val component = checkNotNull(intent.component)
        assertEquals(SignInRequestActivity::class.java.name, component.className)
        assertEquals(context.packageName, component.packageName)
        assertFalse(context.packageManager.getActivityInfo(component, 0).exported)
    }

    @Test
    fun aSignInActionSentToTheLauncherActivityLeavesTheMainListOpen() {
        // The check is on what the user sees, and the main list is only on
        // screen once onboarding is done — which this cannot undo afterwards.
        assumeDisposableInstall()
        runBlocking { SettingsRepository(context, SessionCookieCipher()).setOnboardingDone() }

        // Unconfined: resumed inside the very call that raises a request, so a
        // request raised and consumed straight away would still be seen.
        var requestSeen = false
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        watcher.launch {
            SignInRequests.pending.first { it }
            requestSeen = true
        }
        // The action an earlier revision of the launcher activity listened to.
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .setAction("org.jarsi.betascout.action.SIGN_IN")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val searchButton = context.getString(R.string.search_open)
            val backButton = context.getString(R.string.back)

            fun look(): List<WindowNode> = instrumentation.windowNodes(context.packageName).also { nodes ->
                // On every look, from the first: an accepted request can put
                // these on screen before the main list was ever seen.
                assertFalse("the sign-in page opened", nodes.showSignInPage())
                assertFalse("the account screen opened", nodes.any { it.label == backButton })
            }

            // First the main list has to come up…
            val openBy = SystemClock.elapsedRealtime() + OPEN_TIMEOUT_MS
            var listUp = false
            while (!listUp && SystemClock.elapsedRealtime() < openBy) {
                listUp = look().any { it.label == searchButton }
                if (!listUp) SystemClock.sleep(100)
            }
            assertTrue("the main list did not open", listUp)

            // …and then stay for the whole watch period, which starts only now
            // and is never cut short by the time the list took to appear.
            val watchUntil = SystemClock.elapsedRealtime() + WATCH_MS
            var looks = 0
            while (SystemClock.elapsedRealtime() < watchUntil) {
                val nodes = look()
                // An empty read is a moment the window could not be read, not a
                // screen; it is neither counted nor taken for the list.
                if (nodes.isNotEmpty()) {
                    assertTrue(
                        "the main list was replaced by another screen",
                        nodes.any { it.label == searchButton },
                    )
                    looks++
                }
                SystemClock.sleep(100)
            }
            assertTrue("the main list could not be watched ($looks looks)", looks >= 10)
            assertFalse("an intent to the launcher activity raised a sign-in request", requestSeen)
        } finally {
            activity.finish()
            watcher.cancel()
        }
    }
}
