package ir.sobhanbeton.fleet.data

import java.util.Calendar

/** Derived views over weekly inspections: what's still open, and whether a vehicle has been
 *  inspected this week. */
object InspectionStats {

    data class OpenDefect(
        val vehicleId: String,
        val vehiclePlate: String,
        val category: VehicleCategory,
        val inspectionId: String,
        val partName: String,
        val status: PartStatus,
        val note: String?,
        /** When it was first seen: the first inspection in the unbroken run that flagged it. */
        val since: Long,
        val recurring: Boolean
    )

    /**
     * Every part that is WARN/BAD on a vehicle's latest inspection and not marked repaired. Only
     * the latest inspection counts: a part fixed and found OK next week is no longer open.
     */
    fun openDefects(inspections: List<InspectionEntity>): List<OpenDefect> =
        inspections.groupBy { it.vehicleId }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.timestamp } }
            .flatMap { latest ->
                InspectionJson.parse(latest.partsJson)
                    .filter { it.status != PartStatus.OK && it.repairedAt == null }
                    .map { part ->
                        OpenDefect(
                            vehicleId = latest.vehicleId,
                            vehiclePlate = latest.vehiclePlate,
                            category = latest.categoryEnum(),
                            inspectionId = latest.id,
                            partName = part.name,
                            status = part.status,
                            note = part.note,
                            since = part.recurringSinceTimestamp ?: latest.timestamp,
                            recurring = part.recurringSinceTimestamp != null
                        )
                    }
            }
            .sortedWith(compareByDescending<OpenDefect> { it.status == PartStatus.BAD }.thenBy { it.since })

    /**
     * Carries "still broken since …" forward: a part that's still not OK and was already not OK
     * on [previous] keeps the earliest date it was seen. Same rule as the security app.
     */
    fun withRecurrence(parts: List<InspectionPartResult>, previous: InspectionEntity?): List<InspectionPartResult> {
        if (previous == null) return parts
        val previousByName = InspectionJson.parse(previous.partsJson).associateBy { it.name }
        return parts.map { part ->
            if (part.status == PartStatus.OK) return@map part
            val prior = previousByName[part.name] ?: return@map part
            if (prior.status == PartStatus.OK) return@map part
            part.copy(recurringSinceTimestamp = prior.recurringSinceTimestamp ?: previous.timestamp)
        }
    }

    /** Start of the current Iranian week (Saturday 00:00) — "inspected this week" means at or
     *  after this. */
    fun startOfWeek(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        val daysSinceSaturday = (get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7
        add(Calendar.DAY_OF_MONTH, -daysSinceSaturday)
    }.timeInMillis
}
