package org.jarsi.betascout.work

import android.Manifest
import android.app.ActivityOptions
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jarsi.betascout.MainActivity
import org.jarsi.betascout.R
import org.jarsi.betascout.SignInRequests
import org.jarsi.betascout.assumeDisposableInstall
import org.jarsi.betascout.data.settings.ReloginNoticeState
import org.jarsi.betascout.data.settings.SessionCookieCipher
import org.jarsi.betascout.data.settings.SessionStanding
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.showSignInPage
import org.jarsi.betascout.windowNodes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "sign in again" notification and the path its tap takes. A notification
 * without a tap action looks tappable and does nothing; these read the
 * notification back from the system after posting it, so they catch a missing
 * action whatever the builder code looks like.
 *
 * Every test here posts and cancels the app's real sign-in notice — the same tag
 * and id the app itself uses, so a real one would be replaced and then removed,
 * and with the expired session already gone nothing would bring it back. Some
 * also grant the notification permission or finish onboarding. The whole class,
 * its clean-up included, therefore runs only on an install declared disposable
 * (see [assumeDisposableInstall]); anywhere else it is skipped and touches
 * nothing.
 */
@RunWith(AndroidJUnit4::class)
class BetaSlotNotifierTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = BetaSlotNotifier(context)

    /** Set once [prepare] got past its assumption; JUnit runs @After even for a
     *  test that @Before skipped. */
    private var prepared = false

    @Before
    fun prepare() {
        assumeDisposableInstall()
        prepared = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        notifier.cancelReloginNeeded()
        SignInRequests.consume()
    }

    @After
    fun cleanUp() {
        if (!prepared) return
        notifier.cancelReloginNeeded()
        SignInRequests.consume()
    }

    @Test
    fun anUpgradeKeepsTheVisible069NoticeAndRepairsItsTap() {
        val replaced = Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName)
        val receiver = context.packageManager.queryBroadcastReceivers(replaced, 0)
            .single { it.activityInfo.name == ReloginNoticeUpgradeReceiver::class.java.name }
        assertFalse(receiver.activityInfo.exported)

        val file = context.preferencesDataStoreFile("upgrade_test_${UUID.randomUUID()}")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        val settings = SettingsRepository(store, SessionCookieCipher())
        try {
            val gate = NotificationGate(context)
            gate.ensureChannel(
                BetaSlotNotifier.CHANNEL_ID,
                R.string.slot_channel_name,
                R.string.slot_channel_description,
                NotificationManager.IMPORTANCE_HIGH,
            )
            // The released 0.6.9 tag and id, with neither a content intent nor an expiry in the store.
            val old = NotificationCompat.Builder(context, BetaSlotNotifier.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle(context.getString(R.string.relogin_title))
                .setAutoCancel(true)
                .build()
            @Suppress("MissingPermission") // granted in prepare()
            manager.notify("beta_relogin", 1, old)
            posted("beta_relogin")

            fun sync() = runBlocking { assertTrue(ReloginNotice(context, settings).sync()) }
            sync()
            val expectedTap = gate.openSignIn()
            val updated = posted("beta_relogin") { it.notification.contentIntent != null }
            assertEquals(1, updated.id)
            assertEquals(expectedTap, updated.notification.contentIntent)

            sync() // A fresh synchronizer sees the repaired notice on the next start.
            assertPostTimeStays(updated.postTime)
            notifier.cancelReloginNeeded()
            awaitWithdrawal()
            sync()
            assertPostTimeStays(null)
        } finally {
            runBlocking { job.cancelAndJoin() }
            file.delete()
        }
    }

    @Test
    fun aVisibleNoticeKeepsItsPostTimeWhenAFreshSynchronizerAcknowledgesIt() {
        val generation = 41L
        var noted = false
        fun synchronizer() = ReloginNotice(
            state = { ReloginNoticeState(SessionStanding.EXPIRED, owed = true, generation = generation) },
            noteShown = { noted },
            show = notifier::showReloginNeeded,
            withdraw = notifier::cancelReloginNeeded,
            syncLegacySignedOutNotice = notifier::syncLegacySignedOutNotice,
            activeGeneration = notifier::activeReloginGeneration,
        )
        assertFalse(runBlocking { synchronizer().sync() })
        val visible = posted(BetaSlotNotifier.TAG_RELOGIN)
        assertEquals(generation, notifier.activeReloginGeneration())
        noted = true
        assertTrue(runBlocking { synchronizer().sync() })
        assertPostTimeStays(visible.postTime)
    }

    @Test
    fun reloginNotificationHasATapActionThatStartsAnActivity() {
        notifier.showReloginNeeded(generation = 1)

        val tap = posted(BetaSlotNotifier.TAG_RELOGIN).notification.contentIntent
        assertNotNull("the sign-in notification has no tap action", tap)
        assertEquals(1L, notifier.activeReloginGeneration())
        assertEquals(context.packageName, tap.creatorPackage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("the tap action must open an activity", tap.isActivity)
        }
    }

    @Test
    fun tappingTheReloginNotificationOpensTheSignInPage() {
        // What the fix is for is what the user ends up looking at, so that is
        // what is checked: the sign-in page. It only opens for a signed-out
        // user who is past onboarding.
        val settings = SettingsRepository(context, SessionCookieCipher())
        assumeTrue(
            "needs a signed-out install",
            runBlocking { settings.playSession.first() } == null,
        )
        runBlocking { settings.setOnboardingDone() }

        notifier.showReloginNeeded(generation = 1)
        val tap = posted(BetaSlotNotifier.TAG_RELOGIN).notification.contentIntent

        // Unconfined: resumed inside the very call that raises the request, so
        // the request is seen even though the app consumes it a moment later.
        var requestSeen = false
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        watcher.launch {
            SignInRequests.pending.first { it }
            requestSeen = true
        }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            // Sending a PendingIntent from the background needs an explicit
            // opt-in from Android 14 on; a real tap comes from the system UI
            // and is always allowed.
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                    .toBundle()
            } else {
                null
            }
            tap.send(context, 0, null, null, null, null, options)

            val activity = monitor.waitForActivityWithTimeout(10_000)
            assertNotNull("tapping the notification did not open the app", activity)
            try {
                assertTrue("tapping the notification raised no sign-in request", requestSeen)
                // The request alone proves nothing about the rest of the way:
                // the navigation to the account screen and the opening of the
                // page from there.
                val deadline = SystemClock.elapsedRealtime() + 20_000
                var signInPageShown = false
                while (!signInPageShown && SystemClock.elapsedRealtime() < deadline) {
                    signInPageShown =
                        instrumentation.windowNodes(context.packageName).showSignInPage()
                    if (!signInPageShown) SystemClock.sleep(100)
                }
                assertTrue("the sign-in page did not open", signInPageShown)
            } finally {
                activity.finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            watcher.cancel()
        }
    }

    @Test
    fun aStoredSessionWithdrawsTheNoticeWithNoScreenOpen() {
        // The session is stored a moment after the sign-in page closes; by then
        // the user may have left the account screen, or the app. No activity is
        // started here on purpose: whatever withdraws the notice has to work
        // without one.
        val settings = SettingsRepository(context, SessionCookieCipher())
        assumeTrue(
            "needs a signed-out install",
            runBlocking { settings.playSession.first() } == null,
        )
        notifier.showReloginNeeded(generation = 1)
        posted(BetaSlotNotifier.TAG_RELOGIN)
        try {
            runBlocking { settings.savePlaySession("test@example.com", "SID=made-up") }

            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline &&
                manager.activeNotifications.any { it.tag == BetaSlotNotifier.TAG_RELOGIN }
            ) {
                SystemClock.sleep(50)
            }
            assertTrue(
                "the sign-in notification is still shown after a session was stored",
                manager.activeNotifications.none { it.tag == BetaSlotNotifier.TAG_RELOGIN },
            )
        } finally {
            // Leave the install signed out, as it was found.
            runBlocking { settings.clearPlaySession() }
        }
    }

    @Test
    fun anExpiredSessionIsAnnounced() {
        // The background run's path: the session it scanned with is expired in
        // the store, then the user is told.
        val settings = SettingsRepository(context, SessionCookieCipher())
        assumeTrue(
            "needs a signed-out install",
            runBlocking { settings.playSession.first() } == null,
        )
        try {
            runBlocking {
                settings.savePlaySession("test@example.com", "SID=made-up")
                val session = settings.playSession.first()!!
                assertTrue(settings.expirePlaySession(session, tellByNotification = true))
                ReloginNotice(context, settings).sync()
            }

            posted(BetaSlotNotifier.TAG_RELOGIN)
        } finally {
            // Signed out and not expired, as it was found.
            runBlocking { settings.clearPlaySession() }
        }
    }

    @Test
    fun aNoticeLeftOwedIsPostedWithoutTheRunThatOwedIt() {
        // The run stored the expiry and never got its notice posted: here it
        // does not even try. Nothing scans for a session that is gone, so the
        // application's own watch of the session state has to post it — and
        // note that it did, so that it is not posted a second time.
        val settings = SettingsRepository(context, SessionCookieCipher())
        assumeTrue(
            "needs a signed-out install",
            runBlocking { settings.playSession.first() } == null,
        )
        try {
            runBlocking {
                settings.savePlaySession("test@example.com", "SID=made-up")
                val session = settings.playSession.first()!!
                assertTrue(settings.expirePlaySession(session, tellByNotification = true))
            }

            posted(BetaSlotNotifier.TAG_RELOGIN)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var owed = true
            while (owed && SystemClock.elapsedRealtime() < deadline) {
                owed = runBlocking { settings.readReloginNoticeState() }?.owed != false
                if (owed) SystemClock.sleep(50)
            }
            assertTrue("the notice is still marked as owed after it was shown", !owed)
        } finally {
            runBlocking { settings.clearPlaySession() }
        }
    }

    @Test
    fun anExpiryToldOnScreenPostsNoNotice() {
        // Found by a scan the user asked for, or by a single app's re-check:
        // the screens say it, and nothing is owed.
        val settings = SettingsRepository(context, SessionCookieCipher())
        assumeTrue(
            "needs a signed-out install",
            runBlocking { settings.playSession.first() } == null,
        )
        try {
            runBlocking {
                settings.savePlaySession("test@example.com", "SID=made-up")
                val session = settings.playSession.first()!!
                assertTrue(settings.expirePlaySession(session))
                ReloginNotice(context, settings).sync()
            }

            val deadline = SystemClock.elapsedRealtime() + 3_000
            var seen = false
            while (!seen && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250)
                seen = manager.activeNotifications.any { it.tag == BetaSlotNotifier.TAG_RELOGIN }
            }
            assertTrue("a notice was posted for an expiry that owed none", !seen)
        } finally {
            runBlocking { settings.clearPlaySession() }
        }
    }

    @Test
    fun anAnnouncementAfterANewSignInShowsNothing() {
        // The run expired the session, the user signed in again at once, and
        // only then does the run get as far as announcing. Nothing would take
        // a notice posted now down again: the session does not change any more.
        val settings = SettingsRepository(context, SessionCookieCipher())
        assumeTrue(
            "needs a signed-out install",
            runBlocking { settings.playSession.first() } == null,
        )
        try {
            runBlocking {
                settings.savePlaySession("test@example.com", "SID=old")
                val old = settings.playSession.first()!!
                assertTrue(settings.expirePlaySession(old, tellByNotification = true))
            }
            // A notice put up by hand, only to learn when the app has caught up
            // with the sign-in: it takes this one down when it has. Without
            // that the app's withdrawal could still be on its way when the
            // announcement is made, and would tidy up after a wrong one.
            notifier.showReloginNeeded(generation = 1)
            posted(BetaSlotNotifier.TAG_RELOGIN)
            runBlocking { settings.savePlaySession("test@example.com", "SID=new") }
            val caughtUp = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < caughtUp &&
                manager.activeNotifications.any { it.tag == BetaSlotNotifier.TAG_RELOGIN }
            ) {
                SystemClock.sleep(50)
            }
            assertTrue(
                "the app did not take the notice down after the sign-in",
                manager.activeNotifications.none { it.tag == BetaSlotNotifier.TAG_RELOGIN },
            )

            runBlocking { ReloginNotice(context, settings).sync() }

            // Posting is asynchronous: a notice posted by mistake needs a
            // moment to show up. Nothing takes it down again.
            val deadline = SystemClock.elapsedRealtime() + 3_000
            var seen = false
            while (!seen && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250)
                seen = manager.activeNotifications.any { it.tag == BetaSlotNotifier.TAG_RELOGIN }
            }
            assertTrue("a signed-in user was told to sign in", !seen)
        } finally {
            runBlocking { settings.clearPlaySession() }
        }
    }

    @Test
    fun reloginNotificationCanBeWithdrawnOnceTheUserHasSignedIn() {
        notifier.showReloginNeeded(generation = 1)
        posted(BetaSlotNotifier.TAG_RELOGIN)

        notifier.cancelReloginNeeded()

        awaitWithdrawal()
    }

    private fun awaitWithdrawal() {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline &&
            manager.activeNotifications.any { it.tag == BetaSlotNotifier.TAG_RELOGIN }
        ) {
            SystemClock.sleep(50)
        }
        assertTrue(
            "the sign-in notification is still shown",
            manager.activeNotifications.none { it.tag == BetaSlotNotifier.TAG_RELOGIN },
        )
    }

    /** A just-enqueued replacement or cancellation can reach the shade after sync returns. */
    private fun assertPostTimeStays(expected: Long?) {
        val deadline = SystemClock.elapsedRealtime() + 1_000
        do {
            val notice = manager.activeNotifications.find { it.tag == "beta_relogin" && it.id == 1 }
            assertEquals(expected, notice?.postTime)
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
    }

    /** Posting is asynchronous: wait briefly for the system to list it. */
    private fun posted(tag: String, matches: (StatusBarNotification) -> Boolean = { true }): StatusBarNotification {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            manager.activeNotifications.firstOrNull { it.tag == tag && matches(it) }?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("no notification with tag $tag was posted")
    }
}
