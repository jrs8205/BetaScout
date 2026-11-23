package org.jarsi.betascout.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jarsi.betascout.BetaScoutApp

/** Repair a surviving notification before the user has to open the updated app. */
class ReloginNoticeUpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val notice = (context.applicationContext as BetaScoutApp).reloginNotice
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                notice.sync()
            } finally {
                pending.finish()
            }
        }
    }
}
