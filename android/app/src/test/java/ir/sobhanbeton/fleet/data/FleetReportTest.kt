package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Robolectric only because the report touches InspectionJson (Android's org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ir.sobhanbeton.fleet.ui.TestApp::class)
class FleetReportTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val from = 1_800_000_000_000L
    private val to = from + 10 * day
    private val now = to + day

    private fun order(vehicle: String, start: Long, end: Long?, stopping: Boolean = true) = WorkOrderEntity(
        id = "$vehicle$start", vehicleId = vehicle, reportedAt = start, completedAt = end,
        severity = (if (stopping) WorkOrderSeverity.STOPPING else WorkOrderSeverity.RUNNING).name,
        status = (if (end == null) WorkOrderStatus.OPEN else WorkOrderStatus.DONE).name
    )

    @Test
    fun `overlapping stopping orders are merged and clipped to the period`() {
        val orders = listOf(
            order("v", from - day, from + 2 * day),        // 2 days inside the period
            order("v", from + day, from + 3 * day),        // overlaps: extends to day 3
            order("v", from + 5 * day, from + 6 * day, stopping = false), // running: ignored
            order("v", from + 8 * day, null)               // open: counts until period end
        )
        assertEquals(3 * day + 2 * day, FleetReport.stoppedMillis(orders, from, to, now))
    }

    @Test
    fun `availability, work totals by vehicle type and repairs`() {
        val vehicles = listOf(
            VehicleEntity(id = "m", plate = "1", category = VehicleCategory.MIXER.name),
            VehicleEntity(id = "p", plate = "2", category = VehicleCategory.CONCRETE_PUMP.name),
            VehicleEntity(id = "x", plate = "3", category = VehicleCategory.MIXER.name, status = VehicleStatus.OUT_OF_SERVICE.name)
        )
        val work = listOf(
            DailyWorkEntity(id = "a", vehicleId = "m", category = VehicleCategory.MIXER.name, date = from + day, dayKey = 1, trips = 8, concreteM3 = 64.0, driverId = "d", driverName = "d"),
            DailyWorkEntity(id = "b", vehicleId = "p", category = VehicleCategory.CONCRETE_PUMP.name, date = from + day, dayKey = 1, concreteM3 = 120.0),
            DailyWorkEntity(id = "c", vehicleId = "m", category = VehicleCategory.MIXER.name, date = to + 5 * day, dayKey = 9, trips = 99)
        )
        val report = FleetReport.build(
            from, to, vehicles, emptyList(),
            workOrders = listOf(order("m", from, from + 5 * day)),
            fuelLogs = emptyList(), dailyWork = work, plans = emptyList(), serviceLogs = emptyList(),
            incidents = emptyList(), inspections = emptyList(), now = now
        )
        assertEquals(2, report.vehiclesInService)
        assertEquals(0.75, report.availability, 0.001) // mixer 50%, pump 100%
        assertEquals(8, report.work.mixerTrips)
        assertEquals(64.0, report.work.mixerM3, 0.001)
        assertEquals(120.0, report.work.pumpM3, 0.001)
        assertEquals(1, report.repairsOpened)
        assertEquals(1, report.repairsClosed)
        assertEquals(listOf("d"), report.workByDriver.map { it.key }) // records without a driver left out
    }
}
