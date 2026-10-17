package ir.sobhanbeton.fleet.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY category, groupLabel, plate")
    fun getAllLive(): LiveData<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles")
    suspend fun getAllOnce(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    fun getByIdLive(id: String): LiveData<VehicleEntity?>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun getById(id: String): VehicleEntity?

    @Upsert
    suspend fun upsert(vehicle: VehicleEntity)

    @Query("DELETE FROM vehicles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM vehicles")
    suspend fun clear()
}

@Dao
interface DriverDao {
    @Query("SELECT * FROM drivers ORDER BY name")
    fun getAllLive(): LiveData<List<DriverEntity>>

    @Query("SELECT * FROM drivers")
    suspend fun getAllOnce(): List<DriverEntity>

    @Query("SELECT * FROM drivers WHERE id = :id")
    fun getByIdLive(id: String): LiveData<DriverEntity?>

    @Query("SELECT * FROM drivers WHERE id = :id")
    suspend fun getById(id: String): DriverEntity?

    @Upsert
    suspend fun upsert(driver: DriverEntity)

    @Query("DELETE FROM drivers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM drivers")
    suspend fun clear()
}

@Dao
interface AssignmentDao {
    @Query("SELECT * FROM assignments WHERE vehicleId = :vehicleId ORDER BY startAt DESC")
    fun getForVehicleLive(vehicleId: String): LiveData<List<AssignmentEntity>>

    @Query("SELECT * FROM assignments WHERE driverId = :driverId ORDER BY startAt DESC")
    fun getForDriverLive(driverId: String): LiveData<List<AssignmentEntity>>

    /** The assignment of [vehicleId] in force at [at] (started on/before it, not yet ended). */
    @Query("SELECT * FROM assignments WHERE vehicleId = :vehicleId AND startAt <= :at AND (endAt IS NULL OR endAt > :at) ORDER BY startAt DESC LIMIT 1")
    suspend fun getForVehicleAt(vehicleId: String, at: Long): AssignmentEntity?

    @Query("SELECT * FROM assignments WHERE endAt IS NULL AND (vehicleId = :vehicleId OR driverId = :driverId)")
    suspend fun getOpenFor(vehicleId: String, driverId: String): List<AssignmentEntity>

    @Upsert
    suspend fun upsert(assignment: AssignmentEntity)

    @Query("DELETE FROM assignments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM assignments")
    suspend fun clear()
}

@Dao
interface FuelLogDao {
    @Query("SELECT * FROM fuel_logs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    fun getInRangeLive(from: Long, to: Long): LiveData<List<FuelLogEntity>>

    @Query("SELECT * FROM fuel_logs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun getInRange(from: Long, to: Long): List<FuelLogEntity>

    @Query("SELECT * FROM fuel_logs WHERE vehicleId = :vehicleId ORDER BY timestamp DESC")
    fun getForVehicleLive(vehicleId: String): LiveData<List<FuelLogEntity>>

    @Query("SELECT * FROM fuel_logs WHERE vehicleId = :vehicleId ORDER BY timestamp")
    suspend fun getForVehicle(vehicleId: String): List<FuelLogEntity>

    /** Every log since [from], for the fleet-wide consumption check. */
    @Query("SELECT * FROM fuel_logs WHERE timestamp >= :from ORDER BY timestamp")
    fun getSinceLive(from: Long): LiveData<List<FuelLogEntity>>

    @Query("SELECT * FROM fuel_logs WHERE id = :id")
    suspend fun getById(id: String): FuelLogEntity?

    @Upsert
    suspend fun upsert(log: FuelLogEntity)

    @Query("DELETE FROM fuel_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM fuel_logs")
    suspend fun clear()
}
