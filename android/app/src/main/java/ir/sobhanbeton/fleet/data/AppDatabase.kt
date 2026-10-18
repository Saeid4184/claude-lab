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
    entities = [
        VehicleEntity::class, DriverEntity::class, AssignmentEntity::class, FuelLogEntity::class,
        InspectionEntity::class, WorkOrderEntity::class, ExpenseNoteEntity::class,
        ServicePlanEntity::class, ServiceLogEntity::class, DailyWorkEntity::class, IncidentEntity::class,
        TankConfigEntity::class, TankDeliveryEntity::class, TankWithdrawalEntity::class, TankReadingEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun vehicleDao(): VehicleDao
    abstract fun driverDao(): DriverDao
    abstract fun assignmentDao(): AssignmentDao
    abstract fun fuelLogDao(): FuelLogDao
    abstract fun inspectionDao(): InspectionDao
    abstract fun workOrderDao(): WorkOrderDao
    abstract fun expenseNoteDao(): ExpenseNoteDao
    abstract fun servicePlanDao(): ServicePlanDao
    abstract fun serviceLogDao(): ServiceLogDao
    abstract fun dailyWorkDao(): DailyWorkDao
    abstract fun incidentDao(): IncidentDao
    abstract fun tankDao(): TankDao

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

/** v2 → v3 (phase 3): fuel_logs loses its totalCost column (costs live only in the private
 *  expense notes), and the inspections, work_orders and expense_notes tables are added.
 *  SQLite on older Androids can't DROP COLUMN, so fuel_logs is rebuilt: new table, copy rows,
 *  swap. SQL is Room's own createSql for v3 (app/schemas/…/3.json), checked by MigrationTest. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `fuel_logs_new` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, " +
                "`vehiclePlate` TEXT NOT NULL, `category` TEXT NOT NULL, `driverId` TEXT, `driverName` TEXT, " +
                "`timestamp` INTEGER NOT NULL, `liters` REAL NOT NULL, `meterReading` INTEGER, `source` TEXT NOT NULL, " +
                "`notes` TEXT, `performedByName` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO `fuel_logs_new` (id, vehicleId, vehiclePlate, category, driverId, driverName, timestamp, " +
                "liters, meterReading, source, notes, performedByName, createdAt) " +
                "SELECT id, vehicleId, vehiclePlate, category, driverId, driverName, timestamp, " +
                "liters, meterReading, source, notes, performedByName, createdAt FROM `fuel_logs`"
        )
        db.execSQL("DROP TABLE `fuel_logs`")
        db.execSQL("ALTER TABLE `fuel_logs_new` RENAME TO `fuel_logs`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_logs_vehicleId` ON `fuel_logs` (`vehicleId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_logs_timestamp` ON `fuel_logs` (`timestamp`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `inspections` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, " +
                "`vehiclePlate` TEXT NOT NULL, `groupLabel` TEXT, `driverName` TEXT, `category` TEXT NOT NULL, " +
                "`partsJson` TEXT NOT NULL, `approvedCount` INTEGER NOT NULL, `rejectedCount` INTEGER NOT NULL, " +
                "`notes` TEXT, `performedByName` TEXT, `timestamp` INTEGER NOT NULL, `correctedAt` INTEGER, " +
                "`correctedByName` TEXT, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inspections_vehicleId` ON `inspections` (`vehicleId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inspections_timestamp` ON `inspections` (`timestamp`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `work_orders` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, " +
                "`vehiclePlate` TEXT NOT NULL, `category` TEXT NOT NULL, `driverName` TEXT, `reportedAt` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `details` TEXT, `severity` TEXT NOT NULL, `location` TEXT NOT NULL, " +
                "`workshopName` TEXT, `status` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER, " +
                "`meterReading` INTEGER, `partsUsed` TEXT, `workDone` TEXT, `sourceInspectionId` TEXT, " +
                "`sourcePartName` TEXT, `performedByName` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_work_orders_vehicleId` ON `work_orders` (`vehicleId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_work_orders_status` ON `work_orders` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_work_orders_reportedAt` ON `work_orders` (`reportedAt`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `expense_notes` (`id` TEXT NOT NULL, `date` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `amount` INTEGER NOT NULL, `notes` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_notes_date` ON `expense_notes` (`date`)")
    }
}

/** v3 → v4 (phase 4): periodic service plans and the services done. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `service_plans` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, `intervalMeter` INTEGER, `intervalDays` INTEGER, `notes` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `service_logs` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, " +
                "`vehiclePlate` TEXT NOT NULL, `planId` TEXT NOT NULL, `planName` TEXT NOT NULL, `doneAt` INTEGER NOT NULL, " +
                "`meterReading` INTEGER, `notes` TEXT, `performedByName` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_service_logs_vehicleId` ON `service_logs` (`vehicleId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_service_logs_planId` ON `service_logs` (`planId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_service_logs_doneAt` ON `service_logs` (`doneAt`)")
    }
}

/** v4 → v5 (phase 5): daily work (performance) and incidents. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_work` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, " +
                "`vehiclePlate` TEXT NOT NULL, `category` TEXT NOT NULL, `driverId` TEXT, `driverName` TEXT, " +
                "`date` INTEGER NOT NULL, `dayKey` INTEGER NOT NULL, `trips` INTEGER, `concreteM3` REAL, " +
                "`mineTrips` INTEGER, `debrisTrips` INTEGER, `notes` TEXT, `performedByName` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_work_vehicleId` ON `daily_work` (`vehicleId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_work_driverId` ON `daily_work` (`driverId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_work_dayKey` ON `daily_work` (`dayKey`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `incidents` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`occurredAt` INTEGER NOT NULL, `vehicleId` TEXT, `vehiclePlate` TEXT, `driverId` TEXT, " +
                "`driverName` TEXT, `subject` TEXT NOT NULL, `severity` TEXT, `location` TEXT, `actionTaken` TEXT, " +
                "`description` TEXT, `summaryText` TEXT NOT NULL, `performedByName` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incidents_vehicleId` ON `incidents` (`vehicleId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incidents_driverId` ON `incidents` (`driverId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incidents_occurredAt` ON `incidents` (`occurredAt`)")
    }
}

/** v5 → v6 (phase 7): diesel tank tables, and fuel_logs.managerApproved. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `fuel_logs` ADD COLUMN `managerApproved` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tank_config` (`id` TEXT NOT NULL, `capacityLiters` REAL NOT NULL, " +
                "`lowLevelLiters` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tank_deliveries` (`id` TEXT NOT NULL, `date` INTEGER NOT NULL, `liters` REAL NOT NULL, " +
                "`reference` TEXT, `supplier` TEXT, `notes` TEXT, `performedByName` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tank_deliveries_date` ON `tank_deliveries` (`date`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tank_withdrawals` (`id` TEXT NOT NULL, `date` INTEGER NOT NULL, `liters` REAL NOT NULL, " +
                "`consumer` TEXT NOT NULL, `notes` TEXT, `performedByName` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tank_withdrawals_date` ON `tank_withdrawals` (`date`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tank_readings` (`id` TEXT NOT NULL, `date` INTEGER NOT NULL, `liters` REAL NOT NULL, " +
                "`notes` TEXT, `performedByName` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tank_readings_date` ON `tank_readings` (`date`)")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
