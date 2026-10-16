package ir.sobhanbeton.fleet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    entities = [VehicleEntity::class, DriverEntity::class, AssignmentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun vehicleDao(): VehicleDao
    abstract fun driverDao(): DriverDao
    abstract fun assignmentDao(): AssignmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        const val DB_NAME = "sobhan_fleet.db"

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
