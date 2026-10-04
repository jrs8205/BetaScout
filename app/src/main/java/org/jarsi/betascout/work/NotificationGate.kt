package org.jarsi.betascout.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * What every BetaScout notification needs before it can be posted: the
 * permission/enabled check, its channel, and the tap action that opens a testing
 * page. Shared so a fix to one of these reaches every notifier.
 */
internal class NotificationGate(private val context: Context) {

    fun canNotify(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel(
        id: String,
        @StringRes name: Int,
        @StringRes description: Int,
        importance: Int,
    ) {
        val channel = NotificationChannel(id, context.getString(name), importance).apply {
            this.description = context.getString(description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Tap action opening [testingUrl]; one request code per package so the
     *  intents for different apps never collapse into each other. */
    fun openTestingPage(packageName: String, testingUrl: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            Intent(Intent.ACTION_VIEW, testingUrl.toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
