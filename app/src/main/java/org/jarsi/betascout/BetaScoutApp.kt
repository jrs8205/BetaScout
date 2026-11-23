package org.jarsi.betascout

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.work.BetaScanScheduler
import org.jarsi.betascout.work.ReloginNotice
import org.jarsi.betascout.work.ReminderScheduler

@HiltAndroidApp
class BetaScoutApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var reloginNotice: ReloginNotice

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private companion object {
        const val NOTICE_SYNC_RETRY_MS = 2_000L
    }

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.schedule(this)
        BetaScanScheduler.schedule(this)
        // Re-encrypt any legacy plaintext session at rest, app-wide rather than only
        // when the account screen is opened. Best-effort: playSession works regardless.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope.launch {
            runCatching { settings.migratePlaintextPlaySessionIfNeeded() }
        }
        // The "sign in again" notice follows the stored session state, here, for
        // the life of the process, and not from the account screen: the session
        // is stored a moment after the sign-in page closes, and the user may
        // have left that screen, or the app, by then. The first value arrives
        // when the process starts: a notice that a background run left owed,
        // and did not get posted itself, is posted then. (Why it goes through
        // ReloginNotice: see there.)
        appScope.launch {
            settings.reloginNoticeStates.collectLatest {
                // A store that cannot be read just now says nothing either
                // way, and a notice shown has to be noted as shown: ask again,
                // until that is done or the state changes anew.
                while (!reloginNotice.sync()) delay(NOTICE_SYNC_RETRY_MS)
            }
        }
    }
}
