package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyWorkTest {

    @Test
    fun `each vehicle type reports its own metrics, never km or hours`() {
        assertEquals(listOf(WorkMetrics.Field.TRIPS, WorkMetrics.Field.CONCRETE_M3), WorkMetrics.fieldsFor(VehicleCategory.MIXER))
        assertEquals(listOf(WorkMetrics.Field.MINE_TRIPS, WorkMetrics.Field.DEBRIS_TRIPS), WorkMetrics.fieldsFor(VehicleCategory.DUMP_TRUCK))
        assertEquals(listOf(WorkMetrics.Field.CONCRETE_M3), WorkMetrics.fieldsFor(VehicleCategory.CONCRETE_PUMP))
    }

    @Test
    fun `totals per vehicle and per driver`() {
        fun rec(v: String, d: String?, day: Int, trips: Int? = null, m3: Double? = null, mine: Int? = null, debris: Int? = null) =
            DailyWorkEntity(id = "${v}_$day", vehicleId = v, vehiclePlate = v, driverId = d, driverName = d, dayKey = day, trips = trips, concreteM3 = m3, mineTrips = mine, debrisTrips = debris)
        val records = listOf(
            rec("mixer", "a", 1, trips = 8, m3 = 64.0),
            rec("mixer", "b", 2, trips = 6, m3 = 48.0),
            rec("pump", "a", 1, m3 = 120.0),
            rec("dump", null, 1, mine = 5, debris = 2)
        )
        val byVehicle = DailyWorkStats.byVehicle(records).associateBy { it.key }
        assertEquals(14, byVehicle.getValue("mixer").trips)
        assertEquals(112.0, byVehicle.getValue("mixer").concreteM3, 0.001)
        assertEquals(2, byVehicle.getValue("mixer").days)
        assertEquals(5, byVehicle.getValue("dump").mineTrips)

        val byDriver = DailyWorkStats.byDriver(records).associateBy { it.key }
        assertEquals(184.0, byDriver.getValue("a").concreteM3, 0.001)
        assertEquals(1, byDriver.getValue("a").days) // same day on two vehicles counts once
        assertTrue(byDriver.containsKey("")) // unknown driver grouped together
    }

    @Test
    fun `one record id per vehicle per day`() {
        assertEquals("v1_14050725", DailyWorkEntity.idFor("v1", 14050725))
    }

    @Test
    fun `incident summary reads naturally and skips empty parts`() {
        val s = IncidentCatalog.buildSummary(IncidentType.ACCIDENT, "۱۴۰۵/۰۷/۲۵", "واژگونی", "12345", "رانندهٔ الف", "خسارت جزئی", null, null)
        assertTrue(s.contains("ماشین 12345 (راننده «رانندهٔ الف»)"))
        assertTrue(s.contains("دچار «واژگونی» شد؛ نتیجه: خسارت جزئی."))
        assertFalse(s.contains("اقدام"))
        val fine = IncidentCatalog.buildSummary(IncidentType.FINE, "x", "اضافه‌بار", null, "ب", null, null, "تذکر کتبی")
        assertTrue(fine.contains("راننده «ب» به دلیل «اضافه‌بار» جریمه شد. اقدام: تذکر کتبی."))
    }
}
