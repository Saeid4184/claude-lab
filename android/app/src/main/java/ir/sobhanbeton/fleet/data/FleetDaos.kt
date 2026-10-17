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

@Dao
interface InspectionDao {
    @Query("SELECT * FROM inspections ORDER BY timestamp DESC")
    fun getAllLive(): LiveData<List<InspectionEntity>>

    @Query("SELECT * FROM inspections WHERE vehicleId = :vehicleId ORDER BY timestamp DESC")
    fun getForVehicleLive(vehicleId: String): LiveData<List<InspectionEntity>>

    @Query("SELECT * FROM inspections WHERE id = :id")
    suspend fun getById(id: String): InspectionEntity?

    @Query("SELECT * FROM inspections WHERE vehicleId = :vehicleId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForVehicle(vehicleId: String): InspectionEntity?

    /** The vehicle's inspection before [excludeId] (the one being corrected). */
    @Query("SELECT * FROM inspections WHERE vehicleId = :vehicleId AND id != :excludeId AND timestamp <= (SELECT timestamp FROM inspections WHERE id = :excludeId) ORDER BY timestamp DESC LIMIT 1")
    suspend fun getPreviousExcluding(vehicleId: String, excludeId: String): InspectionEntity?

    @Query("SELECT * FROM inspections WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun getInRange(from: Long, to: Long): List<InspectionEntity>

    @Upsert
    suspend fun upsert(inspection: InspectionEntity)

    @Query("DELETE FROM inspections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM inspections")
    suspend fun clear()
}

@Dao
interface WorkOrderDao {
    @Query("SELECT * FROM work_orders ORDER BY reportedAt DESC")
    fun getAllLive(): LiveData<List<WorkOrderEntity>>

    @Query("SELECT * FROM work_orders WHERE vehicleId = :vehicleId ORDER BY reportedAt DESC")
    fun getForVehicleLive(vehicleId: String): LiveData<List<WorkOrderEntity>>

    @Query("SELECT * FROM work_orders WHERE vehicleId = :vehicleId AND status != 'DONE'")
    suspend fun getOpenForVehicle(vehicleId: String): List<WorkOrderEntity>

    @Query("SELECT * FROM work_orders WHERE id = :id")
    suspend fun getById(id: String): WorkOrderEntity?

    @Query("SELECT * FROM work_orders WHERE id = :id")
    fun getByIdLive(id: String): LiveData<WorkOrderEntity?>

    @Query("SELECT * FROM work_orders WHERE sourceInspectionId = :inspectionId AND sourcePartName = :partName AND status != 'DONE' LIMIT 1")
    suspend fun getOpenForDefect(inspectionId: String, partName: String): WorkOrderEntity?

    @Query("SELECT * FROM work_orders WHERE reportedAt BETWEEN :from AND :to ORDER BY reportedAt")
    suspend fun getInRange(from: Long, to: Long): List<WorkOrderEntity>

    @Upsert
    suspend fun upsert(order: WorkOrderEntity)

    @Query("DELETE FROM work_orders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM work_orders")
    suspend fun clear()
}

@Dao
interface ExpenseNoteDao {
    @Query("SELECT * FROM expense_notes WHERE date BETWEEN :from AND :to ORDER BY date DESC, createdAt DESC")
    fun getInRangeLive(from: Long, to: Long): LiveData<List<ExpenseNoteEntity>>

    @Query("SELECT * FROM expense_notes WHERE id = :id")
    suspend fun getById(id: String): ExpenseNoteEntity?

    @Upsert
    suspend fun upsert(note: ExpenseNoteEntity)

    @Query("DELETE FROM expense_notes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM expense_notes")
    suspend fun clear()
}

@Dao
interface ServicePlanDao {
    @Query("SELECT * FROM service_plans ORDER BY category, name")
    fun getAllLive(): LiveData<List<ServicePlanEntity>>

    @Query("SELECT * FROM service_plans")
    suspend fun getAllOnce(): List<ServicePlanEntity>

    @Query("SELECT * FROM service_plans WHERE id = :id")
    suspend fun getById(id: String): ServicePlanEntity?

    @Upsert
    suspend fun upsert(plan: ServicePlanEntity)

    @Query("DELETE FROM service_plans WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM service_plans")
    suspend fun clear()
}

@Dao
interface ServiceLogDao {
    @Query("SELECT * FROM service_logs ORDER BY doneAt DESC")
    fun getAllLive(): LiveData<List<ServiceLogEntity>>

    @Query("SELECT * FROM service_logs")
    suspend fun getAllOnce(): List<ServiceLogEntity>

    @Query("SELECT * FROM service_logs WHERE doneAt BETWEEN :from AND :to ORDER BY doneAt")
    suspend fun getInRange(from: Long, to: Long): List<ServiceLogEntity>

    @Query("SELECT * FROM service_logs WHERE id = :id")
    suspend fun getById(id: String): ServiceLogEntity?

    @Upsert
    suspend fun upsert(log: ServiceLogEntity)

    @Query("DELETE FROM service_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM service_logs")
    suspend fun clear()
}
