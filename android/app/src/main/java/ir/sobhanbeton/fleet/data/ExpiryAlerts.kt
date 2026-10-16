package ir.sobhanbeton.fleet.data

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Builds the "مدارک در حال انقضا" list from the vehicles' and drivers' expiry dates: everything
 * already expired, or expiring within [WARNING_DAYS]. Pure (no Android/Firebase), so it's unit
 * tested directly.
 */
object ExpiryAlerts {

    const val WARNING_DAYS = 30

    enum class SubjectType { VEHICLE, DRIVER }

    data class Item(
        val subjectType: SubjectType,
        val subjectId: String,
        val subjectLabel: String,
        val documentLabel: String,
        val expiresAt: Long,
        /** Negative once expired; 0 = expires today. */
        val daysLeft: Int
    )

    fun build(
        vehicles: List<VehicleEntity>,
        drivers: List<DriverEntity>,
        now: Long = System.currentTimeMillis(),
        warningDays: Int = WARNING_DAYS
    ): List<Item> {
        val today = startOfDay(now)
        val items = mutableListOf<Item>()

        fun consider(type: SubjectType, id: String, label: String, document: String, expiresAt: Long?) {
            if (expiresAt == null) return
            val days = daysBetween(today, startOfDay(expiresAt))
            if (days <= warningDays) items += Item(type, id, label, document, expiresAt, days)
        }

        // Out-of-service vehicles and drivers who've left aren't operating, so their papers
        // lapsing isn't actionable — they'd only bury the alerts that matter.
        vehicles.filter { it.statusEnum() != VehicleStatus.OUT_OF_SERVICE }.forEach { v ->
            consider(SubjectType.VEHICLE, v.id, v.plate, DOC_THIRD_PARTY, v.thirdPartyInsuranceExpiry)
            consider(SubjectType.VEHICLE, v.id, v.plate, DOC_BODY, v.bodyInsuranceExpiry)
            consider(SubjectType.VEHICLE, v.id, v.plate, DOC_TECHNICAL, v.technicalInspectionExpiry)
        }
        drivers.filter { it.statusEnum() != DriverStatus.LEFT }.forEach { d ->
            consider(SubjectType.DRIVER, d.id, d.name, DOC_LICENSE, d.licenseExpiry)
            consider(SubjectType.DRIVER, d.id, d.name, DOC_SMART_CARD, d.smartCardExpiry)
            consider(SubjectType.DRIVER, d.id, d.name, DOC_HEALTH_CARD, d.healthCardExpiry)
        }
        return items.sortedBy { it.daysLeft }
    }

    const val DOC_THIRD_PARTY = "بیمهٔ شخص ثالث"
    const val DOC_BODY = "بیمهٔ بدنه"
    const val DOC_TECHNICAL = "معاینهٔ فنی"
    const val DOC_LICENSE = "گواهی‌نامه"
    const val DOC_SMART_CARD = "کارت هوشمند"
    const val DOC_HEALTH_CARD = "کارت سلامت"

    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Whole calendar days; rounding absorbs the ±1h of a daylight-saving change in between. */
    private fun daysBetween(fromStartOfDay: Long, toStartOfDay: Long): Int =
        Math.round((toStartOfDay - fromStartOfDay).toDouble() / TimeUnit.DAYS.toMillis(1)).toInt()
}
