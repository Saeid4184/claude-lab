package ir.sobhanbeton.fleet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room is a LOCAL CACHE mirroring Firestore (see [CloudSync]); Firestore is the source of truth.
 * Every write goes to Firestore and its listeners update every device's Room cache, including
 * the one that made the write, while Room gives the UI instant, offline-friendly LiveData reads.
 *
 * Schema changes from here on bump [version] and add an explicit Migration — no destructive
 * fallback — so an upgrade never drops what's cached on the phone (including writes made
 * offline that haven't reached the server yet).
 */
@Database(
    entities = [VehicleEntity::class, DriverEntity::class, AssignmentEntity::class, FuelLogEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun vehicleDao(): VehicleDao
    abstract fun driverDao(): DriverDao
    abstract fun assignmentDao(): AssignmentDao
    abstract fun fuelLogDao(): FuelLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        const val DB_NAME = "sobhan_fleet.db"

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

/** v1 → v2 (phase 2): adds the fuel_logs table. SQL is Room's own createSql for v2 (see
 *  app/schemas/…/2.json); MigrationTest checks the migrated schema matches it exactly. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `fuel_logs` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, " +
                "`vehiclePlate` TEXT NOT NULL, `category` TEXT NOT NULL, `driverId` TEXT, `driverName` TEXT, " +
                "`timestamp` INTEGER NOT NULL, `liters` REAL NOT NULL, `meterReading` INTEGER, `source` TEXT NOT NULL, " +
                "`totalCost` INTEGER, `notes` TEXT, `performedByName` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_logs_vehicleId` ON `fuel_logs` (`vehicleId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_logs_timestamp` ON `fuel_logs` (`timestamp`)")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
