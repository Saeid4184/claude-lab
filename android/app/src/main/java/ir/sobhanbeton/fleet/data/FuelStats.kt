package ir.sobhanbeton.fleet.data

import java.util.concurrent.TimeUnit

/**
 * Fuel arithmetic. Fuel is given in arbitrary amounts (tanks aren't topped up to full), so a
 * single fill says nothing about consumption. Over a period, though, the fuel given between the
 * first and the last meter reading ≈ the fuel burned over that distance/those hours — the
 * unknown tank level at each end is at most one tank, which averages out over a few weeks.
 * Pure (no Android/Firebase) so it's unit tested directly.
 */
object FuelStats {

    /** Consumption over a period: litres per 100 km for KM vehicles, litres per hour for HOURS. */
    data class Consumption(val rate: Double, val distance: Long, val liters: Double, val meterType: MeterType)

    data class VehicleSummary(
        val vehicleId: String,
        val plate: String,
        val fills: Int,
        val liters: Double,
        val consumption: Consumption?
    )

    data class Anomaly(val vehicleId: String, val plate: String, val recent: Consumption, val baseline: Consumption) {
        val increasePercent: Int get() = Math.round((recent.rate / baseline.rate - 1) * 100).toInt()
    }

    const val RECENT_DAYS = 30
    const val BASELINE_DAYS = 90
    /** Recent consumption this much above the vehicle's own baseline is flagged. */
    const val ANOMALY_THRESHOLD = 1.20

    /** [logs] = one vehicle's fuel logs (any order). Null until there are two meter readings
     *  with the meter actually moving between them. */
    fun consumption(logs: List<FuelLogEntity>, meterType: MeterType): Consumption? {
        val sorted = logs.sortedBy { it.timestamp }
        val readings = sorted.filter { it.meterReading != null }
        if (readings.size < 2) return null
        val first = readings.first()
        val last = readings.last()
        val distance = last.meterReading!! - first.meterReading!!
        if (distance <= 0) return null
        // Fuel given from the first reading up to (not including) the last one is what was
        // burned to cover that distance; the last fill's litres are for what comes after it.
        val liters = sorted.filter { it.timestamp >= first.timestamp && it.timestamp < last.timestamp }.sumOf { it.liters }
        if (liters <= 0.0) return null
        val rate = if (meterType == MeterType.KM) liters / distance * 100.0 else liters / distance
        return Consumption(rate, distance, liters, meterType)
    }

    /** Per-vehicle totals for [logs] (already limited to the period of interest). */
    fun summarize(logs: List<FuelLogEntity>, vehiclesById: Map<String, VehicleEntity>): List<VehicleSummary> =
        logs.groupBy { it.vehicleId }.map { (vehicleId, list) ->
            val vehicle = vehiclesById[vehicleId]
            VehicleSummary(
                vehicleId = vehicleId,
                plate = vehicle?.plate ?: list.first().vehiclePlate,
                fills = list.size,
                liters = list.sumOf { it.liters },
                consumption = consumption(list, vehicle?.meterTypeEnum() ?: MeterType.KM)
            )
        }.sortedByDescending { it.liters }

    /**
     * Vehicles whose consumption over the last [RECENT_DAYS] is more than [ANOMALY_THRESHOLD]×
     * their own consumption over the [BASELINE_DAYS] before that. Comparing each vehicle with
     * itself (not with the fleet) keeps old trucks or pumps on long jobs from being flagged just
     * for being what they are.
     */
    fun anomalies(logs: List<FuelLogEntity>, vehicles: List<VehicleEntity>, now: Long = System.currentTimeMillis()): List<Anomaly> {
        val recentFrom = now - TimeUnit.DAYS.toMillis(RECENT_DAYS.toLong())
        val baselineFrom = recentFrom - TimeUnit.DAYS.toMillis(BASELINE_DAYS.toLong())
        val byVehicle = logs.groupBy { it.vehicleId }
        return vehicles.mapNotNull { v ->
            val list = byVehicle[v.id] ?: return@mapNotNull null
            val type = v.meterTypeEnum()
            val recent = consumption(list.filter { it.timestamp in recentFrom..now }, type) ?: return@mapNotNull null
            val baseline = consumption(list.filter { it.timestamp in baselineFrom until recentFrom }, type) ?: return@mapNotNull null
            if (recent.rate > baseline.rate * ANOMALY_THRESHOLD) Anomaly(v.id, v.plate, recent, baseline) else null
        }.sortedByDescending { it.increasePercent }
    }

    /** The latest meter reading strictly before [before] (ignoring [excludeId], the log being
     *  edited) — to warn when a newly typed reading goes backwards. */
    fun previousReading(logs: List<FuelLogEntity>, before: Long, excludeId: String? = null): FuelLogEntity? =
        logs.filter { it.id != excludeId && it.meterReading != null && it.timestamp <= before }
            .maxByOrNull { it.timestamp }
}
