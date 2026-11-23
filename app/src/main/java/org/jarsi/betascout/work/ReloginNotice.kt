package org.jarsi.betascout.work

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jarsi.betascout.data.settings.ReloginNoticeState
import org.jarsi.betascout.data.settings.SessionStanding
import org.jarsi.betascout.data.settings.SettingsRepository

/**
 * Shows and withdraws the "sign in again" notification.
 *
 * The two come from different places — a background run that finds the session
 * dead wants it shown, a sign-in wants it gone — and they can cross: the run
 * expires the session, the user signs in again at once, and only then does the
 * run get as far as posting. Whoever acted last would win, and a prompt to sign
 * in posted after the sign-in has nothing left to take it down.
 *
 * So neither side acts on what it knows. Both come here, and here the stored
 * state is read and acted on in one step, one caller at a time: the notice is
 * shown only while the store says "expired, and a notice is owed", and taken
 * down after sign-in or sign-out. On upgrade, a still-visible 0.6.9 notice
 * is itself evidence of an expiry that version did not record. An unreadable
 * store leaves the existing notification alone.
 *
 * That a notice is owed is itself in the store, written with the expiry. The
 * run that expired the session is only the first to come here; if the store
 * does not answer it, the notice is still owed to the next caller — the
 * application's watch of the session state, in this process or at the start
 * of the next. No scan has to come round again, and none would: the session is
 * gone.
 */
@Singleton
class ReloginNotice internal constructor(
    /** What the store says about the notice, or null if it cannot be read. */
    private val state: suspend () -> ReloginNoticeState?,
    /** Notes in the store that the notice owed for the expiry of sign-in
     *  [generation] is shown; false if that could not be written, or the store
     *  is no longer about that expiry. */
    private val noteShown: suspend (generation: Long) -> Boolean,
    private val show: (generation: Long) -> Unit,
    private val withdraw: () -> Unit,
    private val syncLegacySignedOutNotice: () -> Unit,
    private val activeGeneration: () -> Long?,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
    ) : this(settings, BetaSlotNotifier(context))

    private constructor(settings: SettingsRepository, notifier: BetaSlotNotifier) : this(
        state = settings::readReloginNoticeState,
        noteShown = settings::noteReloginNoticeShown,
        show = notifier::showReloginNeeded,
        withdraw = notifier::cancelReloginNeeded,
        syncLegacySignedOutNotice = notifier::syncLegacySignedOutNotice,
        activeGeneration = notifier::activeReloginGeneration,
    )

    private val oneAtATime = Mutex()

    /** Last notice posted by this process. Posting to Android is asynchronous;
     *  a new process can recover the owner from the notification itself. */
    private var shownFor: Long? = null

    /**
     * Brings the notification in line with the store. Call when a run has
     * expired the session, when the stored session state changes, and when the
     * process starts.
     * @return false if the state could not be read, or a notice was shown and
     *   could not be noted as shown for its sign-in: the caller should ask
     *   again.
     */
    suspend fun sync(): Boolean = oneAtATime.withLock {
        val now = state() ?: return@withLock false
        if (now.standing != SessionStanding.EXPIRED) {
            if (now.standing == SessionStanding.SIGNED_OUT && now.generation == 0L && shownFor == null) {
                syncLegacySignedOutNotice()
            } else {
                withdraw()
            }
            shownFor = null
            return@withLock true
        }
        if (!now.owed) {
            // Told on a screen, or the notice has been shown already — the
            // user may have swiped it away since. Not brought back.
            val owner = shownFor ?: activeGeneration()
            if (owner != null && owner != now.generation) {
                withdraw()
                shownFor = null
            }
            return@withLock true
        }
        if (shownFor != now.generation) {
            // A previous process may have posted the notice before its acknowledgement was saved.
            if (activeGeneration() != now.generation) show(now.generation)
            shownFor = now.generation
        }
        // The store decides whether this is still the expiry the notice was
        // shown for (it may have moved on since the read above); if not, the
        // caller comes round again and finds what is owed now.
        noteShown(now.generation)
    }
}
