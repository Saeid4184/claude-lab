package ir.sobhanbeton.fleet.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.WriteBatch
import ir.sobhanbeton.fleet.util.NetworkMonitor
import kotlinx.coroutines.tasks.await

/**
 * Same architecture as the security app's Repository: Firestore is the source of truth and Room
 * (filled by [CloudSync]) is the offline-friendly mirror the UI reads. Every write goes to
 * Firestore; its listeners reflect it back into Room on every phone, including this one.
 *
 * Writes are routed through [awaitWrite]: offline, Firestore has already queued the change on
 * the device the moment it's issued, so we don't wait on a server acknowledgement that can't
 * arrive yet — the record reaches the server by itself once the connection is back.
 *
 * Only the EXPERT writes (firestore.rules enforce it); the UI hides write actions for MANAGER.
 *
 * One instance per process ([get]) so there is exactly one set of sync listeners however many
 * screens are open.
 */
class FleetRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val cloudSync = CloudSync(db)
    private val firestore = FirebaseFirestore.getInstance()
    private val vehiclesCol = firestore.collection(CloudSync.COLLECTION_VEHICLES)
    private val driversCol = firestore.collection(CloudSync.COLLECTION_DRIVERS)
    private val assignmentsCol = firestore.collection(CloudSync.COLLECTION_ASSIGNMENTS)
    private val fuelCol = firestore.collection(CloudSync.COLLECTION_FUEL_LOGS)

    fun startSync() = cloudSync.start()

    /** Called at sign-out: stop listening and forget this account's cached data. */
    fun stopSyncAndClear() {
        cloudSync.stop()
        cloudSync.clearLocalCache()
    }

    /** False while offline — lets screens say "saved, will upload once online" instead of a
     *  plain success message. */
    fun isOnline(): Boolean = NetworkMonitor.isOnline(appContext)

    private suspend fun awaitWrite(task: Task<Void>) {
        if (!NetworkMonitor.isOnline(appContext)) return
        try {
            task.await()
        } catch (e: Exception) {
            if (!isConnectivityFailure(e)) throw e
        }
    }

    private inline fun <T> writeResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(IllegalStateException(userMessage(e), e))
    }

    // ---------------------------------------------------------------- reads

    fun vehicles(): LiveData<List<VehicleEntity>> = db.vehicleDao().getAllLive()
    fun vehicle(id: String): LiveData<VehicleEntity?> = db.vehicleDao().getByIdLive(id)
    fun drivers(): LiveData<List<DriverEntity>> = db.driverDao().getAllLive()
    fun driver(id: String): LiveData<DriverEntity?> = db.driverDao().getByIdLive(id)
    fun vehicleAssignments(vehicleId: String): LiveData<List<AssignmentEntity>> =
        db.assignmentDao().getForVehicleLive(vehicleId)
    fun driverAssignments(driverId: String): LiveData<List<AssignmentEntity>> =
        db.assignmentDao().getForDriverLive(driverId)

    suspend fun getVehicle(id: String): VehicleEntity? = db.vehicleDao().getById(id)
    suspend fun getDriver(id: String): DriverEntity? = db.driverDao().getById(id)
    suspend fun allVehiclesOnce(): List<VehicleEntity> = db.vehicleDao().getAllOnce()
    suspend fun allDriversOnce(): List<DriverEntity> = db.driverDao().getAllOnce()

    // ---------------------------------------------------------------- vehicles

    /**
     * Creates (blank [VehicleEntity.id]) or updates a vehicle from the form. The plate must be
     * unique by its digits ([BulkImport.plateKey]). The current-driver link is owned by
     * [assignDriver], so whatever the form passes for it is ignored in favour of the stored one.
     */
    suspend fun saveVehicle(input: VehicleEntity): Result<String> = writeResult {
        val plate = input.plate.trim()
        require(plate.isNotEmpty()) { "شمارهٔ پلاک را وارد کنید" }
        val key = BulkImport.plateKey(plate)
        require(key.isNotEmpty()) { "پلاک باید شامل عدد باشد" }
        val duplicate = db.vehicleDao().getAllOnce().firstOrNull { it.id != input.id && BulkImport.plateKey(it.plate) == key }
        require(duplicate == null) { "ماشینی با پلاک «${duplicate?.plate}» از قبل ثبت شده است" }

        val now = System.currentTimeMillis()
        val existing = input.id.takeIf { it.isNotEmpty() }?.let { db.vehicleDao().getById(it) }
        val docRef = if (existing != null) vehiclesCol.document(existing.id) else vehiclesCol.document()
        val toWrite = input.copy(
            id = docRef.id,
            plate = plate,
            currentDriverId = existing?.currentDriverId,
            currentDriverName = existing?.currentDriverName,
            status = existing?.status ?: input.status,
            statusNote = existing?.statusNote ?: input.statusNote,
            statusChangedAt = existing?.statusChangedAt ?: input.statusChangedAt,
            meterUpdatedAt = if (existing?.currentMeter != input.currentMeter && input.currentMeter != null) now else existing?.meterUpdatedAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        awaitWrite(docRef.set(toWrite))
        docRef.id
    }

    suspend fun setVehicleStatus(vehicleId: String, status: VehicleStatus, note: String?): Result<Unit> = writeResult {
        val now = System.currentTimeMillis()
        awaitWrite(
            vehiclesCol.document(vehicleId).update(
                mapOf(
                    "status" to status.name,
                    "statusNote" to note?.trim()?.takeIf { it.isNotEmpty() },
                    "statusChangedAt" to now,
                    "updatedAt" to now
                )
            )
        )
    }

    /** Removes a vehicle registered by mistake. Its assignment history stays (it carries its own
     *  plate copy); an open assignment is closed and the driver's link cleared. */
    suspend fun deleteVehicle(vehicleId: String): Result<Unit> = writeResult {
        val now = System.currentTimeMillis()
        val batch = firestore.batch()
        closeOpenAssignments(batch, vehicleId = vehicleId, driverId = null, at = now)
        batch.delete(vehiclesCol.document(vehicleId))
        awaitWrite(batch.commit())
    }

    // ---------------------------------------------------------------- drivers

    suspend fun saveDriver(input: DriverEntity): Result<String> = writeResult {
        val name = input.name.trim()
        require(name.isNotEmpty()) { "نام راننده را وارد کنید" }
        val key = BulkImport.nameKey(name)
        val duplicate = db.driverDao().getAllOnce().firstOrNull { it.id != input.id && BulkImport.nameKey(it.name) == key }
        require(duplicate == null) { "راننده‌ای با نام «${duplicate?.name}» از قبل ثبت شده است" }

        val now = System.currentTimeMillis()
        val existing = input.id.takeIf { it.isNotEmpty() }?.let { db.driverDao().getById(it) }
        val docRef = if (existing != null) driversCol.document(existing.id) else driversCol.document()
        val toWrite = input.copy(
            id = docRef.id,
            name = name,
            currentVehicleId = existing?.currentVehicleId,
            currentVehiclePlate = existing?.currentVehiclePlate,
            status = existing?.status ?: input.status,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val batch = firestore.batch()
        batch.set(docRef, toWrite)
        // Keep the vehicle's copy of the driver's name in step with a rename.
        if (existing != null && existing.name != name && existing.currentVehicleId != null) {
            batch.update(vehiclesCol.document(existing.currentVehicleId), mapOf("currentDriverName" to name, "updatedAt" to now))
        }
        awaitWrite(batch.commit())
        docRef.id
    }

    /** Suspending keeps the driver on their vehicle (it's usually temporary); a driver who has
     *  LEFT is taken off it, since that vehicle needs someone else. */
    suspend fun setDriverStatus(driverId: String, status: DriverStatus): Result<Unit> = writeResult {
        val now = System.currentTimeMillis()
        val batch = firestore.batch()
        if (status == DriverStatus.LEFT) closeOpenAssignments(batch, vehicleId = null, driverId = driverId, at = now)
        batch.update(driversCol.document(driverId), mapOf("status" to status.name, "updatedAt" to now))
        awaitWrite(batch.commit())
    }

    suspend fun deleteDriver(driverId: String): Result<Unit> = writeResult {
        val now = System.currentTimeMillis()
        val batch = firestore.batch()
        closeOpenAssignments(batch, vehicleId = null, driverId = driverId, at = now)
        batch.delete(driversCol.document(driverId))
        awaitWrite(batch.commit())
    }

    // ---------------------------------------------------------------- assignments

    /**
     * Puts [driverId] on [vehicleId] from [at] (or takes the vehicle's driver off when
     * [driverId] is null). Any open assignment of this vehicle OR this driver is closed at [at],
     * and the denormalized current-driver/current-vehicle fields on both sides are updated in
     * the same batch, so the two can never disagree.
     */
    suspend fun assignDriver(vehicleId: String, driverId: String?, at: Long): Result<Unit> = writeResult {
        val vehicle = requireNotNull(db.vehicleDao().getById(vehicleId)) { "ماشین پیدا نشد" }
        val driver = driverId?.let { requireNotNull(db.driverDao().getById(it)) { "راننده پیدا نشد" } }
        if (driver != null && vehicle.currentDriverId == driver.id) return@writeResult

        val open = db.assignmentDao().getOpenFor(vehicleId, driverId ?: "")
        val latestStart = open.maxOfOrNull { it.startAt }
        require(latestStart == null || at >= latestStart) { "تاریخ نمی‌تواند قبل از شروع تخصیص فعلی باشد" }

        val now = System.currentTimeMillis()
        val batch = firestore.batch()
        closeOpenAssignments(batch, vehicleId, driverId, at)

        if (driver != null) {
            val ref = assignmentsCol.document()
            batch.set(
                ref,
                AssignmentEntity(
                    id = ref.id,
                    vehicleId = vehicle.id,
                    vehiclePlate = vehicle.plate,
                    driverId = driver.id,
                    driverName = driver.name,
                    startAt = at,
                    endAt = null,
                    performedByName = Session.currentUserOrGuestLabel()
                )
            )
            batch.update(vehiclesCol.document(vehicle.id), mapOf("currentDriverId" to driver.id, "currentDriverName" to driver.name, "updatedAt" to now))
            batch.update(driversCol.document(driver.id), mapOf("currentVehicleId" to vehicle.id, "currentVehiclePlate" to vehicle.plate, "updatedAt" to now))
        }
        awaitWrite(batch.commit())
    }

    /** Adds to [batch]: close every open assignment touching [vehicleId] or [driverId], and
     *  clear the current-link fields on whichever vehicles/drivers those assignments named. */
    private suspend fun closeOpenAssignments(batch: WriteBatch, vehicleId: String?, driverId: String?, at: Long) {
        val now = System.currentTimeMillis()
        val open = db.assignmentDao().getOpenFor(vehicleId ?: "", driverId ?: "")
        for (a in open) {
            batch.update(assignmentsCol.document(a.id), "endAt", at)
            if (db.vehicleDao().getById(a.vehicleId) != null) {
                batch.update(vehiclesCol.document(a.vehicleId), mapOf("currentDriverId" to null, "currentDriverName" to null, "updatedAt" to now))
            }
            if (db.driverDao().getById(a.driverId) != null) {
                batch.update(driversCol.document(a.driverId), mapOf("currentVehicleId" to null, "currentVehiclePlate" to null, "updatedAt" to now))
            }
        }
    }

    // ---------------------------------------------------------------- fuel

    fun fuelInRange(from: Long, to: Long): LiveData<List<FuelLogEntity>> = db.fuelLogDao().getInRangeLive(from, to)
    fun vehicleFuel(vehicleId: String): LiveData<List<FuelLogEntity>> = db.fuelLogDao().getForVehicleLive(vehicleId)
    fun fuelSince(from: Long): LiveData<List<FuelLogEntity>> = db.fuelLogDao().getSinceLive(from)
    suspend fun fuelInRangeOnce(from: Long, to: Long): List<FuelLogEntity> = db.fuelLogDao().getInRange(from, to)
    suspend fun vehicleFuelOnce(vehicleId: String): List<FuelLogEntity> = db.fuelLogDao().getForVehicle(vehicleId)
    suspend fun getFuelLog(id: String): FuelLogEntity? = db.fuelLogDao().getById(id)

    /** Who was driving [vehicleId] at [at], from the assignment history. */
    suspend fun driverAt(vehicleId: String, at: Long): AssignmentEntity? = db.assignmentDao().getForVehicleAt(vehicleId, at)

    /**
     * Creates (blank id) or corrects a fuel log. The plate/category and the driver on duty at
     * [FuelLogEntity.timestamp] are filled in here. A meter reading at least as high as the
     * vehicle's current one also becomes its current meter — in the same batch, so the vehicle
     * screen and the fuel history never disagree.
     */
    suspend fun saveFuel(input: FuelLogEntity): Result<String> = writeResult {
        require(input.liters > 0.0) { "مقدار سوخت را وارد کنید" }
        val vehicle = requireNotNull(db.vehicleDao().getById(input.vehicleId)) { "ماشین را انتخاب کنید" }
        val existing = input.id.takeIf { it.isNotEmpty() }?.let { db.fuelLogDao().getById(it) }
        val driver = db.assignmentDao().getForVehicleAt(vehicle.id, input.timestamp)
        val now = System.currentTimeMillis()
        val ref = if (existing != null) fuelCol.document(existing.id) else fuelCol.document()
        val toWrite = input.copy(
            id = ref.id,
            vehiclePlate = vehicle.plate,
            category = vehicle.category,
            driverId = driver?.driverId,
            driverName = driver?.driverName,
            performedByName = existing?.performedByName ?: Session.currentUserOrGuestLabel(),
            createdAt = existing?.createdAt ?: now
        )
        val batch = firestore.batch()
        batch.set(ref, toWrite)
        val reading = toWrite.meterReading
        if (reading != null && reading >= (vehicle.currentMeter ?: Long.MIN_VALUE)) {
            batch.update(vehiclesCol.document(vehicle.id), mapOf("currentMeter" to reading, "meterUpdatedAt" to toWrite.timestamp, "updatedAt" to now))
        }
        awaitWrite(batch.commit())
        ref.id
    }

    suspend fun deleteFuel(id: String): Result<Unit> = writeResult {
        awaitWrite(fuelCol.document(id).delete())
    }

    // ---------------------------------------------------------------- bulk import

    /**
     * Applies parsed [rows] (see [BulkImport]): reuses vehicles/drivers that already exist (by
     * plate digits / normalized name), creates the rest, and assigns a row's driver to its
     * vehicle when neither already has someone. Safe to run twice — the second run only reports
     * everything as existing.
     */
    suspend fun bulkImport(rows: List<BulkImport.Row>): Result<BulkImport.Summary> = writeResult {
        val now = System.currentTimeMillis()
        val vehiclesByKey = db.vehicleDao().getAllOnce().associateBy { BulkImport.plateKey(it.plate) }.toMutableMap()
        val driversByKey = db.driverDao().getAllOnce().associateBy { BulkImport.nameKey(it.name) }.toMutableMap()
        var vehiclesCreated = 0; var vehiclesExisting = 0
        var driversCreated = 0; var driversExisting = 0
        var assignmentsCreated = 0

        // Firestore caps a batch at 500 writes; each row is at most 4.
        val batches = mutableListOf(firestore.batch())
        var opsInBatch = 0
        fun op(block: (WriteBatch) -> Unit) {
            if (opsInBatch >= 400) { batches += firestore.batch(); opsInBatch = 0 }
            block(batches.last()); opsInBatch++
        }

        for (row in rows) {
            var vehicle = row.plate?.let { vehiclesByKey[BulkImport.plateKey(it)] }
            if (row.plate != null && vehicle == null) {
                val category = VehicleCategory.classify(row.group)
                val ref = vehiclesCol.document()
                vehicle = VehicleEntity(
                    id = ref.id,
                    plate = row.plate.trim(),
                    category = category.name,
                    groupLabel = row.group,
                    meterType = category.defaultMeterType.name,
                    createdAt = now,
                    updatedAt = now
                )
                op { it.set(ref, vehicle) }
                vehiclesByKey[BulkImport.plateKey(row.plate)] = vehicle
                vehiclesCreated++
            } else if (vehicle != null) {
                vehiclesExisting++
            }

            var driver = row.driverName?.let { driversByKey[BulkImport.nameKey(it)] }
            if (row.driverName != null && driver == null) {
                val ref = driversCol.document()
                driver = DriverEntity(id = ref.id, name = row.driverName.trim(), createdAt = now, updatedAt = now)
                op { it.set(ref, driver) }
                driversByKey[BulkImport.nameKey(row.driverName)] = driver
                driversCreated++
            } else if (driver != null) {
                driversExisting++
            }

            if (vehicle != null && driver != null && vehicle.currentDriverId == null && driver.currentVehicleId == null) {
                val ref = assignmentsCol.document()
                op {
                    it.set(ref, AssignmentEntity(ref.id, vehicle.id, vehicle.plate, driver.id, driver.name, now, null, Session.currentUserOrGuestLabel()))
                }
                op { it.update(vehiclesCol.document(vehicle.id), mapOf("currentDriverId" to driver.id, "currentDriverName" to driver.name)) }
                op { it.update(driversCol.document(driver.id), mapOf("currentVehicleId" to vehicle.id, "currentVehiclePlate" to vehicle.plate)) }
                val linkedVehicle = vehicle.copy(currentDriverId = driver.id, currentDriverName = driver.name)
                val linkedDriver = driver.copy(currentVehicleId = vehicle.id, currentVehiclePlate = vehicle.plate)
                vehiclesByKey[BulkImport.plateKey(vehicle.plate)] = linkedVehicle
                driversByKey[BulkImport.nameKey(driver.name)] = linkedDriver
                assignmentsCreated++
            }
        }
        for (batch in batches) awaitWrite(batch.commit())
        BulkImport.Summary(vehiclesCreated, vehiclesExisting, driversCreated, driversExisting, assignmentsCreated)
    }

    companion object {
        @Volatile
        private var INSTANCE: FleetRepository? = null

        fun get(context: Context): FleetRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: FleetRepository(context).also { INSTANCE = it }
        }
    }
}

private fun isConnectivityFailure(e: Exception): Boolean {
    if (e is FirebaseFirestoreException) {
        return e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
            e.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
    }
    val msg = e.message ?: return false
    return msg.contains("UNAVAILABLE", ignoreCase = true) || msg.contains("DEADLINE_EXCEEDED", ignoreCase = true)
}

private fun userMessage(e: Exception): String = when {
    e is IllegalArgumentException -> e.message ?: "اطلاعات نامعتبر است"
    e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
        "اجازهٔ این کار را ندارید"
    else -> "خطا در ذخیره: ${e.message}"
}
