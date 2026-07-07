package org.jarsi.betascout.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val DB_NAME = "migration-test.db"

/**
 * Walks a database created at the shipped v2 schema (v0.3.2 installs) through every
 * migration to the current version, validating each resulting schema against the
 * exported schema JSONs and asserting that user data survives.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migratesFrom2To5PreservingUserData() {
        helper.createDatabase(DB_NAME, 2).apply {
            execSQL(
                "INSERT INTO installed_apps " +
                    "(packageName, label, versionName, versionCode, installerPackage, isSystem, lastScanned) " +
                    "VALUES ('com.example.app', 'Example', '1.0', 10, 'com.android.vending', 0, 123)",
            )
            execSQL(
                "INSERT INTO beta_programs " +
                    "(packageName, appName, testingUrl, knownStatus, productionVersionCode, notes, source) " +
                    "VALUES ('com.example.app', 'Example', NULL, 'UNKNOWN', 9, NULL, 'REMOTE')",
            )
            execSQL(
                "INSERT INTO user_beta_status " +
                    "(packageName, state, watching, reminderIntervalDays, lastCheckedByUser, userNote, lastRemindedAt) " +
                    "VALUES ('com.example.app', 'JOINED', 1, 7, 42, 'my note', 41)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME, 5, true, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        )

        db.query(
            "SELECT label, versionCode, hasLauncher FROM installed_apps WHERE packageName = 'com.example.app'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Example", cursor.getString(0))
            assertEquals(10L, cursor.getLong(1))
            // Added by MIGRATION_4_5 with DEFAULT 0; the next package scan fills it in.
            assertEquals(0L, cursor.getLong(2))
        }
        db.query(
            "SELECT state, watching, userNote FROM user_beta_status WHERE packageName = 'com.example.app'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("JOINED", cursor.getString(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals("my note", cursor.getString(2))
        }
        db.query("SELECT COUNT(*) FROM beta_programs").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM beta_observations").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
    }

    @Test
    fun migration3To4DropsUnattributableObservations() {
        helper.createDatabase(DB_NAME, 3).apply {
            execSQL(
                "INSERT INTO beta_observations " +
                    "(packageName, liveStatus, observedMembership, checkedAt, lastError) " +
                    "VALUES ('com.example.app', 'OPEN', 'JOINED', 1, NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4)

        // v3 rows were package-only and cannot be attributed to an account; the
        // migration drops them and the next signed-in scan rebuilds the table.
        db.query("SELECT COUNT(*) FROM beta_observations").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
    }
}
