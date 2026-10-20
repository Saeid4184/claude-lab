package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelPositionsTest {

    private fun codes(category: VehicleCategory, group: String? = null) =
        WheelPositions.positionsFor(VehicleEntity(id = "v", plate = "1", category = category.name, groupLabel = group)).map { it.code }

    @Test
    fun layoutsPerVehicleType() {
        assertEquals(listOf("F_L", "F_R", "R_L", "R_R", "SPARE"), codes(VehicleCategory.LOADER))
        assertEquals(2 + 2 * 4 + 1, codes(VehicleCategory.MIXER).size)
        assertEquals(2 + 2 * 4 + 1, codes(VehicleCategory.CONCRETE_PUMP).size)
        assertEquals(2 + 2 * 4 + 1, codes(VehicleCategory.DUMP_TRUCK, "کمپرسی").size)
        val trailer = codes(VehicleCategory.DUMP_TRUCK, "کمپرسی تریلی")
        assertEquals(2 + 3 * 4 + 1, trailer.size)
        assertTrue("A4_RO" in trailer)
        // every code is unique and has a Persian label
        VehicleCategory.values().forEach { c ->
            val list = codes(c)
            assertEquals(list.size, list.toSet().size)
            list.forEach { assertTrue(WheelPositions.label(it) != it) }
        }
    }

    @Test
    fun unknownCodeKeepsItsText() {
        assertEquals("X9", WheelPositions.label("X9"))
    }

    @Test
    fun lastChangeByWheelTakesTheLatestTyreChangeOnly() {
        val changes = listOf(
            PartChangeEntity(id = "a", vehicleId = "v", kind = "TIRE", date = 100, wheels = "F_L,F_R"),
            PartChangeEntity(id = "b", vehicleId = "v", kind = "TIRE", date = 300, wheels = "F_L"),
            PartChangeEntity(id = "c", vehicleId = "v", kind = "BATTERY", date = 500),
            PartChangeEntity(id = "d", vehicleId = "v", kind = "TIRE", date = 200, wheels = " A2_LO , ")
        )
        val last = WheelPositions.lastChangeByWheel(changes)
        assertEquals("b", last["F_L"]?.id)
        assertEquals("a", last["F_R"]?.id)
        assertEquals("d", last["A2_LO"]?.id)
        assertEquals(3, last.size)
    }
}
