package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectionCatalogTest {

    /** Every checklist item must be markable: on the diagram, or in the non-spatial chips. */
    @Test
    fun `diagram zones plus non-spatial parts cover exactly the checklist`() {
        for (category in VehicleCategory.values()) {
            val zones = InspectionCatalog.zonesFor(category).map { it.name }.toSet()
            val markable = zones + InspectionCatalog.nonSpatialParts
            assertEquals("$category", InspectionCatalog.partsFor(category).toSet(), markable)
        }
    }
}
