package ir.sobhanbeton.fleet.ui.common

import androidx.annotation.ColorRes
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.DriverStatus
import ir.sobhanbeton.fleet.data.ExpiryAlerts
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.data.VehicleStatus
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.toPersianDigitsInString

/** Display helpers shared by the fleet screens, so every screen words dates, expiry countdowns
 *  and statuses the same way. */
object FleetFormat {

    const val EMPTY = "—"

    fun date(millis: Long?): String = millis?.let { JalaliCalendar.formatDate(it) } ?: EMPTY

    fun text(value: String?): String = value?.takeIf { it.isNotBlank() }?.toPersianDigitsInString() ?: EMPTY

    fun number(value: Number?): String = value?.toString()?.toPersianDigitsInString() ?: EMPTY

    /** "۲ روز مانده" / "امروز منقضی می‌شود" / "۵ روز گذشته" */
    fun daysLeft(days: Int): String = when {
        days > 0 -> "$days روز مانده".toPersianDigitsInString()
        days == 0 -> "امروز منقضی می‌شود"
        else -> "${-days} روز از انقضا گذشته".toPersianDigitsInString()
    }

    /** Expiry date plus its countdown when it's within the warning window or past. */
    fun expiry(millis: Long?, now: Long = System.currentTimeMillis()): String {
        if (millis == null) return EMPTY
        val days = daysUntil(millis, now)
        return if (days <= ExpiryAlerts.WARNING_DAYS) "${date(millis)} (${daysLeft(days)})" else date(millis)
    }

    fun daysUntil(millis: Long, now: Long = System.currentTimeMillis()): Int =
        Math.round((ExpiryAlerts.startOfDay(millis) - ExpiryAlerts.startOfDay(now)) / 86_400_000.0).toInt()

    @ColorRes
    fun expiryColor(days: Int): Int = if (days < 0) R.color.danger_red else R.color.safety_amber

    @ColorRes
    fun statusColor(status: VehicleStatus): Int = when (status) {
        VehicleStatus.ACTIVE -> R.color.status_green
        VehicleStatus.IN_REPAIR -> R.color.safety_amber_dark
        VehicleStatus.OUT_OF_SERVICE -> R.color.danger_red
    }

    @ColorRes
    fun statusColor(status: DriverStatus): Int = when (status) {
        DriverStatus.ACTIVE -> R.color.status_green
        DriverStatus.SUSPENDED -> R.color.safety_amber_dark
        DriverStatus.LEFT -> R.color.concrete_300
    }

    @ColorRes
    fun categoryAccent(category: VehicleCategory): Int = when (category) {
        VehicleCategory.MIXER -> R.color.accent_machinery
        VehicleCategory.CONCRETE_PUMP -> R.color.accent_inspection
        VehicleCategory.DUMP_TRUCK -> R.color.accent_personnel
        VehicleCategory.LOADER -> R.color.accent_report
        VehicleCategory.LOGISTICS -> R.color.accent_item_log
    }

    @ColorRes
    fun categoryAccentBg(category: VehicleCategory): Int = when (category) {
        VehicleCategory.MIXER -> R.color.accent_machinery_bg
        VehicleCategory.CONCRETE_PUMP -> R.color.accent_inspection_bg
        VehicleCategory.DUMP_TRUCK -> R.color.accent_personnel_bg
        VehicleCategory.LOADER -> R.color.accent_report_bg
        VehicleCategory.LOGISTICS -> R.color.accent_item_log_bg
    }
}
