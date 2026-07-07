package org.jarsi.betascout.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.jarsi.betascout.R

/** Posts "a beta slot opened" notifications whose tap opens the opt-in page, plus a
 *  one-off "sign in again" notice when the background scan finds the session dead. */
class BetaSlotNotifier(private val context: Context) {

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.slot_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.slot_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showSlotOpen(packageName: String, appLabel: String, testingUrl: String) {
        if (!canNotify()) return
        ensureChannel()

        val intent = Intent(Intent.ACTION_VIEW, testingUrl.toUri())
        val pendingIntent = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(context.getString(R.string.slot_title, appLabel))
            .setContentText(context.getString(R.string.slot_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // A tag keeps these ids from colliding with ReminderNotifier's untagged ones.
        @Suppress("MissingPermission") // guarded by canNotify()
        NotificationManagerCompat.from(context)
            .notify(TAG_SLOT, packageName.hashCode(), notification)
    }

    fun showReloginNeeded() {
        if (!canNotify()) return
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
