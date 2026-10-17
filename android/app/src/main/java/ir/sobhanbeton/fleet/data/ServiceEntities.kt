package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * A periodic service rule for every vehicle of one [category]: "[name] every [intervalMeter]
 * (km or engine hours — whatever that vehicle's meter is) or every [intervalDays] days,
 * whichever comes first". Either interval may be null, not both.
 */
@Entity(tableName = "service_plans")
data class ServicePlanEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val name: String = "",
    val category: String = VehicleCategory.MIXER.name,
    val intervalMeter: Long? = null,
    val intervalDays: Int? = null,
    val notes: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun categoryEnum(): VehicleCategory = VehicleCategory.fromStringOrDefault(category)
}

/** One service actually done: the next due point of that plan for that vehicle is counted from
 *  here. [planName]/[vehiclePlate] are copied so history survives plan/vehicle edits. */
@Entity(
    tableName = "service_logs",
    indices = [Index("vehicleId"), Index("planId"), Index("doneAt")]
)
data class ServiceLogEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val vehicleId: String = "",
    val vehiclePlate: String = "",
    val planId: String = "",
    val planName: String = "",
    val doneAt: Long = 0L,
    val meterReading: Long? = null,
    val notes: String? = null,
    val performedByName: String? = null,
    val createdAt: Long = 0L
)
