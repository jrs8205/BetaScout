package org.jarsi.betascout.work

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.jarsi.betascout.R

/** Posts "a beta slot opened" notifications whose tap opens the opt-in page, plus a
 *  one-off "sign in again" notice when the background scan finds the session dead. */
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

    fun showReloginNeeded() {
        if (!gate.canNotify()) return
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(context.getString(R.string.relogin_title))
            .setContentText(context.getString(R.string.relogin_text))
            .setAutoCancel(true)
            .build()

        @Suppress("MissingPermission") // guarded by canNotify()
        NotificationManagerCompat.from(context).notify(TAG_RELOGIN, 1, notification)
    }

    companion object {
        const val CHANNEL_ID = "beta_slots"
        private const val TAG_SLOT = "beta_slot"
        private const val TAG_RELOGIN = "beta_relogin"
    }
}
