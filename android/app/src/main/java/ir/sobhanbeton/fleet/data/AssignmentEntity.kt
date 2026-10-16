package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * "Who drove which vehicle, from when to when." [endAt] null = the current assignment. Plate and
 * name are denormalized at assignment time so history reads correctly even after a vehicle or
 * driver is later edited or removed — the same reasoning as the security app's LogEntity.
 * Later phases (fuel, incidents, daily work) look up the driver of a vehicle on a given date
 * from these rows.
 */
@Entity(
    tableName = "assignments",
    indices = [Index("vehicleId"), Index("driverId")]
)
data class AssignmentEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val vehicleId: String = "",
    val vehiclePlate: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val startAt: Long = 0L,
    val endAt: Long? = null,
    val performedByName: String? = null
)
