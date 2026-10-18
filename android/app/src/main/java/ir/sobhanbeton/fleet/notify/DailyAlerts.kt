package ir.sobhanbeton.fleet.notify

import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.ExpiryAlerts
import ir.sobhanbeton.fleet.data.FuelLogEntity
import ir.sobhanbeton.fleet.data.FuelStats
import ir.sobhanbeton.fleet.data.ServiceLogEntity
import ir.sobhanbeton.fleet.data.ServicePlanEntity
import ir.sobhanbeton.fleet.data.ServiceSchedule
import ir.sobhanbeton.fleet.data.VehicleEntity

/** What the daily reminder says — computed from the same rules the screens use. Pure, so the
 *  wording/thresholds are unit tested without a device. */
object DailyAlerts {

    data class Counts(
        val servicesOverdue: Int,
        val servicesDueSoon: Int,
        val documentsExpired: Int,
        val documentsExpiringSoon: Int,
        val fuelAnomalies: Int,
        /** Tank stock when it's below the warning level, else null. */
        val tankLowStock: Double? = null
    ) {
        val isEmpty: Boolean get() =
            servicesOverdue + servicesDueSoon + documentsExpired + documentsExpiringSoon + fuelAnomalies == 0 && tankLowStock == null
    }

    /** Documents only nag within this many days (the home card shows the full 30-day window). */
    const val DOCUMENT_NOTIFY_DAYS = 7

    fun count(
        vehicles: List<VehicleEntity>,
        drivers: List<DriverEntity>,
        plans: List<ServicePlanEntity>,
        serviceLogs: List<ServiceLogEntity>,
        fuelLogs: List<FuelLogEntity>,
        now: Long = System.currentTimeMillis(),
        tankStock: Double? = null,
        tankLowLevel: Double = 0.0
    ): Counts {
        val services = ServiceSchedule.all(vehicles, plans, serviceLogs, now)
        val docs = ExpiryAlerts.build(vehicles, drivers, now, warningDays = DOCUMENT_NOTIFY_DAYS)
        return Counts(
            servicesOverdue = services.count { it.state == ServiceSchedule.State.OVERDUE },
            servicesDueSoon = services.count { it.state == ServiceSchedule.State.DUE_SOON },
            documentsExpired = docs.count { it.daysLeft < 0 },
            documentsExpiringSoon = docs.count { it.daysLeft >= 0 },
            fuelAnomalies = FuelStats.anomalies(fuelLogs, vehicles, now).size,
            tankLowStock = tankStock?.takeIf { it < tankLowLevel }
        )
    }

    /** One line per non-zero item, e.g. "۳ سرویس عقب افتاده". Persian digits are applied by
     *  the caller. */
    fun lines(c: Counts): List<String> = listOfNotNull(
        c.servicesOverdue.takeIf { it > 0 }?.let { "$it سرویس عقب افتاده" },
        c.servicesDueSoon.takeIf { it > 0 }?.let { "$it سرویس نزدیک به موعد" },
        c.documentsExpired.takeIf { it > 0 }?.let { "$it مدرک منقضی‌شده" },
        c.documentsExpiringSoon.takeIf { it > 0 }?.let { "$it مدرک تا $DOCUMENT_NOTIFY_DAYS روز آینده منقضی می‌شود" },
        c.fuelAnomalies.takeIf { it > 0 }?.let { "مصرف سوخت $it ماشین غیرعادی" },
        c.tankLowStock?.let { "موجودی مخزن گازوئیل کم است: ${it.toLong()} لیتر" }
    )
}
