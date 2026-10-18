package ir.sobhanbeton.fleet.notify

import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.ServiceLogEntity
import ir.sobhanbeton.fleet.data.ServicePlanEntity
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.data.VehicleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class DailyAlertsTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_800_000_000_000L

    @Test
    fun `nothing to report is empty`() {
        val c = DailyAlerts.count(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), now)
        assertTrue(c.isEmpty)
        assertTrue(DailyAlerts.lines(c).isEmpty())
    }

    @Test
    fun `counts overdue services and documents within the notify window only`() {
        val v = VehicleEntity(
            id = "v", plate = "1", category = VehicleCategory.MIXER.name, currentMeter = 70_000,
            thirdPartyInsuranceExpiry = now - 2 * day,   // expired
            technicalInspectionExpiry = now + 20 * day  // within 30 but not within 7: not notified
        )
        val d = DriverEntity(id = "d", name = "x", licenseExpiry = now + 3 * day)
        val plan = ServicePlanEntity(id = "p", category = VehicleCategory.MIXER.name, intervalMeter = 10_000)
        val log = ServiceLogEntity(id = "l", vehicleId = "v", planId = "p", doneAt = now - day, meterReading = 50_000)

        val c = DailyAlerts.count(listOf(v), listOf(d), listOf(plan), listOf(log), emptyList(), now)
        assertEquals(1, c.servicesOverdue)
        assertEquals(1, c.documentsExpired)
        assertEquals(1, c.documentsExpiringSoon)
        assertEquals(3, DailyAlerts.lines(c).size)
    }

    @Test
    fun `low tank stock is reported`() {
        val c = DailyAlerts.count(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), now, tankStock = 1_500.0, tankLowLevel = 2_000.0)
        assertEquals(1_500.0, c.tankLowStock!!, 0.001)
        assertEquals(1, DailyAlerts.lines(c).size)
        assertTrue(DailyAlerts.count(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), now, tankStock = 5_000.0, tankLowLevel = 2_000.0).isEmpty)
    }
}
