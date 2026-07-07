package org.jarsi.betascout

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jarsi.betascout.data.settings.SettingsRepository
import org.jarsi.betascout.work.BetaScanScheduler
import org.jarsi.betascout.work.ReminderScheduler

@HiltAndroidApp
class BetaScoutApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settings: SettingsRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.schedule(this)
        BetaScanScheduler.schedule(this)
        // Re-encrypt any legacy plaintext session at rest, app-wide rather than only
        // when the account screen is opened. Best-effort: playSession works regardless.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { settings.migratePlaintextPlaySessionIfNeeded() }
        }
    }
}
