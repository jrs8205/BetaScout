package org.jarsi.betascout.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.concurrent.atomic.AtomicLong

/**
 * A scan the user asked for that did not run: [code] is why (an error code of
 * the scan worker), [accountKey] the account it was asked for, [order] its
 * place among scan events (see [ScanSequence]).
 *
 * The refused run writes this itself. The request can sit in WorkManager's
 * queue (offline) until long after the app's process is gone, and be refused
 * then; a refusal that only a watching screen remembered would never reach the
 * user.
 */
data class ScanRefusal(val code: String, val accountKey: String, val order: Long)

/**
 * The latest scan that got through for [accountKey], whether or not it had
 * anything to record; [order] is its place among scan events. Compared with the
 * order of the stored summary ([LastScanInfo.order]) it tells whether that
 * summary is still the latest outcome: a run with nothing due leaves the
 * summary alone — it is still the last real result — but is the newer event,
 * and what the summary says about failures is history from then on.
 */
data class ScanDone(val accountKey: String, val order: Long)

/** The stored [ScanRefusal], as plain reads and edits of a preferences snapshot. */
internal object ScanRefusalPrefs {
    val code = stringPreferencesKey("scan_refusal_code")
    val account = stringPreferencesKey("scan_refusal_account")
    val order = longPreferencesKey("scan_refusal_order")

    fun read(prefs: Preferences): ScanRefusal? {
        return ScanRefusal(
            code = prefs[code] ?: return null,
            accountKey = prefs[account] ?: return null,
            order = prefs[order] ?: return null,
        )
    }

    fun write(prefs: MutablePreferences, refusal: ScanRefusal) {
        prefs[code] = refusal.code
        prefs[account] = refusal.accountKey
        prefs[order] = refusal.order
    }

    fun clear(prefs: MutablePreferences) {
        prefs.remove(code)
        prefs.remove(account)
        prefs.remove(order)
    }
}

/** The stored [ScanDone], as plain reads and edits of a preferences snapshot. */
internal object ScanDonePrefs {
    val account = stringPreferencesKey("scan_done_account")
    val order = longPreferencesKey("scan_done_order")

    fun read(prefs: Preferences): ScanDone? {
        return ScanDone(
            accountKey = prefs[account] ?: return null,
            order = prefs[order] ?: return null,
        )
    }

    fun write(prefs: MutablePreferences, done: ScanDone) {
        prefs[account] = done.accountKey
        prefs[order] = done.order
    }

    fun clear(prefs: MutablePreferences) {
        prefs.remove(account)
        prefs.remove(order)
    }
}

/**
 * How the outcome of a scan run is entered into the store. Two runs can end
 * close together and their records can be written in either order — each run
 * writes from its own coroutine, after the scan lock is long released. So no
 * record is taken at face value as "the newest": each carries the place of its
 * event ([ScanSequence]) and every write checks it against what is stored.
 *
 * - A refusal stands until a scan of the same account gets through after it.
 * - The latest scan that got through is remembered even when it recorded no
 *   summary (nothing was due): without that there would be nothing to check a
 *   late refusal against, and nothing to tell the screens that the stored
 *   summary is no longer the latest outcome.
 */
internal object ScanOutcomes {

    /** The highest order among the stored records, 0 when there are none. */
    fun highestOrder(prefs: Preferences): Long = maxOf(
        prefs[ScanRefusalPrefs.order] ?: 0L,
        prefs[ScanDonePrefs.order] ?: 0L,
        prefs[LastScanPrefs.order] ?: 0L,
    )

    /** A requested scan did not run. Ignored if a scan of the same account got
     *  through after it, or if a later request of that account was refused too
     *  and is already stored. */
    fun recordRefusal(prefs: MutablePreferences, refusal: ScanRefusal) {
        val done = ScanDonePrefs.read(prefs)
        if (done != null && done.accountKey == refusal.accountKey && done.order > refusal.order) return
        val stored = ScanRefusalPrefs.read(prefs)
        if (stored != null && stored.accountKey == refusal.accountKey && stored.order > refusal.order) return
        ScanRefusalPrefs.write(prefs, refusal)
    }

    /**
     * A scan for [accountKey] got through at [order]; [summary] is what it
     * recorded, or null if it had nothing to record. Three things are decided,
     * each against its own stored counterpart — the records of two runs can
     * land in either order, and the later-written one must not undo more than
     * its own place in the order entitles it to:
     * - It becomes the latest scan unless a later one of the account is stored.
     * - Its summary becomes the last-scan summary unless the stored summary is
     *   that account's from a later scan. Not "unless a later scan is stored":
     *   a later scan with nothing due has no summary, and this one is then
     *   still the last real result.
     * - A refusal of the account from before it is settled; one from after it
     *   stays.
     */
    fun recordDone(
        prefs: MutablePreferences,
        accountKey: String,
        order: Long,
        summary: LastScanInfo?,
    ) {
        val latest = ScanDonePrefs.read(prefs)
        if (latest == null || latest.accountKey != accountKey || latest.order < order) {
            ScanDonePrefs.write(prefs, ScanDone(accountKey, order))
        }
        if (summary != null) {
            val stored = LastScanPrefs.read(prefs)
            if (stored == null || stored.accountKey != accountKey || stored.order < order) {
                LastScanPrefs.write(prefs, summary.copy(accountKey = accountKey, order = order))
            }
        }
        val refusal = ScanRefusalPrefs.read(prefs)
        if (refusal != null && refusal.accountKey == accountKey && refusal.order < order) {
            ScanRefusalPrefs.clear(prefs)
        }
    }
}

/**
 * Puts scan events — a request refused, a run getting through — in the order
 * they happened, for records that are written afterwards and possibly in
 * another order.
 *
 * Not by the clock. The wall clock gets corrected, and an event stamped before
 * a correction backwards would outrank everything that happens after it. A
 * [ticket] is a plain count taken at the event, exact within this process. The
 * events of an earlier process are all older, so [orderOf] continues from the
 * highest order that process left behind — and it can have left one in two
 * places. Normally in the store. But an outcome the store would not take is
 * handed to the request's work result instead, order included, and then the
 * store still ends where it did before: a process that looked at the store
 * alone would give that same order out again, to a later event. So the highest
 * order kept in the work record counts as well. Everything kept anywhere is
 * therefore below everything that can still be issued, whatever happened to
 * the clock or to a write, on any restart.
 */
internal class ScanSequence {
    private val issued = AtomicLong()

    /** Set by the first [orderOf], once. */
    @Volatile
    var start: Long? = null
        private set

    /** The order [ticket] stands for, if this process has already settled
     *  where it starts; null if it has not. Needs no store: a place must not
     *  come out as "unknown" just because the store cannot be read at a
     *  moment when it has nothing left to tell. */
    fun settledOrderOf(ticket: Long): Long? = start?.let { it + ticket }

    /** Take one at the event, without suspending. */
    fun ticket(): Long = issued.incrementAndGet()

    /**
     * The order [ticket] stands for. [prefs] is the store as it is now — the
     * snapshot being edited, when a record is written — and [heldElsewhere]
     * the highest order kept outside the store, 0 if none. Both only matter
     * the first time in a process: where it starts from is settled then, and
     * not again — its own records raise both, and an earlier ticket resolved
     * after a later one must still come out below it.
     */
    fun orderOf(ticket: Long, prefs: Preferences, heldElsewhere: Long): Long {
        val from = start ?: synchronized(this) {
            start ?: maxOf(ScanOutcomes.highestOrder(prefs), heldElsewhere).also { start = it }
        }
        return from + ticket
    }
}
