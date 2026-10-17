package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/** Robolectric only because InspectionJson uses Android's org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ir.sobhanbeton.fleet.ui.TestApp::class)
class InspectionStatsTest {

    private fun inspection(id: String, vehicle: String, at: Long, vararg parts: InspectionPartResult) =
        InspectionEntity(id = id, vehicleId = vehicle, vehiclePlate = vehicle, category = VehicleCategory.MIXER.name, timestamp = at, partsJson = InspectionJson.serialize(parts.toList()))

    @Test
    fun `only the latest inspection per vehicle counts, repaired parts are closed`() {
        val older = inspection("a", "v1", 1, InspectionPartResult("آینه چپ", PartStatus.BAD))
        val latest = inspection(
            "b", "v1", 2,
            InspectionPartResult("آینه چپ", PartStatus.OK),
            InspectionPartResult("سپر جلو", PartStatus.WARN),
            InspectionPartResult("کارواش", PartStatus.BAD, repairedAt = 3)
        )
        val defects = InspectionStats.openDefects(listOf(older, latest))
        assertEquals(listOf("سپر جلو"), defects.map { it.partName })
        assertEquals("b", defects.single().inspectionId)
    }

    @Test
    fun `recurrence keeps the first date a defect was seen`() {
        val previous = inspection("a", "v1", 100, InspectionPartResult("آینه چپ", PartStatus.BAD))
        val parts = InspectionStats.withRecurrence(
            listOf(InspectionPartResult("آینه چپ", PartStatus.WARN), InspectionPartResult("سپر جلو", PartStatus.BAD)),
            previous
        )
        assertEquals(100L, parts[0].recurringSinceTimestamp)
        assertEquals(null, parts[1].recurringSinceTimestamp)
    }

    @Test
    fun `week starts on saturday`() {
        val wednesday = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 14, 15, 0, 0) }.timeInMillis
        val start = Calendar.getInstance().apply { timeInMillis = InspectionStats.startOfWeek(wednesday) }
        assertEquals(Calendar.SATURDAY, start.get(Calendar.DAY_OF_WEEK))
        assertEquals(10, start.get(Calendar.DAY_OF_MONTH))
        assertTrue(start.timeInMillis <= wednesday)
    }
}
