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
import org.jarsi.betascout.SignInRequestActivity

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

    /** Explicit intent to the app's own, non-exported sign-in entry point, which
     *  raises the request and brings the app forward (see
     *  [SignInRequestActivity] for why it is not the launcher activity). */
    fun signInIntent(): Intent =
        Intent(context, SignInRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Tap action for the "sign in again" notice. */
    fun openSignIn(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            signInIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
