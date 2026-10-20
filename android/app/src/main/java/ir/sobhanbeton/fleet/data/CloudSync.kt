package ir.sobhanbeton.fleet.data

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Mirrors the fleet's Firestore collections into the local Room cache in real time — the same
 * mechanism as the security app: whichever phone (the expert's or the manager's) writes a
 * change, Firestore fans it back out to every listener, and this class writes it into Room so
 * the LiveData-based UI updates by itself. The fleet is small (tens of vehicles/drivers, a few
 * fuel logs a day), so every collection is mirrored in full rather than windowed.
 */
class CloudSync(private val db: AppDatabase) {

    private val firestore = FirebaseFirestore.getInstance()
    private val listeners = mutableListOf<ListenerRegistration>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun start() {
        if (listeners.isNotEmpty()) return // already running

        listeners += mirror(COLLECTION_VEHICLES,
            upsert = { db.vehicleDao().upsert(it.toObject(VehicleEntity::class.java)!!) },
            delete = { db.vehicleDao().deleteById(it) })
        listeners += mirror(COLLECTION_DRIVERS,
            upsert = { db.driverDao().upsert(it.toObject(DriverEntity::class.java)!!) },
            delete = { db.driverDao().deleteById(it) })
        listeners += mirror(COLLECTION_ASSIGNMENTS,
            upsert = { db.assignmentDao().upsert(it.toObject(AssignmentEntity::class.java)!!) },
            delete = { db.assignmentDao().deleteById(it) })
        listeners += mirror(COLLECTION_FUEL_LOGS,
            upsert = { db.fuelLogDao().upsert(it.toObject(FuelLogEntity::class.java)!!) },
            delete = { db.fuelLogDao().deleteById(it) })
        listeners += mirror(COLLECTION_INSPECTIONS,
            upsert = { db.inspectionDao().upsert(it.toObject(InspectionEntity::class.java)!!) },
            delete = { db.inspectionDao().deleteById(it) })
        listeners += mirror(COLLECTION_WORK_ORDERS,
            upsert = { db.workOrderDao().upsert(it.toObject(WorkOrderEntity::class.java)!!) },
            delete = { db.workOrderDao().deleteById(it) })
        listeners += mirror(COLLECTION_SERVICE_PLANS,
            upsert = { db.servicePlanDao().upsert(it.toObject(ServicePlanEntity::class.java)!!) },
            delete = { db.servicePlanDao().deleteById(it) })
        listeners += mirror(COLLECTION_SERVICE_LOGS,
            upsert = { db.serviceLogDao().upsert(it.toObject(ServiceLogEntity::class.java)!!) },
            delete = { db.serviceLogDao().deleteById(it) })
        listeners += mirror(COLLECTION_DAILY_WORK,
            upsert = { db.dailyWorkDao().upsert(it.toObject(DailyWorkEntity::class.java)!!) },
            delete = { db.dailyWorkDao().deleteById(it) })
        listeners += mirror(COLLECTION_INCIDENTS,
            upsert = { db.incidentDao().upsert(it.toObject(IncidentEntity::class.java)!!) },
            delete = { db.incidentDao().deleteById(it) })
        listeners += mirror(COLLECTION_TANK_CONFIG,
            upsert = { db.tankDao().upsertConfig(it.toObject(TankConfigEntity::class.java)!!) },
            delete = { db.tankDao().deleteConfig(it) })
        listeners += mirror(COLLECTION_TANK_DELIVERIES,
            upsert = { db.tankDao().upsertDelivery(it.toObject(TankDeliveryEntity::class.java)!!) },
            delete = { db.tankDao().deleteDelivery(it) })
        listeners += mirror(COLLECTION_TANK_WITHDRAWALS,
            upsert = { db.tankDao().upsertWithdrawal(it.toObject(TankWithdrawalEntity::class.java)!!) },
            delete = { db.tankDao().deleteWithdrawal(it) })
        listeners += mirror(COLLECTION_TANK_READINGS,
            upsert = { db.tankDao().upsertReading(it.toObject(TankReadingEntity::class.java)!!) },
            delete = { db.tankDao().deleteReading(it) })
        listeners += mirror(COLLECTION_PART_CHANGES,
            upsert = { db.partChangeDao().upsert(it.toObject(PartChangeEntity::class.java)!!) },
            delete = { db.partChangeDao().deleteById(it) })
        listeners += mirror(COLLECTION_HOUSING_ROOMS,
            upsert = { db.housingDao().upsert(it.toObject(HousingRoomEntity::class.java)!!) },
            delete = { db.housingDao().deleteById(it) })
        // Private to the expert: never mirrored onto the manager's phone (rules would refuse
        // the read anyway).
        if (Session.canEdit()) {
            listeners += mirror(COLLECTION_EXPENSE_NOTES,
                upsert = { db.expenseNoteDao().upsert(it.toObject(ExpenseNoteEntity::class.java)!!) },
                delete = { db.expenseNoteDao().deleteById(it) })
        }
    }

    @Synchronized
    fun stop() {
        listeners.forEach { it.remove() }
        listeners.clear()
    }

    /** Wipes the on-phone mirror at sign-out, so a different account signing in on the same
     *  phone never briefly sees the previous account's cached data. */
    fun clearLocalCache() {
        scope.launch {
            db.vehicleDao().clear()
            db.driverDao().clear()
            db.assignmentDao().clear()
            db.fuelLogDao().clear()
            db.inspectionDao().clear()
            db.workOrderDao().clear()
            db.expenseNoteDao().clear()
            db.servicePlanDao().clear()
            db.serviceLogDao().clear()
            db.dailyWorkDao().clear()
            db.incidentDao().clear()
            db.tankDao().clearConfig()
            db.tankDao().clearDeliveries()
            db.tankDao().clearWithdrawals()
            db.tankDao().clearReadings()
            db.partChangeDao().clear()
            db.housingDao().clear()
        }
    }

    private fun mirror(
        collection: String,
        upsert: suspend (DocumentSnapshot) -> Unit,
        delete: suspend (String) -> Unit
    ): ListenerRegistration = firestore.collection(collection)
        .addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            scope.launch {
                for (change in snapshot.documentChanges) {
                    // One malformed document must not stop the rest of the batch from syncing.
                    runCatching {
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> upsert(change.document)
                            DocumentChange.Type.REMOVED -> delete(change.document.id)
                        }
                    }
                }
            }
        }

    companion object {
        const val COLLECTION_VEHICLES = "vehicles"
        const val COLLECTION_DRIVERS = "drivers"
        const val COLLECTION_ASSIGNMENTS = "assignments"
        const val COLLECTION_FUEL_LOGS = "fuelLogs"
        const val COLLECTION_INSPECTIONS = "inspections"
        const val COLLECTION_WORK_ORDERS = "workOrders"
        const val COLLECTION_EXPENSE_NOTES = "expenseNotes"
        const val COLLECTION_SERVICE_PLANS = "servicePlans"
        const val COLLECTION_SERVICE_LOGS = "serviceLogs"
        const val COLLECTION_DAILY_WORK = "dailyWork"
        const val COLLECTION_INCIDENTS = "incidents"
        const val COLLECTION_TANK_CONFIG = "tankConfig"
        const val COLLECTION_TANK_DELIVERIES = "tankDeliveries"
        const val COLLECTION_TANK_WITHDRAWALS = "tankWithdrawals"
        const val COLLECTION_TANK_READINGS = "tankReadings"
        const val COLLECTION_PART_CHANGES = "partChanges"
        const val COLLECTION_HOUSING_ROOMS = "housingRooms"
    }
}
