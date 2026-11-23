package org.jarsi.betascout.work

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.bundleOf
import org.jarsi.betascout.R

/** Posts "a beta slot opened" notifications whose tap opens the opt-in page, plus a
 *  one-off "sign in again" notice when the background scan finds the session dead;
 *  its tap opens the app on the sign-in page. */
class BetaSlotNotifier(private val context: Context) {

    private val gate = NotificationGate(context)

    private fun ensureChannel() = gate.ensureChannel(
        CHANNEL_ID,
        R.string.slot_channel_name,
        R.string.slot_channel_description,
        NotificationManager.IMPORTANCE_HIGH,
    )

    fun showSlotOpen(packageName: String, appLabel: String, testingUrl: String) {
        if (!gate.canNotify()) return
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(context.getString(R.string.slot_title, appLabel))
            .setContentText(context.getString(R.string.slot_text))
            .setContentIntent(gate.openTestingPage(packageName, testingUrl))
            .setAutoCancel(true)
            .build()

        // A tag keeps these ids from colliding with ReminderNotifier's untagged ones.
        @Suppress("MissingPermission") // guarded by canNotify()
        NotificationManagerCompat.from(context)
            .notify(TAG_SLOT, packageName.hashCode(), notification)
    }

    fun showReloginNeeded(generation: Long) {
        if (!gate.canNotify()) return
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .addExtras(bundleOf(EXTRA_GENERATION to generation))
            .setOnlyAlertOnce(generation == UPGRADED_LEGACY_NOTICE)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(context.getString(R.string.relogin_title))
            .setContentText(context.getString(R.string.relogin_text))
            // Without a tap action the notice looks tappable and does nothing.
            .setContentIntent(gate.openSignIn())
            .setAutoCancel(true)
            .build()

        @Suppress("MissingPermission") // guarded by canNotify()
        NotificationManagerCompat.from(context)
            .notify(TAG_RELOGIN, RELOGIN_NOTIFICATION_ID, notification)
    }

    private fun activeRelogin() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.tag == TAG_RELOGIN && it.id == RELOGIN_NOTIFICATION_ID }
        ?.notification

    /** The 0.6.9 notice had no tap action. It is the only surviving record of
     *  expiry after that version removed the session. Update it in place once. */
    internal fun syncLegacySignedOutNotice() {
        val notice = activeRelogin() ?: return
        when {
            notice.contentIntent == null -> showReloginNeeded(UPGRADED_LEGACY_NOTICE)
            notice.extras.getLong(EXTRA_GENERATION, 0L) != UPGRADED_LEGACY_NOTICE -> cancelReloginNeeded()
        }
    }

    internal fun activeReloginGeneration(): Long? = activeRelogin()?.let { notice ->
        when {
            notice.contentIntent == null -> UPGRADED_LEGACY_NOTICE
            notice.extras.containsKey(EXTRA_GENERATION) -> notice.extras.getLong(EXTRA_GENERATION)
            else -> null
        }
    }

    /** Withdraws the "sign in again" notice once it no longer applies, so a
     *  stale one does not sit in the shade after the user has signed in. */
    fun cancelReloginNeeded() {
        NotificationManagerCompat.from(context).cancel(TAG_RELOGIN, RELOGIN_NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "beta_slots"
        private const val TAG_SLOT = "beta_slot"
        internal const val TAG_RELOGIN = "beta_relogin"
        private const val RELOGIN_NOTIFICATION_ID = 1
        private const val EXTRA_GENERATION = "org.jarsi.betascout.RELOGIN_GENERATION"
        // Outside the range of session_generation, including 0 for an imported live session.
        private const val UPGRADED_LEGACY_NOTICE = -1L
    }
}
