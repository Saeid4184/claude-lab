package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * One vehicle's work on one day — the performance record. Km/engine hours are deliberately NOT
 * part of it (they aren't how this factory measures work); what counts depends on the vehicle:
 *
 * - mixer: [trips] (services delivered) + [concreteM3] (concrete carried)
 * - dump truck / trailer: [mineTrips] (loads brought from the quarry) + [debrisTrips] (rubble
 *   taken out of the factory)
 * - concrete pump: [concreteM3] (concrete discharged on site)
 * - loader / logistics: [trips] (optional count)
 *
 * [id] is "<vehicleId>_<yyyymmdd Jalali>" ([idFor]), so a vehicle has at most one record per
 * day even if entered on two phones. [driverId]/[driverName] default to the assignment on that
 * day but can be changed (a substitute driver), since the record also feeds driver performance.
 */
@Entity(
    tableName = "daily_work",
    indices = [Index("vehicleId"), Index("driverId"), Index("dayKey")]
)
data class DailyWorkEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val vehicleId: String = "",
    val vehiclePlate: String = "",
    val category: String = "",
    val driverId: String? = null,
    val driverName: String? = null,
    /** Local noon of the work day (what the Jalali picker returns). */
    val date: Long = 0L,
    val dayKey: Int = 0,
    val trips: Int? = null,
    val concreteM3: Double? = null,
    val mineTrips: Int? = null,
    val debrisTrips: Int? = null,
    val notes: String? = null,
    val performedByName: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun categoryEnum(): VehicleCategory = VehicleCategory.fromStringOrDefault(category)

    companion object {
        fun idFor(vehicleId: String, dayKey: Int): String = "${vehicleId}_$dayKey"
    }
}

/** Which daily-work fields a category reports — the single place the form, lists and
 *  reports ask, so they always agree. */
object WorkMetrics {
    enum class Field { TRIPS, CONCRETE_M3, MINE_TRIPS, DEBRIS_TRIPS }

    fun fieldsFor(category: VehicleCategory): List<Field> = when (category) {
        VehicleCategory.MIXER -> listOf(Field.TRIPS, Field.CONCRETE_M3)
        VehicleCategory.DUMP_TRUCK -> listOf(Field.MINE_TRIPS, Field.DEBRIS_TRIPS)
        VehicleCategory.CONCRETE_PUMP -> listOf(Field.CONCRETE_M3)
        VehicleCategory.LOADER, VehicleCategory.LOGISTICS -> listOf(Field.TRIPS)
    }
}
