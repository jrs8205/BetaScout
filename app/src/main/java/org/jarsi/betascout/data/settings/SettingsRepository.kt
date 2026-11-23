package org.jarsi.betascout.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import org.jarsi.betascout.domain.PlaySession

private val Context.dataStore by preferencesDataStore(name = "settings")

/** How the scan that produced a [LastScanInfo] was started. A background run is
 *  capped to a small batch, so its counts cover far fewer apps than a manual run. */
enum class ScanType { MANUAL, BACKGROUND }

/** Result of the most recent completed status scan, persisted so the account screen
 *  can show it across app restarts instead of re-prompting a signed-in user. */
data class LastScanInfo(
    val at: Long,
    val checked: Int,
    val joined: Int,
    val notJoined: Int,
    val failed: Int = 0,
    val noProgram: Int = 0,
    /** The run's most common fetch-failure reason, or null when nothing failed. */
    val failureReason: String? = null,
    val scanType: ScanType = ScanType.MANUAL,
    /** [PlaySession.accountKey] of the account the scan was made for; null for a
     *  summary stored by a version that did not record it. */
    val accountKey: String? = null,
    /** The scan's place among scan events (see ScanSequence); 0 for a summary
     *  stored before places were kept — older than everything. */
    val order: Long = 0,
)

/** Longest failure reason kept with, or read from, a scan's summary. */
private const val MAX_FAILURE_REASON_CHARS = 200

/** [reason] cut to a length fit to keep and to show. It is an exception message
 *  of whatever length, and it is printed on the main screen in a line that does
 *  not scroll and in the settings card: thousands of characters there would
 *  push everything else off the screen. */
internal fun boundedFailureReason(reason: String?): String? = reason?.take(MAX_FAILURE_REASON_CHARS)

/**
 * The stored summary of the latest scan, as plain reads and edits of a
 * preferences snapshot. The reason is bounded on the way in and on the way out:
 * summaries written by earlier versions hold it at full length.
 */
internal object LastScanPrefs {
    val at = longPreferencesKey("last_scan_at")
    val checked = intPreferencesKey("last_scan_checked")
    val joined = intPreferencesKey("last_scan_joined")
    val notJoined = intPreferencesKey("last_scan_not_joined")
    val failed = intPreferencesKey("last_scan_failed")
    val noProgram = intPreferencesKey("last_scan_no_program")
    val failureReason = stringPreferencesKey("last_scan_failure_reason")
    val type = stringPreferencesKey("last_scan_type")
    val account = stringPreferencesKey("last_scan_account")
    val order = longPreferencesKey("last_scan_order")

    fun read(prefs: Preferences): LastScanInfo? {
        val scanAt = prefs[at] ?: return null
        return LastScanInfo(
            at = scanAt,
            checked = prefs[checked] ?: 0,
            joined = prefs[joined] ?: 0,
            notJoined = prefs[notJoined] ?: 0,
            failed = prefs[failed] ?: 0,
            noProgram = prefs[noProgram] ?: 0,
            failureReason = boundedFailureReason(prefs[failureReason]),
            // Scans stored before the type existed were manual-only.
            scanType = prefs[type]
                ?.let { stored -> ScanType.entries.firstOrNull { it.name == stored } }
                ?: ScanType.MANUAL,
            accountKey = prefs[account],
            order = prefs[order] ?: 0L,
        )
    }

    fun write(prefs: MutablePreferences, info: LastScanInfo) {
        prefs[at] = info.at
        prefs[checked] = info.checked
        prefs[joined] = info.joined
        prefs[notJoined] = info.notJoined
        prefs[failed] = info.failed
        prefs[noProgram] = info.noProgram
        val reason = boundedFailureReason(info.failureReason)
        if (reason == null) prefs.remove(failureReason) else prefs[failureReason] = reason
        prefs[type] = info.scanType.name
        val owner = info.accountKey
        if (owner == null) prefs.remove(account) else prefs[account] = owner
        prefs[order] = info.order
    }

    /** Removes the summary, and with it the record of the latest scan: that
     *  record says how the summary stands, and has nothing to say without it. */
    fun clear(prefs: MutablePreferences) {
        listOf(at, checked, joined, notJoined, failed, noProgram, failureReason, type, account, order)
            .forEach { prefs.remove(it) }
        ScanDonePrefs.clear(prefs)
    }
}

/** The preference values that make up the Play session, before decryption. */
internal data class StoredSession(val email: String?, val cookie: String?, val generation: Long = 0)

/**
 * Turns stored session values into [PlaySession]s. Decryption is an
 * AndroidKeyStore round-trip, so it runs on [io] and only when the stored values
 * actually change — every DataStore edit re-emits the whole Preferences snapshot
 * to each collector (several ViewModels on Main), and the cookie bytes are the
 * same across all of them.
 *
 * With one exception: an encrypted cookie that would not decrypt. It is
 * answered as signed out, at once — and asked for again after [pause] (given
 * the number of failures so far, from 0), for as long as it is the stored
 * session and someone is collecting. The stored values do not change, so
 * nothing else would ever make this flow look again; a collector that lives as
 * long as a screen's view model and met the Keystore at a bad moment would go
 * on believing nobody is signed in, next to a screen that read a moment later
 * and shows the account. A key that is lost for good is asked for in vain, at
 * the pace [pause] sets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<StoredSession>.toPlaySessions(
    isEncrypted: (String) -> Boolean,
    decrypt: (String) -> String?,
    io: CoroutineDispatcher,
    pause: suspend (attempt: Long) -> Unit,
): Flow<PlaySession?> = distinctUntilChanged()
    .transformLatest { stored ->
        val raw = stored.cookie
        // A legacy plaintext cookie is still a valid session — read directly,
        // so an upgrade never signs the user out; it is re-encrypted at rest by
        // migratePlaintextPlaySessionIfNeeded() on the next launch.
        val needsKeystore = !raw.isNullOrBlank() && isEncrypted(raw)
        var failures = 0L
        while (true) {
            val cookie = if (needsKeystore) decrypt(raw!!) else raw
            if (!cookie.isNullOrBlank()) {
                emit(
                    PlaySession(
                        accountEmail = stored.email.orEmpty(),
                        cookieHeader = cookie,
                        generation = stored.generation,
                    ),
                )
                break
            }
            // Nothing stored, or encrypted and unreadable just now: signed
            // out, as far as the screens are concerned.
            emit(null)
            if (!needsKeystore) break
            pause(failures++)
        }
    }
    .distinctUntilChanged()
    .flowOn(io)

/** Where the stored session stands, read off the store without decrypting
 *  anything. */
enum class SessionStanding {
    SIGNED_IN,

    /** Signed out because Google stopped accepting the session. */
    EXPIRED,
    SIGNED_OUT,
}

/** What the store says about the "sign in again" notification: where the
 *  session stands, whether a notification for its expiry is still [owed] (see
 *  [SessionPrefs.noticeOwed]), and which sign-in that is about ([generation]:
 *  the stored count, which stays with an expiry until the next sign-in). */
data class ReloginNoticeState(val standing: SessionStanding, val owed: Boolean, val generation: Long)

/** What reading the stored session came to. */
sealed interface SessionRead {
    data class Present(val session: PlaySession) : SessionRead

    /** Nothing is stored: nobody is signed in. */
    data object None : SessionRead

    /** A session is stored, or may be, and could not be read: the store or the
     *  Keystore failed. Not the same as signed out — it may well work a moment
     *  later, and the screens may still be showing the user as signed in. */
    data object Unreadable : SessionRead
}

/**
 * The stored Play session and the three ways it changes, as plain edits on a
 * preferences snapshot (so each is one atomic DataStore transaction). "Expired"
 * is recorded here, next to the session it replaces, because the run that finds
 * a session dead may finish with the app closed: a flag held in a ViewModel
 * would be gone by the time anyone could read it.
 */
internal object SessionPrefs {
    val email = stringPreferencesKey("session_email")
    val cookie = stringPreferencesKey("session_cookie")
    val expired = booleanPreferencesKey("session_expired")

    /** Set with [expired] when the user is to be told by notification and has
     *  not been yet. Kept here, next to the expiry and written in the same
     *  edit: the run that found the session dead may end before it gets the
     *  notification posted, and no later run would do it — the session is
     *  gone, so there is nothing left to scan with. Whoever looks at the store
     *  next shows it (see ReloginNotice). */
    val noticeOwed = booleanPreferencesKey("relogin_notice_owed")

    /** How many sign-ins this store has seen ([PlaySession.generation] of the
     *  stored session). Never removed: the session after a sign-out must not
     *  get the number of the one before it. */
    val generation = longPreferencesKey("session_generation")

    fun generationOf(prefs: Preferences): Long = prefs[generation] ?: 0L

    /**
     * Stores a new session. [accountKey] is the session's own account key
     * ([PlaySession.accountKey]; the default is what that is for any non-blank
     * email). A stored last-scan summary that is not provably this account's —
     * another account's, or one from a version that did not record the owner —
     * goes in the same edit: an expired session leaves its summary behind for
     * the account's return, and whoever signs in next must not be shown it.
     * So do another account's refused scan request and its latest-scan record.
     */
    fun signIn(
        prefs: MutablePreferences,
        email: String,
        encryptedCookie: String,
        accountKey: String = email.trim().lowercase(Locale.ROOT),
    ) {
        prefs[this.email] = email
        prefs[cookie] = encryptedCookie
        prefs.remove(expired)
        prefs.remove(noticeOwed)
        prefs[generation] = generationOf(prefs) + 1
        if (prefs[LastScanPrefs.at] != null && prefs[LastScanPrefs.account] != accountKey) {
            LastScanPrefs.clear(prefs)
        }
        if (prefs[ScanRefusalPrefs.account] != accountKey) ScanRefusalPrefs.clear(prefs)
        if (prefs[ScanDonePrefs.account] != accountKey) ScanDonePrefs.clear(prefs)
    }

    /** A deliberate sign-out: nothing expired, and an earlier expiry is moot. */
    fun signOut(prefs: MutablePreferences) {
        prefs.remove(email)
        prefs.remove(cookie)
        prefs.remove(expired)
        prefs.remove(noticeOwed)
        ScanRefusalPrefs.clear(prefs)
        LastScanPrefs.clear(prefs)
    }

    /** Google no longer accepts the session: drop it, remember why.
     *  [tellByNotification]: nobody is looking at a screen that would say so
     *  (a background run found it), so a notification is owed as well. */
    fun expire(prefs: MutablePreferences, tellByNotification: Boolean = false) {
        prefs.remove(email)
        prefs.remove(cookie)
        prefs[expired] = true
        if (tellByNotification) prefs[noticeOwed] = true else prefs.remove(noticeOwed)
        // A scan request that was refused belonged to the signed-in user; with
        // the session gone there is nobody to retry it for.
        ScanRefusalPrefs.clear(prefs)
    }

    /**
     * Whether [session] is still the stored session: someone is signed in, and
     * it is that sign-in ([PlaySession.generation]). Decided without reading
     * the cookie. Reading it means asking the Keystore, and a Keystore that
     * does not answer at this moment would make a perfectly current session
     * look like one that has ended — a dead session would then not be expired
     * and a finished scan would lose its record.
     */
    fun holds(prefs: Preferences, session: PlaySession): Boolean {
        return !prefs[cookie].isNullOrBlank() && generationOf(prefs) == session.generation
    }

    /**
     * The stored session as a run has to see it. [SessionRead.Unreadable] is
     * kept apart from [SessionRead.None]: the screens treat an unreadable
     * session as signed out, which is right for a state to show, but a run
     * that acted on it would end as if nobody had asked for it.
     */
    fun read(
        prefs: Preferences,
        isEncrypted: (String) -> Boolean,
        decrypt: (String) -> String?,
    ): SessionRead {
        val stored = prefs[cookie]
        if (stored.isNullOrBlank()) return SessionRead.None
        // A legacy plain-text cookie is a valid session (see toPlaySessions).
        val plain = if (isEncrypted(stored)) decrypt(stored) else stored
        if (plain.isNullOrBlank()) return SessionRead.Unreadable
        return SessionRead.Present(PlaySession(prefs[email].orEmpty(), plain, generationOf(prefs)))
    }

    /**
     * [expire], but only if [session] — the one the rejected check was made
     * with — is still the stored session (see [holds]). The answer to a check
     * can arrive after the user signed out or signed in again: a late rejection
     * must neither turn a deliberate sign-out into a standing "session expired"
     * prompt nor wipe the newer session. Compared and changed in one edit, so
     * nothing can slip in between.
     * @return whether this call expired the session.
     */
    fun expireIfCurrent(prefs: MutablePreferences, session: PlaySession): Boolean {
        if (!holds(prefs, session)) return false
        expire(prefs)
        return true
    }

    /** Where the session stands and whether a notification is owed for it.
     *  Owed only while expired: a stray flag next to anything else is nothing. */
    fun noticeState(prefs: Preferences): ReloginNoticeState {
        val standing = standing(prefs)
        return ReloginNoticeState(
            standing = standing,
            owed = standing == SessionStanding.EXPIRED && prefs[noticeOwed] == true,
            generation = generationOf(prefs),
        )
    }

    /**
     * The notification owed for the expiry of sign-in [generation] is on
     * screen. The session stays expired. Taken only while that very expiry is
     * what is stored: this note is written after the notification was shown,
     * and by then the user may have opened it and signed in, and that session
     * may have expired in the background in its turn — the notice owed then is
     * another one, and a late note for the first must not write it off.
     * @return whether the note was taken.
     */
    fun noticeShown(prefs: MutablePreferences, generation: Long): Boolean {
        if (standing(prefs) != SessionStanding.EXPIRED || generationOf(prefs) != generation) return false
        prefs.remove(noticeOwed)
        return true
    }

    /** A stored session always wins, so a stale flag can never contradict it. */
    fun isExpired(prefs: Preferences): Boolean = standing(prefs) == SessionStanding.EXPIRED

    fun standing(prefs: Preferences): SessionStanding = when {
        !prefs[cookie].isNullOrBlank() -> SessionStanding.SIGNED_IN
        prefs[expired] == true -> SessionStanding.EXPIRED
        else -> SessionStanding.SIGNED_OUT
    }
}

/**
 * One edit of this store on behalf of [session]: [change] is applied only if
 * that session is still the stored one ([SessionPrefs.holds]), checked in the
 * same update. Expiring a session and everything a scan run writes about itself
 * go through here. A run reads its session when it starts and writes when it
 * ends; in between the user can sign out, the session can expire, or someone
 * else can sign in — and a record written then would be waiting for whoever
 * signs in next. Nothing is decrypted here, so it may be called from any
 * thread.
 * @return whether [change] was applied.
 */
internal suspend fun DataStore<Preferences>.editForSession(
    session: PlaySession,
    change: (MutablePreferences) -> Unit,
): Boolean {
    var applied = false
    edit { prefs ->
        applied = SessionPrefs.holds(prefs, session)
        if (applied) change(prefs)
    }
    return applied
}

/**
 * The store's contents for a watcher that has to outlive a bad read. A read
 * that fails says nothing about what is stored: by default no value is
 * delivered for it ([onFailure] may deliver one), and it does not end the
 * watch — [pause] is given the number of failures in a row so far (from 0) and
 * the read is then started again.
 */
internal fun Flow<Preferences>.outlivingReadFailures(
    pause: suspend (attempt: Long) -> Unit,
    onFailure: suspend FlowCollector<Preferences>.() -> Unit = {},
): Flow<Preferences> = flow {
    // Per collector. retryWhen only sees what the source throws, so nothing
    // thrown by whoever collects this is mistaken for a read failure.
    var failuresInARow = 0L
    emitAll(
        this@outlivingReadFailures
            .onEach { failuresInARow = 0 }
            .retryWhen { cause, _ ->
                if (cause is IOException) {
                    onFailure()
                    pause(failuresInARow++)
                    true
                } else {
                    false
                }
            },
    )
}

/**
 * [outlivingReadFailures] for a value that is also asked for once (first()),
 * by callers that cannot wait for the store to recover: a failed read is
 * answered at once as an empty store — the answer these flows have always
 * given — and then, which they did not do before, the read is started again.
 */
internal fun Flow<Preferences>.emptyOnReadFailure(
    pause: suspend (attempt: Long) -> Unit,
): Flow<Preferences> = outlivingReadFailures(pause) { emit(emptyPreferences()) }

/**
 * The state that determines the notice, one value per change, for a watcher that has to
 * outlive a bad read (see [outlivingReadFailures]): a read that fails is not
 * reported as "signed out".
 */
internal fun Flow<Preferences>.reloginNoticeStates(
    pause: suspend (attempt: Long) -> Unit,
): Flow<ReloginNoticeState> = outlivingReadFailures(pause)
    .map(SessionPrefs::noticeState)
    .distinctUntilChanged()

/**
 * @param store the preferences file behind every setting. The app always passes
 *   its one "settings" store; a test passes a store of its own, so exercising
 *   the real DataStore and Keystore cipher never touches the app's file.
 * @param io where the Keystore cipher runs.
 */
@Singleton
class SettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val sessionCookieCipher: SessionCookieCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        sessionCookieCipher: SessionCookieCipher,
    ) : this(context.dataStore, sessionCookieCipher)

    private companion object {
        /** First pause before a failed read of the store is tried again, and
         *  the longest one. */
        const val READ_RETRY_MS = 1_000L
        const val READ_RETRY_MAX_MS = 60_000L
    }

    private suspend fun pauseBeforeReadingAgain(attempt: Long) {
        delay((READ_RETRY_MS shl attempt.coerceAtMost(6).toInt()).coerceAtMost(READ_RETRY_MAX_MS))
    }

    /**
     * The store as the scan screens and the application watch it. Their
     * collectors are started once and live as long as a view model, or the
     * process; a flow that answered one failed read with defaults and then
     * ended would leave them showing those defaults for good, whatever is
     * stored afterwards. A failed read fails every collector of the store at
     * once, so this goes for all of them together: the session, the outcomes
     * of a scan, the cooldown.
     *
     * [watched]: a failed read delivers nothing — an outcome on screen does
     * not blink out because of it. [watchedOrEmpty]: for the two values that
     * are also asked for once.
     */
    private val watched: Flow<Preferences> = store.data.outlivingReadFailures(::pauseBeforeReadingAgain)
    private val watchedOrEmpty: Flow<Preferences> = store.data.emptyOnReadFailure(::pauseBeforeReadingAgain)

    private val onboardingDoneKey = booleanPreferencesKey("onboarding_done")

    val onboardingDone: Flow<Boolean> = store.data
        // IOException specifically: a broad catch would swallow CancellationException.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[onboardingDoneKey] ?: false }

    suspend fun setOnboardingDone() {
        store.edit { it[onboardingDoneKey] = true }
    }

    private val shareDiscoveriesKey = booleanPreferencesKey("share_discoveries")
    private val sharePromptShownKey = booleanPreferencesKey("share_prompt_shown")
    private val reportedPackagesKey = stringSetPreferencesKey("reported_packages")

    /** Opt-in crowd sharing: anonymous package-name hints after scans. Default off. */
    val shareDiscoveries: Flow<Boolean> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[shareDiscoveriesKey] ?: false }

    suspend fun setShareDiscoveries(enabled: Boolean) {
        store.edit {
            it[shareDiscoveriesKey] = enabled
            // Deciding either way settles the one-time prompt.
            it[sharePromptShownKey] = true
        }
    }

    val sharePromptShown: Flow<Boolean> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[sharePromptShownKey] ?: false }

    suspend fun setSharePromptShown() {
        store.edit { it[sharePromptShownKey] = true }
    }

    /** Packages already uploaded as hints — updated only after a 2xx upload so a
     *  failed upload retries naturally after the next scan. */
    val reportedPackages: Flow<Set<String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[reportedPackagesKey] ?: emptySet() }

    suspend fun addReportedPackages(packages: Set<String>) {
        store.edit {
            it[reportedPackagesKey] = (it[reportedPackagesKey] ?: emptySet()) + packages
        }
    }

    private val useDynamicColorKey = booleanPreferencesKey("use_dynamic_color")

    /** Opt-in Material You: the brand palette is the default so the app looks the
     *  same on every device; wallpaper-derived colors only when the user asks. */
    val useDynamicColor: Flow<Boolean> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[useDynamicColorKey] ?: false }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        store.edit { it[useDynamicColorKey] = enabled }
    }

    private val sessionEmailKey = SessionPrefs.email
    private val sessionCookieKey = SessionPrefs.cookie

    /** True from the moment a dead session was dropped ([expirePlaySession]) until
     *  the next sign-in or sign-out. Persisted: it explains the sign-out even
     *  when the run that found it finished with the app closed. */
    val sessionExpired: Flow<Boolean> = watched
        .map { prefs -> SessionPrefs.isExpired(prefs) }
        .distinctUntilChanged()

    /** A fast sign-in and expiry can leave the standing unchanged. The notice
     *  still has a different owner, even if the intermediate value was conflated. */
    val reloginNoticeStates = store.data.reloginNoticeStates(::pauseBeforeReadingAgain)

    /** Where the session stands right now, or null if the store cannot be read. */
    suspend fun readSessionStanding(): SessionStanding? = try {
        SessionPrefs.standing(store.data.first())
    } catch (e: IOException) {
        null
    }

    /** The stored Play web session, or null if not signed in or the encrypted
     *  cookie is unreadable. A store that cannot be read is answered as null
     *  too, at once — and read again, so whoever goes on watching gets the
     *  session back (see [watchedOrEmpty]); so is a cookie the Keystore would
     *  not decrypt (see [toPlaySessions]). */
    val playSession: Flow<PlaySession?> = watchedOrEmpty
        .map { prefs ->
            StoredSession(
                email = prefs[sessionEmailKey],
                cookie = prefs[sessionCookieKey],
                generation = SessionPrefs.generationOf(prefs),
            )
        }
        .toPlaySessions(
            isEncrypted = sessionCookieCipher::isEncrypted,
            decrypt = sessionCookieCipher::decrypt,
            io = io,
            pause = ::pauseBeforeReadingAgain,
        )

    /** The cipher is a Keystore round-trip, so it runs on [io] whoever calls.
     *  @return the new session's [PlaySession.generation]. */
    suspend fun savePlaySession(email: String, cookieHeader: String): Long = withContext(io) {
        val encryptedCookie = sessionCookieCipher.encrypt(cookieHeader)
        val accountKey = PlaySession(email, cookieHeader).accountKey
        var generation = 0L
        store.edit {
            SessionPrefs.signIn(it, email, encryptedCookie, accountKey)
            generation = SessionPrefs.generationOf(it)
        }
        generation
    }

    suspend fun migratePlaintextPlaySessionIfNeeded() {
        store.edit { prefs ->
            val cookie = prefs[sessionCookieKey]
            if (!cookie.isNullOrBlank() && !sessionCookieCipher.isEncrypted(cookie)) {
                // On a transient Keystore failure, keep the plaintext cookie and retry on
                // the next launch. Deleting it would sign the user out over an error that
                // would very likely succeed on the next attempt — playSession still reads
                // the plaintext value, so the session keeps working meanwhile.
                runCatching { sessionCookieCipher.encrypt(cookie) }.getOrNull()?.let { encrypted ->
                    prefs[sessionCookieKey] = encrypted
                }
            }
        }
    }

    /** Sign-out by the user. For a session Google rejected use [expirePlaySession]. */
    suspend fun clearPlaySession() {
        store.edit { SessionPrefs.signOut(it) }
    }

    /**
     * Drops [session] because Google no longer accepts it and records that it
     * expired, so the screens can say why the user is signed out — but only if
     * it is still the stored session (see [SessionPrefs.expireIfCurrent]).
     * [tellByNotification]: a notification is owed for it as well (see
     * [SessionPrefs.noticeOwed]).
     * @return whether the session was expired by this call; false means the
     *   user has signed out or signed in again since the check was made, and
     *   nothing was changed.
     */
    suspend fun expirePlaySession(session: PlaySession, tellByNotification: Boolean = false): Boolean =
        store.editForSession(session) { SessionPrefs.expire(it, tellByNotification) }

    /** What the store says about the "sign in again" notification right now,
     *  or null if the store cannot be read. */
    suspend fun readReloginNoticeState(): ReloginNoticeState? = try {
        SessionPrefs.noticeState(store.data.first())
    } catch (e: IOException) {
        null
    }

    /** Notes that the notification owed for the expiry of sign-in
     *  [generation] is shown (see [SessionPrefs.noticeShown]).
     *  @return false if the store could not be written, or is no longer about
     *    that expiry: the caller has to look at the state again. */
    suspend fun noteReloginNoticeShown(generation: Long): Boolean = try {
        var noted = false
        store.edit { noted = SessionPrefs.noticeShown(it, generation) }
        noted
    } catch (e: IOException) {
        false
    }

    /**
     * Whether [session] is still the stored session: that very sign-in, not
     * only that account (see [SessionPrefs.holds]). For whoever is about to
     * fetch with it. An unreadable store throws IOException so the caller
     * can distinguish it from a confirmed change of sign-in.
     */
    suspend fun holdsPlaySession(session: PlaySession): Boolean =
        SessionPrefs.holds(store.data.first(), session)

    /**
     * The stored session, for a run that is about to act on it. Unlike
     * [playSession] this tells "nobody is signed in" from "could not be read"
     * (see [SessionRead]).
     */
    suspend fun readPlaySession(): SessionRead = withContext(io) {
        val prefs = try {
            store.data.first()
        } catch (e: IOException) {
            return@withContext SessionRead.Unreadable
        }
        SessionPrefs.read(prefs, sessionCookieCipher::isEncrypted) { stored ->
            runCatching { sessionCookieCipher.decrypt(stored) }.getOrNull()
        }
    }

    private val scanBlockedUntilKey = longPreferencesKey("scan_blocked_until")

    /** Until when scans are frozen after Google answered 429/403, or null. The value
     *  is epoch millis and simply expires; nothing needs to clear it. */
    val scanBlockedUntil: Flow<Long?> = watchedOrEmpty
        .map { prefs -> prefs[scanBlockedUntilKey] }

    /** A scan must know the stored cooldown before making a Play request. */
    suspend fun readScanBlockedUntil(): Long? = store.data.first()[scanBlockedUntilKey]

    suspend fun setScanBlockedUntil(until: Long) {
        store.edit { it[scanBlockedUntilKey] = until }
    }

    /** The most recent scan that recorded a summary, or null. It carries the
     *  account it was made for; showing it only to that account is the reader's
     *  job. */
    val lastScan: Flow<LastScanInfo?> = watched
        .map { prefs -> LastScanPrefs.read(prefs) }

    /** The latest scan request that did not run and that no scan has answered
     *  since, or null. Shown only to its account, like [lastScan]. */
    val scanRefusal: Flow<ScanRefusal?> = watched
        .map { prefs -> ScanRefusalPrefs.read(prefs) }
        .distinctUntilChanged()

    /** The latest scan that got through, with or without a summary, or null. */
    val scanDone: Flow<ScanDone?> = watched
        .map { prefs -> ScanDonePrefs.read(prefs) }
        .distinctUntilChanged()

    private val scanSequence = ScanSequence()

    /** Marks a scan event — a request refused, a run getting through — at the
     *  moment it happens, for the record written afterwards (see [ScanSequence]).
     *  Does not suspend: take it first, do the rest after. */
    fun scanTicket(): Long = scanSequence.ticket()

    /** Before the first ordered write, an unavailable work history leaves the starting order unknown. */
    internal suspend fun priorScanOrder(readWorkOrder: suspend () -> Long): Long? =
        scanSequence.start ?: try {
            readWorkOrder()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("BetaScout", "scan: the work record could not be read", e)
            null
        }

    /**
     * Records that the scan [session]'s user asked for did not run ([code]: why;
     * [ticket]: when, from [scanTicket]). Written only while [session] is still
     * the stored session, and only if no scan of the account has got through
     * since (see [ScanOutcomes.recordRefusal]). [ordersOnRecord]: the highest
     * order kept outside this store, in the work record of earlier requests
     * (see [ScanSequence]).
     * @return whether [session] was still current.
     */
    suspend fun saveScanRefusal(
        session: PlaySession,
        code: String,
        ticket: Long,
        ordersOnRecord: Long,
    ): Boolean = store.editForSession(session) { prefs ->
        val order = scanSequence.orderOf(ticket, prefs, ordersOnRecord)
        ScanOutcomes.recordRefusal(prefs, ScanRefusal(code, session.accountKey, order))
    }

    /**
     * Records that a scan for [session] got through ([ticket]: when, from
     * [scanTicket]); [summary] is what it recorded, or null if nothing was due.
     * Written only while [session] is still the stored session (see
     * [ScanOutcomes.recordDone] for what it replaces and settles).
     * [ordersOnRecord]: as for [saveScanRefusal].
     * @return whether [session] was still current.
     */
    suspend fun saveScanDone(
        session: PlaySession,
        ticket: Long,
        summary: LastScanInfo?,
        ordersOnRecord: Long,
    ): Boolean = store.editForSession(session) { prefs ->
        val order = scanSequence.orderOf(ticket, prefs, ordersOnRecord)
        ScanOutcomes.recordDone(prefs, session.accountKey, order, summary)
    }

    /** The order [ticket] stands for, for a record that could not be written
     *  to the store and has to travel another way. The store is read only if
     *  this process has not settled yet where it counts from; null if it has
     *  not and the store cannot be read either. [ordersOnRecord]: as for
     *  [saveScanRefusal]. */
    suspend fun scanOrder(ticket: Long, ordersOnRecord: Long): Long? =
        scanSequence.settledOrderOf(ticket) ?: try {
            scanSequence.orderOf(ticket, store.data.first(), ordersOnRecord)
        } catch (e: IOException) {
            null
        }

}
