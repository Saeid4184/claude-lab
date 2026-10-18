package ir.sobhanbeton.fleet.data

/**
 * Diesel tank arithmetic and the factory's fuelling priority. Pure (no Android/Firebase), unit
 * tested.
 *
 * Stock = latest physical reading + deliveries since − fuel given from the tank since (fleet
 * fuel logs with source INTERNAL_TANK, plus non-fleet withdrawals). Every reading is also
 * checked against what the records predicted since the reading before it: the difference is
 * the discrepancy (negative = less fuel in the tank than recorded — leak, unrecorded or
 * unauthorised withdrawal, or a measuring error).
 */
object TankStock {

    data class Status(
        /** Null until the first physical reading — there's no starting point before that. */
        val stock: Double?,
        val lastReading: TankReadingEntity?,
        val deliveredSince: Double,
        val usedSince: Double
    )

    data class ReadingCheck(val reading: TankReadingEntity, val expected: Double?, val discrepancy: Double?)

    /** One line of the tank's movement history (+ in, − out, or a measurement). */
    data class Movement(val date: Long, val kind: Kind, val liters: Double, val label: String, val refId: String) {
        enum class Kind { DELIVERY, VEHICLE_FUEL, OTHER_WITHDRAWAL, READING }
    }

    private fun tankFuel(fuelLogs: List<FuelLogEntity>) = fuelLogs.filter { it.sourceEnum() == FuelSource.INTERNAL_TANK }

    fun status(
        deliveries: List<TankDeliveryEntity>,
        withdrawals: List<TankWithdrawalEntity>,
        readings: List<TankReadingEntity>,
        fuelLogs: List<FuelLogEntity>,
        now: Long = System.currentTimeMillis()
    ): Status {
        val last = readings.filter { it.date <= now }.maxWithOrNull(compareBy({ it.date }, { it.createdAt }))
            ?: return Status(null, null, 0.0, 0.0)
        val delivered = deliveries.filter { it.date > last.date && it.date <= now }.sumOf { it.liters }
        val used = tankFuel(fuelLogs).filter { it.timestamp > last.date && it.timestamp <= now }.sumOf { it.liters } +
            withdrawals.filter { it.date > last.date && it.date <= now }.sumOf { it.liters }
        return Status(last.liters + delivered - used, last, delivered, used)
    }

    /** Each reading with what the records said should have been there (null for the first). */
    fun checks(
        deliveries: List<TankDeliveryEntity>,
        withdrawals: List<TankWithdrawalEntity>,
        readings: List<TankReadingEntity>,
        fuelLogs: List<FuelLogEntity>
    ): List<ReadingCheck> {
        val sorted = readings.sortedWith(compareBy({ it.date }, { it.createdAt }))
        return sorted.mapIndexed { i, r ->
            if (i == 0) return@mapIndexed ReadingCheck(r, null, null)
            val expected = status(deliveries, withdrawals, sorted.subList(0, i), fuelLogs, now = r.date).stock
            ReadingCheck(r, expected, expected?.let { r.liters - it })
        }.reversed()
    }

    fun movements(
        deliveries: List<TankDeliveryEntity>,
        withdrawals: List<TankWithdrawalEntity>,
        readings: List<TankReadingEntity>,
        fuelLogs: List<FuelLogEntity>
    ): List<Movement> = (
        deliveries.map { Movement(it.date, Movement.Kind.DELIVERY, it.liters, it.reference ?: it.supplier ?: "", it.id) } +
            tankFuel(fuelLogs).map { Movement(it.timestamp, Movement.Kind.VEHICLE_FUEL, it.liters, it.vehiclePlate, it.id) } +
            withdrawals.map { Movement(it.date, Movement.Kind.OTHER_WITHDRAWAL, it.liters, it.consumer, it.id) } +
            readings.map { Movement(it.date, Movement.Kind.READING, it.liters, it.notes.orEmpty(), it.id) }
        ).sortedByDescending { it.date }

    // ---------------------------------------------------------------- fuelling priority

    /**
     * The factory's order for using the tank: loaders → pumps → dump trailers → dump trucks →
     * mixers. Lower number = higher priority. Dump trailers and dump trucks share a category, so
     * the group label ("…تریلی…") tells them apart.
     */
    fun priority(vehicle: VehicleEntity): Int = when (vehicle.categoryEnum()) {
        VehicleCategory.LOADER -> 1
        VehicleCategory.CONCRETE_PUMP -> 2
        VehicleCategory.DUMP_TRUCK -> if (vehicle.groupLabel.orEmpty().let { it.contains("تریلی") || it.contains("تریلر") }) 3 else 4
        VehicleCategory.MIXER -> 5
        VehicleCategory.LOGISTICS -> 6
    }

    /** Mixers (and light/logistics vehicles, which aren't on the priority list) may only take
     *  tank fuel with the machinery manager's approval. */
    fun needsManagerApproval(vehicle: VehicleEntity): Boolean = priority(vehicle) >= 5

    /** When stock is below the warning level, anyone after the pumps is reminded of the order. */
    fun lowStockWarning(vehicle: VehicleEntity, stock: Double?, config: TankConfigEntity): Boolean =
        stock != null && stock < config.lowLevelLiters && priority(vehicle) > 2
}
