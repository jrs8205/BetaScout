package org.jarsi.betascout.domain

object ReminderPolicy {

    /**
     * Returns the watched statuses whose reminder interval has elapsed since the
     * user last checked the app or was last reminded, whichever is more recent.
     */
    fun dueForReminder(statuses: List<UserBetaStatusInfo>, now: Long): List<UserBetaStatusInfo> =
        statuses.filter { status ->
            if (!status.watching) return@filter false
            val anchor = maxOf(status.lastCheckedByUser ?: 0L, status.lastRemindedAt ?: 0L)
            now - anchor >= status.reminderIntervalDays * DAY_MILLIS
        }

    private const val DAY_MILLIS = 86_400_000L
}
