package ir.sobhanbeton.fleet.data

/** Rules for how repairs drive a vehicle's status, and downtime. Pure, unit tested. */
object WorkOrders {

    /**
     * The vehicle status implied by its [openOrders] (status != DONE): any open STOPPING order
     * puts it «در تعمیر»; once none is left, a vehicle that is «در تعمیر» goes back to «فعال».
     * «خارج از سرویس» is a deliberate decision by the expert and is never changed here.
     */
    fun vehicleStatusFor(current: VehicleStatus, openOrders: List<WorkOrderEntity>): VehicleStatus = when {
        current == VehicleStatus.OUT_OF_SERVICE -> current
        openOrders.any { it.severityEnum() == WorkOrderSeverity.STOPPING } -> VehicleStatus.IN_REPAIR
        current == VehicleStatus.IN_REPAIR -> VehicleStatus.ACTIVE
        else -> current
    }

    /** How long a STOPPING order kept the vehicle off work (so far, if still open); null for
     *  RUNNING orders, which don't stop it. */
    fun downtimeMillis(order: WorkOrderEntity, now: Long = System.currentTimeMillis()): Long? {
        if (order.severityEnum() != WorkOrderSeverity.STOPPING) return null
        val end = order.completedAt ?: now
        return (end - order.reportedAt).coerceAtLeast(0L)
    }
}
