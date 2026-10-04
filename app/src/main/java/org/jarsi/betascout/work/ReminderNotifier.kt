package org.jarsi.betascout.work

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.jarsi.betascout.R

/** Posts "check the beta status" notifications whose tap opens the testing page. */
class ReminderNotifier(private val context: Context) {

    private val gate = NotificationGate(context)

    fun canNotify(): Boolean = gate.canNotify()

    fun showReminder(packageName: String, appLabel: String, testingUrl: String) {
        if (!canNotify()) return
        gate.ensureChannel(
            CHANNEL_ID,
            R.string.reminder_channel_name,
            R.string.reminder_channel_description,
            NotificationManager.IMPORTANCE_DEFAULT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(context.getString(R.string.reminder_title, appLabel))
            .setContentText(context.getString(R.string.reminder_text))
            .setContentIntent(gate.openTestingPage(packageName, testingUrl))
            .setAutoCancel(true)
            .build()

        @Suppress("MissingPermission") // guarded by canNotify()
        NotificationManagerCompat.from(context).notify(packageName.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "beta_reminders"
    }
}
