package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkOrdersTest {

    private fun order(severity: WorkOrderSeverity, reportedAt: Long = 0, completedAt: Long? = null) =
        WorkOrderEntity(id = "o", severity = severity.name, reportedAt = reportedAt, completedAt = completedAt)

    @Test
    fun `stopping order puts vehicle in repair, none left brings it back`() {
        assertEquals(VehicleStatus.IN_REPAIR, WorkOrders.vehicleStatusFor(VehicleStatus.ACTIVE, listOf(order(WorkOrderSeverity.STOPPING))))
        assertEquals(VehicleStatus.ACTIVE, WorkOrders.vehicleStatusFor(VehicleStatus.IN_REPAIR, emptyList()))
        assertEquals(VehicleStatus.ACTIVE, WorkOrders.vehicleStatusFor(VehicleStatus.IN_REPAIR, listOf(order(WorkOrderSeverity.RUNNING))))
    }

    @Test
    fun `running order alone does not change an active vehicle`() {
        assertEquals(VehicleStatus.ACTIVE, WorkOrders.vehicleStatusFor(VehicleStatus.ACTIVE, listOf(order(WorkOrderSeverity.RUNNING))))
    }

    @Test
    fun `out of service is never changed automatically`() {
        assertEquals(VehicleStatus.OUT_OF_SERVICE, WorkOrders.vehicleStatusFor(VehicleStatus.OUT_OF_SERVICE, listOf(order(WorkOrderSeverity.STOPPING))))
        assertEquals(VehicleStatus.OUT_OF_SERVICE, WorkOrders.vehicleStatusFor(VehicleStatus.OUT_OF_SERVICE, emptyList()))
    }

    @Test
    fun `downtime only for stopping orders, open ones count until now`() {
        assertNull(WorkOrders.downtimeMillis(order(WorkOrderSeverity.RUNNING, 0, 100)))
        assertEquals(100L, WorkOrders.downtimeMillis(order(WorkOrderSeverity.STOPPING, 0, 100)))
        assertEquals(50L, WorkOrders.downtimeMillis(order(WorkOrderSeverity.STOPPING, 10), now = 60))
    }
}
