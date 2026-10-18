package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

enum class FuelSource(val displayName: String) {
    INTERNAL_TANK("مخزن کارخانه"),
    STATION("جایگاه سوخت");

    companion object {
        fun fromStringOrDefault(value: String?): FuelSource =
            runCatching { valueOf(value ?: INTERNAL_TANK.name) }.getOrDefault(INTERNAL_TANK)
    }
}

/**
 * "این مقدار گازوئیل به این ماشین تعلق گرفت" — one fuel allocation. Amounts are whatever was
 * given (tanks aren't filled to full), so consumption is never computed per fill: [FuelStats]
 * averages over a period instead, using the optional [meterReading]s inside it.
 *
 * [vehiclePlate]/[category] and [driverId]/[driverName] are copied at entry time (the driver from
 * the vehicle's assignment on [timestamp]), so history stays right after reassignments or edits.
 * No money fields: costs are kept only in the expert's private expense notes.
 */
@Entity(
    tableName = "fuel_logs",
    indices = [Index("vehicleId"), Index("timestamp")]
)
data class FuelLogEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val vehicleId: String = "",
    val vehiclePlate: String = "",
    val category: String = "",
    val driverId: String? = null,
    val driverName: String? = null,
    val timestamp: Long = 0L,
    val liters: Double = 0.0,
    val meterReading: Long? = null,
    val source: String = FuelSource.INTERNAL_TANK.name,
    val notes: String? = null,
    /** Tank fuel for a vehicle that needs it (see [TankStock.needsManagerApproval]) was given
     *  with the machinery manager's approval. Null when not applicable. */
    val managerApproved: Boolean? = null,
    val performedByName: String? = null,
    val createdAt: Long = 0L
) {
    fun sourceEnum(): FuelSource = FuelSource.fromStringOrDefault(source)
}
