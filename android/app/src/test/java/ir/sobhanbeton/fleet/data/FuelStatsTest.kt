package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class FuelStatsTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_800_000_000_000L

    private fun log(id: String, daysAgo: Int, liters: Double, meter: Long? = null, vehicle: String = "v") =
        FuelLogEntity(id = id, vehicleId = vehicle, vehiclePlate = vehicle, timestamp = now - daysAgo * day, liters = liters, meterReading = meter)

    @Test
    fun `needs two readings with movement`() {
        assertNull(FuelStats.consumption(listOf(log("a", 3, 100.0, 1000)), MeterType.KM))
        assertNull(FuelStats.consumption(listOf(log("a", 3, 100.0, 1000), log("b", 1, 50.0, 1000)), MeterType.KM))
    }

    @Test
    fun `litres between first and last reading over distance, last fill excluded`() {
        val logs = listOf(
            log("a", 10, 100.0, 10_000),
            log("b", 7, 60.0),            // no reading, still counted
            log("c", 4, 40.0, 10_400),
            log("d", 1, 80.0, 10_800)     // last reading: its litres are for later
        )
        val c = FuelStats.consumption(logs, MeterType.KM)!!
        assertEquals(800L, c.distance)
        assertEquals(200.0, c.liters, 0.001)
        assertEquals(25.0, c.rate, 0.001) // 200 L / 800 km × 100
    }

    @Test
    fun `hours based vehicles report litres per hour`() {
        val logs = listOf(log("a", 5, 90.0, 1200), log("b", 1, 30.0, 1230))
        assertEquals(3.0, FuelStats.consumption(logs, MeterType.HOURS)!!.rate, 0.001)
    }

    @Test
    fun `anomaly when recent rate is over 20 percent above own baseline`() {
        val vehicle = VehicleEntity(id = "v", plate = "1", meterType = MeterType.KM.name)
        val baseline = listOf(log("b1", 100, 100.0, 0), log("b2", 60, 100.0, 1000)) // 10 L/100km
        val recentNormal = listOf(log("r1", 20, 50.0, 2000), log("r2", 5, 50.0, 2500))  // 10 L/100km
        val recentHigh = listOf(log("r1", 20, 70.0, 2000), log("r2", 5, 50.0, 2500))    // 14 L/100km

        assertTrue(FuelStats.anomalies(baseline + recentNormal, listOf(vehicle), now).isEmpty())
        val a = FuelStats.anomalies(baseline + recentHigh, listOf(vehicle), now).single()
        assertEquals(40, a.increasePercent)
    }

    @Test
    fun `summary groups per vehicle, biggest consumer first`() {
        val logs = listOf(log("a", 3, 50.0, vehicle = "x"), log("b", 2, 200.0, vehicle = "y"), log("c", 1, 25.0, vehicle = "x"))
        val s = FuelStats.summarize(logs, emptyMap())
        assertEquals(listOf("y", "x"), s.map { it.vehicleId })
        assertEquals(75.0, s[1].liters, 0.001)
        assertEquals(2, s[1].fills)
    }

    @Test
    fun `previous reading ignores the log being edited`() {
        val logs = listOf(log("a", 5, 10.0, 100), log("b", 2, 10.0, 200))
        assertEquals("a", FuelStats.previousReading(logs, now - 2 * day, excludeId = "b")!!.id)
    }
}
