package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * One machine in the fleet ("شناسنامهٔ ماشین"). Same pattern as the security app's entities:
 * [id] is the Firestore document ID and doubles as the Room primary key, so IDs never collide
 * between the expert's and the manager's phones.
 *
 * [category]/[meterType]/[status] hold enum names ([VehicleCategory], [MeterType],
 * [VehicleStatus]). [groupLabel] is the free-text brand/model group the roster is organized by
 * (e.g. "میکسر - آمیکو"), kept from the security app's roster.
 *
 * [currentMeter] is the latest odometer (km) or engine-hours reading; later phases (fuel, daily
 * work, service) update it. The three *Expiry fields are epoch millis of a time on the expiry day
 * (the picker stores local noon; comparisons use whole days), null when unknown.
 *
 * [currentDriverId]/[currentDriverName] are a denormalized copy of the open [AssignmentEntity]
 * (written in the same batch as it — see FleetRepository.assignDriver) so lists can show the
 * driver without a join; the assignments collection stays the full history.
 */
@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val plate: String = "",
    val category: String = VehicleCategory.LOGISTICS.name,
    val groupLabel: String? = null,
    val brandModel: String? = null,
    val manufactureYear: Int? = null,
    val chassisNumber: String? = null,
    val engineNumber: String? = null,
    val meterType: String = MeterType.KM.name,
    val currentMeter: Long? = null,
    val meterUpdatedAt: Long? = null,
    val status: String = VehicleStatus.ACTIVE.name,
    val statusNote: String? = null,
    val statusChangedAt: Long? = null,
    val thirdPartyInsuranceExpiry: Long? = null,
    val bodyInsuranceExpiry: Long? = null,
    val technicalInspectionExpiry: Long? = null,
    val notes: String? = null,
    val currentDriverId: String? = null,
    val currentDriverName: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun categoryEnum(): VehicleCategory = VehicleCategory.fromStringOrDefault(category)
    fun meterTypeEnum(): MeterType = MeterType.fromStringOrDefault(meterType)
    fun statusEnum(): VehicleStatus = VehicleStatus.fromStringOrDefault(status)
}
