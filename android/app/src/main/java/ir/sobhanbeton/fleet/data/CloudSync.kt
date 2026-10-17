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
    }
}
