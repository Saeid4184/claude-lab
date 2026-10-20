package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

enum class PartKind(val displayName: String) {
    TIRE("تعویض لاستیک"),
    BATTERY("تعویض باتری");

    companion object {
        fun fromStringOrDefault(value: String?): PartKind = runCatching { valueOf(value ?: TIRE.name) }.getOrDefault(TIRE)
    }
}

/**
 * A tyre or battery replacement — kept deliberately simple: when, and (for tyres) which wheels.
 * [wheels] holds [WheelPositions] codes separated by commas, e.g. "F_L,A2_RO".
 */
@Entity(tableName = "part_changes", indices = [Index("vehicleId"), Index("date")])
data class PartChangeEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val vehicleId: String = "",
    val vehiclePlate: String = "",
    val kind: String = PartKind.TIRE.name,
    val date: Long = 0L,
    val wheels: String? = null,
    val meterReading: Long? = null,
    val notes: String? = null,
    val performedByName: String? = null,
    val createdAt: Long = 0L
) {
    fun kindEnum(): PartKind = PartKind.fromStringOrDefault(kind)
    fun wheelCodes(): List<String> = wheels?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/**
 * The wheel positions offered for a vehicle: front pair, dual rear axles (outer/inner each side)
 * for trucks/mixers/pumps — an extra axle for trailers — four wheels for loaders and light
 * vehicles, plus the spare. Pure, unit tested.
 */
object WheelPositions {

    data class Position(val code: String, val label: String)

    private val front = listOf(Position("F_L", "جلو چپ"), Position("F_R", "جلو راست"))
    private fun dualAxle(n: Int) = listOf(
        Position("A${n}_LO", "محور $n چپ بیرون"), Position("A${n}_LI", "محور $n چپ داخل"),
        Position("A${n}_RI", "محور $n راست داخل"), Position("A${n}_RO", "محور $n راست بیرون")
    )
    private val rearSingle = listOf(Position("R_L", "عقب چپ"), Position("R_R", "عقب راست"))
    private val spare = Position("SPARE", "زاپاس")

    fun positionsFor(vehicle: VehicleEntity): List<Position> = when (vehicle.categoryEnum()) {
        VehicleCategory.LOADER, VehicleCategory.LOGISTICS -> front + rearSingle + spare
        VehicleCategory.DUMP_TRUCK ->
            if (vehicle.groupLabel.orEmpty().let { it.contains("تریلی") || it.contains("تریلر") }) front + dualAxle(2) + dualAxle(3) + dualAxle(4) + spare
            else front + dualAxle(2) + dualAxle(3) + spare
        VehicleCategory.MIXER, VehicleCategory.CONCRETE_PUMP -> front + dualAxle(2) + dualAxle(3) + spare
    }

    /** Label for a stored code (codes from a since-changed layout still read sensibly). */
    fun label(code: String): String =
        (front + rearSingle + dualAxle(2) + dualAxle(3) + dualAxle(4) + spare).firstOrNull { it.code == code }?.label ?: code

    /** For each wheel, its most recent change — what the vehicle screen shows. */
    fun lastChangeByWheel(changes: List<PartChangeEntity>): Map<String, PartChangeEntity> {
        val result = mutableMapOf<String, PartChangeEntity>()
        changes.filter { it.kindEnum() == PartKind.TIRE }.sortedBy { it.date }.forEach { c ->
            c.wheelCodes().forEach { result[it] = c }
        }
        return result
    }
}
