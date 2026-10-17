package ir.sobhanbeton.fleet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/** STOPPING: the vehicle can't work until it's fixed (it's switched to «در تعمیر» while the
 *  order is open). RUNNING: it can keep working meanwhile. */
enum class WorkOrderSeverity(val displayName: String) {
    STOPPING("متوقف‌کننده"),
    RUNNING("غیرمتوقف‌کننده");

    companion object {
        fun fromStringOrDefault(value: String?): WorkOrderSeverity =
            runCatching { valueOf(value ?: RUNNING.name) }.getOrDefault(RUNNING)
    }
}

enum class WorkOrderLocation(val displayName: String) {
    INTERNAL("تعمیرگاه کارخانه"),
    EXTERNAL("تعمیرگاه بیرون");

    companion object {
        fun fromStringOrDefault(value: String?): WorkOrderLocation =
            runCatching { valueOf(value ?: INTERNAL.name) }.getOrDefault(INTERNAL)
    }
}

enum class WorkOrderStatus(val displayName: String) {
    OPEN("ثبت‌شده"),
    IN_PROGRESS("در حال تعمیر"),
    DONE("انجام‌شده");

    companion object {
        fun fromStringOrDefault(value: String?): WorkOrderStatus =
            runCatching { valueOf(value ?: OPEN.name) }.getOrDefault(OPEN)
    }
}

/**
 * "دستور تعمیر": one breakdown/defect and its repair. Deliberately has no money fields — costs
 * live only in the expert's private expense notes ([ExpenseNoteEntity]) and never mix into the
 * operational records or reports.
 *
 * [reportedAt] → [completedAt] is the downtime for a STOPPING order. [partsUsed]/[workDone] are
 * free text. [sourceInspectionId]/[sourcePartName] link an order created from a weekly
 * inspection defect, so finishing it also marks that part repaired on the inspection.
 */
@Entity(
    tableName = "work_orders",
    indices = [Index("vehicleId"), Index("status"), Index("reportedAt")]
)
data class WorkOrderEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val vehicleId: String = "",
    val vehiclePlate: String = "",
    val category: String = "",
    val driverName: String? = null,
    val reportedAt: Long = 0L,
    val title: String = "",
    val details: String? = null,
    val severity: String = WorkOrderSeverity.RUNNING.name,
    val location: String = WorkOrderLocation.INTERNAL.name,
    val workshopName: String? = null,
    val status: String = WorkOrderStatus.OPEN.name,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val meterReading: Long? = null,
    val partsUsed: String? = null,
    val workDone: String? = null,
    val sourceInspectionId: String? = null,
    val sourcePartName: String? = null,
    val performedByName: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun severityEnum(): WorkOrderSeverity = WorkOrderSeverity.fromStringOrDefault(severity)
    fun locationEnum(): WorkOrderLocation = WorkOrderLocation.fromStringOrDefault(location)
    fun statusEnum(): WorkOrderStatus = WorkOrderStatus.fromStringOrDefault(status)
}
