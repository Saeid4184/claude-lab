package ir.sobhanbeton.fleet.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every schema step must keep what's already on the phone — including offline writes not yet
 * uploaded. Each test creates the database at the old version from the committed schema JSON,
 * inserts a row, runs the migration, and lets Room validate the result against the new schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ir.sobhanbeton.fleet.ui.TestApp::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2_keepsVehiclesAndAddsFuelLogs() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO vehicles (id, plate, category, meterType, status, createdAt, updatedAt) " +
                    "VALUES ('v1', '12345', 'MIXER', 'KM', 'ACTIVE', 0, 0)"
            )
        }

        helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT plate FROM vehicles WHERE id = 'v1'").use { c ->
                c.moveToFirst()
                assertEquals("12345", c.getString(0))
            }
            db.execSQL(
                "INSERT INTO fuel_logs (id, vehicleId, vehiclePlate, category, timestamp, liters, source, createdAt) " +
                    "VALUES ('f1', 'v1', '12345', 'MIXER', 1, 120.5, 'INTERNAL_TANK', 1)"
            )
        }
    }

    @Test
    fun allMigrationsReachLatestVersion() {
        helper.createDatabase(DB, 1).close()
        helper.runMigrationsAndValidate(DB, 2, true, *ALL_MIGRATIONS).close()
    }

    private companion object {
        const val DB = "migration-test"
    }
}
