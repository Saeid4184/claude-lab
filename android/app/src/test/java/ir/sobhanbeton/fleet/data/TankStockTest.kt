package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TankStockTest {

    private fun reading(id: String, date: Long, liters: Double) = TankReadingEntity(id = id, date = date, liters = liters)
    private fun delivery(date: Long, liters: Double) = TankDeliveryEntity(id = "d$date", date = date, liters = liters)
    private fun fuel(date: Long, liters: Double, source: FuelSource = FuelSource.INTERNAL_TANK) =
        FuelLogEntity(id = "f$date", timestamp = date, liters = liters, source = source.name)

    @Test
    fun `no stock before the first reading`() {
        assertNull(TankStock.status(emptyList(), emptyList(), emptyList(), listOf(fuel(1, 10.0)), now = 5).stock)
    }

    @Test
    fun `stock is last reading plus deliveries minus tank fuel and other withdrawals since`() {
        val s = TankStock.status(
            deliveries = listOf(delivery(5, 9_000.0), delivery(1, 999.0)),   // the earlier one is before the reading
            withdrawals = listOf(TankWithdrawalEntity(id = "w", date = 6, liters = 100.0, consumer = "ژنراتور")),
            readings = listOf(reading("r", 2, 1_000.0)),
            fuelLogs = listOf(fuel(3, 200.0), fuel(7, 300.0), fuel(8, 50.0, FuelSource.STATION)), // station fuel ignored
            now = 10
        )
        assertEquals(1_000.0 + 9_000.0 - 200.0 - 300.0 - 100.0, s.stock!!, 0.001)
    }

    @Test
    fun `discrepancy compares each reading with what records predicted`() {
        val checks = TankStock.checks(
            deliveries = listOf(delivery(3, 5_000.0)),
            withdrawals = emptyList(),
            readings = listOf(reading("a", 1, 2_000.0), reading("b", 10, 6_400.0)),
            fuelLogs = listOf(fuel(5, 500.0))
        )
        val latest = checks.first()
        assertEquals("b", latest.reading.id)
        assertEquals(6_500.0, latest.expected!!, 0.001)
        assertEquals(-100.0, latest.discrepancy!!, 0.001)
        assertNull(checks.last().discrepancy)
    }

    @Test
    fun `priority order and manager approval`() {
        fun v(c: VehicleCategory, group: String? = null) = VehicleEntity(id = "x", category = c.name, groupLabel = group)
        assertEquals(1, TankStock.priority(v(VehicleCategory.LOADER)))
        assertEquals(2, TankStock.priority(v(VehicleCategory.CONCRETE_PUMP)))
        assertEquals(3, TankStock.priority(v(VehicleCategory.DUMP_TRUCK, "کمپرسی تریلی - فاو")))
        assertEquals(4, TankStock.priority(v(VehicleCategory.DUMP_TRUCK, "کمپرسی - آمیکو")))
        assertTrue(TankStock.needsManagerApproval(v(VehicleCategory.MIXER)))
        assertTrue(TankStock.needsManagerApproval(v(VehicleCategory.LOGISTICS)))
        assertFalse(TankStock.needsManagerApproval(v(VehicleCategory.DUMP_TRUCK)))

        val config = TankConfigEntity(lowLevelLiters = 2_000.0)
        assertTrue(TankStock.lowStockWarning(v(VehicleCategory.DUMP_TRUCK), 1_500.0, config))
        assertFalse(TankStock.lowStockWarning(v(VehicleCategory.CONCRETE_PUMP), 1_500.0, config))
        assertFalse(TankStock.lowStockWarning(v(VehicleCategory.MIXER), 5_000.0, config))
    }
}
