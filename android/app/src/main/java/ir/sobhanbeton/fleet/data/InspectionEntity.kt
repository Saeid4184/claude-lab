package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * One weekly "بازدید ظاهری" of one vehicle — carried over from the security app (same parts
 * list and order per category, same JSON part format, see [InspectionCatalog]/[InspectionJson]),
 * now keyed by vehicle instead of the old shared roster row.
 *
 * [vehiclePlate]/[groupLabel]/[driverName] are copied at submission time so history reads right
 * after edits/reassignments. [category] is stored rather than re-derived so old records keep
 * their checklist even if the vehicle's category changes later. [approvedCount]/[rejectedCount]
 * are precomputed so lists never have to parse [partsJson]. [correctedAt]/[correctedByName]
 * mark a later correction; the original [timestamp] is kept so it stays in its week.
 */
@Entity(
    tableName = "inspections",
    indices = [Index("vehicleId"), Index("timestamp")]
)
data class InspectionEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val vehicleId: String = "",
    val vehiclePlate: String = "",
    val groupLabel: String? = null,
    val driverName: String? = null,
    val category: String = "",
    val partsJson: String = "",
    val approvedCount: Int = 0,
    val rejectedCount: Int = 0,
    val notes: String? = null,
    val performedByName: String? = null,
    val timestamp: Long = 0L,
    val correctedAt: Long? = null,
    val correctedByName: String? = null
) {
    fun categoryEnum(): VehicleCategory = VehicleCategory.fromStringOrDefault(category)
}
