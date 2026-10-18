package ir.sobhanbeton.fleet.ui.reports

import ir.sobhanbeton.fleet.data.FleetReport
import ir.sobhanbeton.fleet.data.MeterType
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.XlsxWriter
import java.util.Locale

/**
 * The manager's "گزارش ماهانهٔ مدیریت" workbook: a one-page summary first, then one sheet per
 * topic. Numbers are written as plain Latin digits so Excel treats them as numbers (see
 * XlsxWriter). No money anywhere — costs live only in the expert's private notes.
 */
object MonthlyReportExporter {

    fun sheets(r: FleetReport): List<XlsxWriter.Sheet> = listOf(
        summary(r),
        XlsxWriter.Sheet(
            "آماده‌به‌کاری",
            listOf("پلاک", "توقف (ساعت)", "آماده‌به‌کاری (٪)"),
            r.downtime.map { listOf(it.plate, hours(it.downtimeMillis), percent(it.availability)) }
        ),
        XlsxWriter.Sheet(
            "کارکرد ماشین‌ها",
            listOf("پلاک", "روزهای کار", "سرویس", "متراژ بتن (م³)", "سرویس معدن", "سرویس نخاله"),
            r.workByVehicle.map { listOf(it.label, it.days.toString(), it.trips.toString(), decimal(it.concreteM3), it.mineTrips.toString(), it.debrisTrips.toString()) }
        ),
        XlsxWriter.Sheet(
            "کارکرد رانندگان",
            listOf("راننده", "روزهای کار", "سرویس", "متراژ بتن (م³)", "سرویس معدن", "سرویس نخاله"),
            r.workByDriver.map { listOf(it.label, it.days.toString(), it.trips.toString(), decimal(it.concreteM3), it.mineTrips.toString(), it.debrisTrips.toString()) }
        ),
        XlsxWriter.Sheet(
            "سوخت",
            listOf("پلاک", "تعداد سوخت‌گیری", "جمع لیتر", "میانگین مصرف", "واحد"),
            r.fuelByVehicle.map { s ->
                val c = s.consumption
                listOf(s.plate, s.fills.toString(), decimal(s.liters), c?.let { decimal(it.rate) }.orEmpty(),
                    c?.let { if (it.meterType == MeterType.KM) "لیتر در ۱۰۰ کیلومتر" else "لیتر در ساعت" }.orEmpty())
            }
        ),
        XlsxWriter.Sheet(
            "سرویس‌های عقب‌افتاده",
            listOf("پلاک", "سرویس", "آخرین انجام", "کارکرد گذشته از موعد", "روز گذشته از موعد"),
            r.servicesOverdue.map {
                listOf(it.vehicle.plate, it.plan.name, it.last?.let { l -> JalaliCalendar.formatDate(l.doneAt) }.orEmpty(),
                    it.meterLeft?.takeIf { m -> m < 0 }?.let { m -> (-m).toString() }.orEmpty(),
                    it.daysLeft?.takeIf { d -> d < 0 }?.let { d -> (-d).toString() }.orEmpty())
            }
        ),
        XlsxWriter.Sheet(
            "مدارک",
            listOf("ماشین/راننده", "مدرک", "تاریخ انقضا", "روز مانده"),
            r.documentAlerts.map { listOf(it.subjectLabel, it.documentLabel, JalaliCalendar.formatDate(it.expiresAt), it.daysLeft.toString()) }
        ),
        XlsxWriter.Sheet(
            "حوادث و تخلفات",
            listOf("تاریخ", "نوع", "پلاک", "راننده", "موضوع", "خلاصه"),
            r.incidents.map { listOf(JalaliCalendar.formatDate(it.occurredAt), it.typeEnum().displayName, it.vehiclePlate.orEmpty(), it.driverName.orEmpty(), it.subject, it.summaryText) }
        ),
        XlsxWriter.Sheet(
            "پرتکرارترین ایرادها",
            listOf("قطعه", "تعداد دفعات در بازدیدها"),
            r.topDefectParts.map { (part, count) -> listOf(part, count.toString()) }
        )
    )

    private fun summary(r: FleetReport): XlsxWriter.Sheet {
        val rows = mutableListOf(
            listOf("بازه", "${JalaliCalendar.formatDate(r.from)} تا ${JalaliCalendar.formatDate(r.to)}"),
            listOf("ماشین‌های در سرویس", r.vehiclesInService.toString()),
            listOf("آماده‌به‌کاری ناوگان (٪)", percent(r.availability)),
            listOf("ماشین‌های متوقف (الان)", r.stoppedNow.toString()),
            listOf("خرابی‌های ثبت‌شده در بازه", r.repairsOpened.toString()),
            listOf("تعمیرات تمام‌شده در بازه", r.repairsClosed.toString()),
            listOf("تعمیرات باز (الان)", r.repairsOpenNow.toString()),
            listOf("سرویس میکسرها", r.work.mixerTrips.toString()),
            listOf("بتن حمل‌شده با میکسر (م³)", decimal(r.work.mixerM3)),
            listOf("بتن پمپاژشده (م³)", decimal(r.work.pumpM3)),
            listOf("سرویس بار از معدن", r.work.mineTrips.toString()),
            listOf("سرویس نخاله", r.work.debrisTrips.toString()),
            listOf("سرویس لودر و تدارکات", r.work.otherTrips.toString()),
            listOf("جمع سوخت (لیتر)", decimal(r.fuelLiters)),
            listOf("ماشین‌های با مصرف سوخت غیرعادی", r.fuelAnomalies.size.toString()),
            listOf("سرویس‌های دوره‌ای انجام‌شده", r.servicesDoneInPeriod.toString()),
            listOf("سرویس‌های عقب‌افتاده (الان)", r.servicesOverdue.size.toString()),
            listOf("مدارک منقضی یا تا ۳۰ روز (الان)", r.documentAlerts.size.toString()),
            listOf("بازدیدهای هفتگی ثبت‌شده", r.inspectionsInPeriod.toString()),
            listOf("ایرادهای باز (الان)", r.openDefects.toString())
        )
        r.incidentsByType.forEach { (type, count) -> rows += listOf(type.displayName, count.toString()) }
        return XlsxWriter.Sheet("خلاصه", listOf("شاخص", "مقدار"), rows)
    }

    fun percent(share: Double): String = String.format(Locale.US, "%.1f", share * 100)
    fun hours(millis: Long): String = (millis / 3_600_000L).toString()
    fun decimal(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.1f", value)
}
