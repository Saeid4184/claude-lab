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
    fun migrate2To3_keepsFuelRowsWithoutCost() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO fuel_logs (id, vehicleId, vehiclePlate, category, timestamp, liters, meterReading, source, totalCost, createdAt) " +
                    "VALUES ('f1', 'v1', '12345', 'MIXER', 5, 80.0, 1000, 'STATION', 999, 5)"
            )
        }
        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT liters, meterReading, source FROM fuel_logs WHERE id = 'f1'").use { c ->
                c.moveToFirst()
                assertEquals(80.0, c.getDouble(0), 0.001)
                assertEquals(1000L, c.getLong(1))
                assertEquals("STATION", c.getString(2))
                assertEquals(-1, c.getColumnIndex("totalCost"))
            }
        }
    }

    @Test
    fun migrate3To4_addsServiceTables() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL("INSERT INTO work_orders (id, vehicleId, vehiclePlate, category, reportedAt, title, severity, location, status, createdAt, updatedAt) " +
                "VALUES ('w1', 'v1', '12345', 'MIXER', 1, 'x', 'STOPPING', 'INTERNAL', 'OPEN', 1, 1)")
        }
        helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT COUNT(*) FROM work_orders").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.execSQL("INSERT INTO service_logs (id, vehicleId, vehiclePlate, planId, planName, doneAt, createdAt) VALUES ('s1', 'v1', '1', 'p', 'روغن', 1, 1)")
        }
    }

    @Test
    fun migrate4To5_addsDailyWorkAndIncidents() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL("INSERT INTO service_plans (id, name, category, intervalDays, createdAt, updatedAt) VALUES ('p', 'روغن', 'MIXER', 90, 1, 1)")
        }
        helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5).use { db ->
            db.query("SELECT COUNT(*) FROM service_plans").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.execSQL("INSERT INTO daily_work (id, vehicleId, vehiclePlate, category, date, dayKey, trips, concreteM3, createdAt, updatedAt) " +
                "VALUES ('v_14050725', 'v', '1', 'MIXER', 1, 14050725, 8, 64.0, 1, 1)")
        }
    }

    @Test
    fun migrate5To6_addsTankAndKeepsFuel() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL("INSERT INTO fuel_logs (id, vehicleId, vehiclePlate, category, timestamp, liters, source, createdAt) VALUES ('f1', 'v', '1', 'MIXER', 1, 80.0, 'INTERNAL_TANK', 1)")
        }
        helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6).use { db ->
            db.query("SELECT liters, managerApproved FROM fuel_logs WHERE id = 'f1'").use { c ->
                c.moveToFirst()
                assertEquals(80.0, c.getDouble(0), 0.001)
                org.junit.Assert.assertTrue(c.isNull(1))
            }
            db.execSQL("INSERT INTO tank_readings (id, date, liters, createdAt) VALUES ('r', 1, 5000.0, 1)")
        }
    }

    @Test
    fun allMigrationsReachLatestVersion() {
        helper.createDatabase(DB, 1).close()
        helper.runMigrationsAndValidate(DB, 6, true, *ALL_MIGRATIONS).close()
    }

    private companion object {
        const val DB = "migration-test"
    }
}
