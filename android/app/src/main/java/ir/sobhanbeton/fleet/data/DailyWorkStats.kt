package ir.sobhanbeton.fleet.data

/** Totals of daily work over a period, per vehicle or per driver. Pure, unit tested. */
object DailyWorkStats {

    data class Totals(
        val key: String,
        val label: String,
        val days: Int,
        val trips: Int,
        val concreteM3: Double,
        val mineTrips: Int,
        val debrisTrips: Int
    )

    fun byVehicle(records: List<DailyWorkEntity>): List<Totals> =
        records.groupBy { it.vehicleId }.map { (id, list) -> totals(id, list.first().vehiclePlate, list) }
            .sortedWith(compareByDescending<Totals> { it.concreteM3 }.thenByDescending { it.trips + it.mineTrips + it.debrisTrips })

    /** Records without a driver are grouped under an empty key. */
    fun byDriver(records: List<DailyWorkEntity>): List<Totals> =
        records.groupBy { it.driverId.orEmpty() }.map { (id, list) -> totals(id, list.first().driverName.orEmpty(), list) }
            .sortedWith(compareByDescending<Totals> { it.concreteM3 }.thenByDescending { it.trips + it.mineTrips + it.debrisTrips })

    private fun totals(key: String, label: String, list: List<DailyWorkEntity>) = Totals(
        key = key,
        label = label,
        days = list.map { it.dayKey }.distinct().size,
        trips = list.sumOf { it.trips ?: 0 },
        concreteM3 = list.sumOf { it.concreteM3 ?: 0.0 },
        mineTrips = list.sumOf { it.mineTrips ?: 0 },
        debrisTrips = list.sumOf { it.debrisTrips ?: 0 }
    )

    /** Jalali yyyymmdd for [millis] — the day part of [DailyWorkEntity.id]. */
    fun dayKey(millis: Long): Int {
        val j = ir.sobhanbeton.fleet.util.JalaliCalendar.toJalali(millis)
        return j.year * 10_000 + j.month * 100 + j.day
    }
}
