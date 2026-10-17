package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ServiceScheduleTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_800_000_000_000L
    private val plan = ServicePlanEntity(id = "p", name = "روغن", category = VehicleCategory.MIXER.name, intervalMeter = 10_000, intervalDays = 90)

    private fun vehicle(meter: Long?) = VehicleEntity(id = "v", plate = "1", category = VehicleCategory.MIXER.name, currentMeter = meter)
    private fun log(daysAgo: Int, meter: Long?) = ServiceLogEntity(id = "l$daysAgo", vehicleId = "v", planId = "p", doneAt = now - daysAgo * day, meterReading = meter)

    @Test
    fun `never done`() {
        assertEquals(ServiceSchedule.State.NEVER_DONE, ServiceSchedule.status(vehicle(1000), plan, emptyList(), now).state)
    }

    @Test
    fun `overdue by meter even if days are fine`() {
        val item = ServiceSchedule.status(vehicle(61_000), plan, listOf(log(10, 50_000)), now)
        assertEquals(ServiceSchedule.State.OVERDUE, item.state)
        assertEquals(-1_000L, item.meterLeft)
        assertEquals(80, item.daysLeft)
    }

    @Test
    fun `overdue by days when meter unknown`() {
        val item = ServiceSchedule.status(vehicle(null), plan, listOf(log(100, 50_000)), now)
        assertEquals(ServiceSchedule.State.OVERDUE, item.state)
        assertNull(item.meterLeft)
    }

    @Test
    fun `due soon within 10 percent or 7 days, latest log counts`() {
        assertEquals(ServiceSchedule.State.DUE_SOON, ServiceSchedule.status(vehicle(59_500), plan, listOf(log(10, 50_000)), now).state)
        assertEquals(ServiceSchedule.State.DUE_SOON, ServiceSchedule.status(vehicle(51_000), plan, listOf(log(85, 50_000)), now).state)
        val ok = ServiceSchedule.status(vehicle(51_000), plan, listOf(log(200, 30_000), log(10, 50_000)), now)
        assertEquals(ServiceSchedule.State.OK, ok.state)
    }

    @Test
    fun `plans apply by category and skip out of service vehicles, urgent first`() {
        val pump = VehicleEntity(id = "x", plate = "2", category = VehicleCategory.CONCRETE_PUMP.name)
        val dead = VehicleEntity(id = "y", plate = "3", category = VehicleCategory.MIXER.name, status = VehicleStatus.OUT_OF_SERVICE.name)
        val items = ServiceSchedule.all(listOf(vehicle(61_000), pump, dead), listOf(plan), listOf(log(10, 50_000)), now)
        assertEquals(listOf("v"), items.map { it.vehicle.id })
    }

    @Test
    fun `default plans cover every category and have an interval`() {
        val plans = ServiceSchedule.defaultPlans()
        assertEquals(VehicleCategory.values().toSet(), plans.map { it.categoryEnum() }.toSet())
        assertTrue(plans.all { it.intervalMeter != null || it.intervalDays != null })
    }
}
