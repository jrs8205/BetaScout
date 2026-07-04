package org.jarsi.betascout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 86_400_000L
private const val NOW = 100 * DAY

private fun status(
    packageName: String = "com.a",
    watching: Boolean = true,
    intervalDays: Int = 7,
    lastChecked: Long? = null,
    lastReminded: Long? = null,
) = UserBetaStatusInfo(
    packageName = packageName,
    watching = watching,
    reminderIntervalDays = intervalDays,
    lastCheckedByUser = lastChecked,
    lastRemindedAt = lastReminded,
)

class ReminderPolicyTest {

    @Test
    fun `apps not being watched are never due`() {
        val result = ReminderPolicy.dueForReminder(
            listOf(status(watching = false, lastChecked = NOW - 30 * DAY)),
            now = NOW,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `watched app with no history is due`() {
        val result = ReminderPolicy.dueForReminder(listOf(status()), now = NOW)
        assertEquals(1, result.size)
    }

    @Test
    fun `recently checked app is not due`() {
        val result = ReminderPolicy.dueForReminder(
            listOf(status(lastChecked = NOW - 3 * DAY, intervalDays = 7)),
            now = NOW,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `app checked longer ago than the interval is due`() {
        val result = ReminderPolicy.dueForReminder(
            listOf(status(lastChecked = NOW - 8 * DAY, intervalDays = 7)),
            now = NOW,
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `recent reminder throttles even when last check is old`() {
        val result = ReminderPolicy.dueForReminder(
            listOf(status(lastChecked = NOW - 30 * DAY, lastReminded = NOW - 2 * DAY, intervalDays = 7)),
            now = NOW,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `interval is applied per app`() {
        val result = ReminderPolicy.dueForReminder(
            listOf(
                status(packageName = "com.short", lastChecked = NOW - 10 * DAY, intervalDays = 7),
                status(packageName = "com.long", lastChecked = NOW - 10 * DAY, intervalDays = 30),
            ),
            now = NOW,
        )
        assertEquals(listOf("com.short"), result.map { it.packageName })
    }
}
