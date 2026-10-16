package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * One driver/operator ("شناسنامهٔ راننده"). [status] is a [DriverStatus] name — SUSPENDED
 * replaces the security app's blacklist. The *Expiry fields are epoch millis of a time on the
 * expiry day, null when unknown. [currentVehicleId]/[currentVehiclePlate] mirror the open
 * [AssignmentEntity], same reasoning as [VehicleEntity.currentDriverId].
 */
@Entity(tableName = "drivers")
data class DriverEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val name: String = "",
    val phone: String? = null,
    val nationalCode: String? = null,
    val licenseType: String? = null,
    val licenseNumber: String? = null,
    val licenseExpiry: Long? = null,
    val smartCardExpiry: Long? = null,
    val healthCardExpiry: Long? = null,
    val status: String = DriverStatus.ACTIVE.name,
    val notes: String? = null,
    val currentVehicleId: String? = null,
    val currentVehiclePlate: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun statusEnum(): DriverStatus = DriverStatus.fromStringOrDefault(status)
}
