package ir.sobhanbeton.fleet.data

import java.util.concurrent.TimeUnit

/**
 * When each periodic service is next due. Pure (no Android/Firebase), unit tested.
 *
 * Due = last time it was done + interval, by meter and/or by days — whichever comes first. The
 * meter side needs both a meter reading on the last service and the vehicle's current meter;
 * without them only the days side counts (and with no days interval the status is unknown).
 */
object ServiceSchedule {

    enum class State { OVERDUE, DUE_SOON, OK, NEVER_DONE, UNKNOWN }

    data class Item(
        val vehicle: VehicleEntity,
        val plan: ServicePlanEntity,
        val last: ServiceLogEntity?,
        val state: State,
        /** Meter units left until due (negative = past due by that much); null if not measurable. */
        val meterLeft: Long?,
        /** Days left until due (negative = overdue); null if not measurable. */
        val daysLeft: Int?
    )

    /** "Soon" = within this share of the interval… */
    const val SOON_FRACTION = 0.10
    /** …or within this many days. */
    const val SOON_DAYS = 7

    fun status(vehicle: VehicleEntity, plan: ServicePlanEntity, logs: List<ServiceLogEntity>, now: Long = System.currentTimeMillis()): Item {
        val last = logs.filter { it.vehicleId == vehicle.id && it.planId == plan.id }.maxByOrNull { it.doneAt }
            ?: return Item(vehicle, plan, null, State.NEVER_DONE, null, null)

        val meterLeft = plan.intervalMeter?.let { interval ->
            val lastMeter = last.meterReading ?: return@let null
            val current = vehicle.currentMeter ?: return@let null
            lastMeter + interval - current
        }
        val daysLeft = plan.intervalDays?.let { days ->
            val due = last.doneAt + TimeUnit.DAYS.toMillis(days.toLong())
            Math.floorDiv(due - now, TimeUnit.DAYS.toMillis(1)).toInt()
        }

        val state = when {
            meterLeft == null && daysLeft == null -> State.UNKNOWN
            (meterLeft != null && meterLeft < 0) || (daysLeft != null && daysLeft < 0) -> State.OVERDUE
            (meterLeft != null && meterLeft <= (plan.intervalMeter!! * SOON_FRACTION)) ||
                (daysLeft != null && daysLeft <= SOON_DAYS) -> State.DUE_SOON
            else -> State.OK
        }
        return Item(vehicle, plan, last, state, meterLeft, daysLeft)
    }

    /** Every (vehicle, plan) pair that applies — plans match vehicles by category; out-of-service
     *  vehicles are left out. Most urgent first. */
    fun all(vehicles: List<VehicleEntity>, plans: List<ServicePlanEntity>, logs: List<ServiceLogEntity>, now: Long = System.currentTimeMillis()): List<Item> =
        vehicles.filter { it.statusEnum() != VehicleStatus.OUT_OF_SERVICE }.flatMap { v ->
            plans.filter { it.category == v.category }.map { p -> status(v, p, logs, now) }
        }.sortedWith(compareBy<Item> { it.state.ordinal }.thenBy { it.vehicle.plate })

    /** Factory defaults offered once (the expert can edit/delete them). Typical intervals for
     *  ready-mix trucks and pumps — meant as a starting point, not manufacturer specs. */
    fun defaultPlans(): List<ServicePlanEntity> {
        fun plan(name: String, category: VehicleCategory, meter: Long?, days: Int?) =
            ServicePlanEntity(name = name, category = category.name, intervalMeter = meter, intervalDays = days)
        val heavyKm = listOf(VehicleCategory.MIXER, VehicleCategory.DUMP_TRUCK)
        val hours = listOf(VehicleCategory.CONCRETE_PUMP, VehicleCategory.LOADER)
        return heavyKm.flatMap { c ->
            listOf(
                plan("روغن موتور و فیلتر روغن", c, 10_000, 90),
                plan("فیلتر هوا", c, 20_000, 180),
                plan("فیلتر گازوئیل", c, 20_000, 180),
                plan("گریس‌کاری", c, 2_000, 14),
                plan("روغن گیربکس و دیفرانسیل", c, 60_000, 365)
            )
        } + plan("روغن هیدرولیک و گیربکس دیگ", VehicleCategory.MIXER, null, 365) +
            hours.flatMap { c ->
                listOf(
                    plan("روغن موتور و فیلتر روغن", c, 250, 90),
                    plan("گریس‌کاری", c, 50, 7),
                    plan("فیلتر هوا", c, 500, 180),
                    plan("فیلتر گازوئیل", c, 500, 180),
                    plan("روغن و فیلتر هیدرولیک", c, 2_000, 365)
                )
            } + listOf(
            plan("روغن موتور و فیلتر روغن", VehicleCategory.LOGISTICS, 5_000, 90),
            plan("فیلتر هوا", VehicleCategory.LOGISTICS, 15_000, 365)
        )
    }
}
