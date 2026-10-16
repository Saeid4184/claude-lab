package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ExpiryAlertsTest {

    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 16, 15, 30, 0) }.timeInMillis
    private fun inDays(days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_MONTH, days)
        set(Calendar.HOUR_OF_DAY, 12)
    }.timeInMillis

    @Test
    fun `includes expired and within window, sorted most urgent first`() {
        val vehicles = listOf(
            VehicleEntity(id = "v1", plate = "1", thirdPartyInsuranceExpiry = inDays(10), technicalInspectionExpiry = inDays(-3)),
            VehicleEntity(id = "v2", plate = "2", bodyInsuranceExpiry = inDays(31))
        )
        val drivers = listOf(DriverEntity(id = "d1", name = "x", licenseExpiry = inDays(0)))

        val items = ExpiryAlerts.build(vehicles, drivers, now)

        assertEquals(listOf(-3, 0, 10), items.map { it.daysLeft })
        assertEquals(ExpiryAlerts.DOC_TECHNICAL, items[0].documentLabel)
        assertEquals(ExpiryAlerts.SubjectType.DRIVER, items[1].subjectType)
    }

    @Test
    fun `boundary is inclusive at the warning window`() {
        val vehicles = listOf(VehicleEntity(id = "v", plate = "1", thirdPartyInsuranceExpiry = inDays(ExpiryAlerts.WARNING_DAYS)))
        assertEquals(1, ExpiryAlerts.build(vehicles, emptyList(), now).size)
    }

    @Test
    fun `out of service vehicles and drivers who left are ignored`() {
        val vehicles = listOf(VehicleEntity(id = "v", plate = "1", status = VehicleStatus.OUT_OF_SERVICE.name, thirdPartyInsuranceExpiry = inDays(-1)))
        val drivers = listOf(DriverEntity(id = "d", name = "x", status = DriverStatus.LEFT.name, licenseExpiry = inDays(-1)))
        assertTrue(ExpiryAlerts.build(vehicles, drivers, now).isEmpty())
    }

    @Test
    fun `category classification from group labels`() {
        assertEquals(VehicleCategory.MIXER, VehicleCategory.classify("میکسر - آمیکو"))
        assertEquals(VehicleCategory.CONCRETE_PUMP, VehicleCategory.classify("پمپ سوار"))
        assertEquals(VehicleCategory.DUMP_TRUCK, VehicleCategory.classify("کمپرسی تریلی - فاو"))
        assertEquals(VehicleCategory.LOADER, VehicleCategory.classify("لودر کوماتسو"))
        assertEquals(VehicleCategory.LOGISTICS, VehicleCategory.classify("نیسان - تدارکات"))
        assertEquals(MeterType.HOURS, VehicleCategory.LOADER.defaultMeterType)
    }
}
