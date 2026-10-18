package ir.sobhanbeton.fleet.data

/**
 * Everything the manager's dashboard and monthly report show for one period, computed from the
 * records the app already keeps — no extra tables. Pure (no Android/Firebase), unit tested.
 *
 * "Now" figures (open repairs, overdue services, expiring documents, open defects) describe the
 * moment the report is made; the rest cover [from]..[to].
 */
data class FleetReport(
    val from: Long,
    val to: Long,
    val vehiclesInService: Int,
    /** Share of vehicle-time in the period not lost to STOPPING repairs, 0..1. */
    val availability: Double,
    val downtime: List<VehicleDowntime>,
    val repairsOpened: Int,
    val repairsClosed: Int,
    val repairsOpenNow: Int,
    val stoppedNow: Int,
    val fuelLiters: Double,
    val fuelByVehicle: List<FuelStats.VehicleSummary>,
    val fuelAnomalies: List<FuelStats.Anomaly>,
    val work: WorkTotals,
    val workByVehicle: List<DailyWorkStats.Totals>,
    val workByDriver: List<DailyWorkStats.Totals>,
    val servicesOverdue: List<ServiceSchedule.Item>,
    val servicesDueSoon: Int,
    val servicesDoneInPeriod: Int,
    val documentAlerts: List<ExpiryAlerts.Item>,
    val incidentsByType: Map<IncidentType, Int>,
    val incidents: List<IncidentEntity>,
    val inspectionsInPeriod: Int,
    val openDefects: Int,
    val topDefectParts: List<Pair<String, Int>>
) {
    data class VehicleDowntime(val vehicleId: String, val plate: String, val downtimeMillis: Long, val availability: Double)

    data class WorkTotals(
        val mixerTrips: Int,
        val mixerM3: Double,
        val pumpM3: Double,
        val mineTrips: Int,
        val debrisTrips: Int,
        val otherTrips: Int
    )

    companion object {

        fun build(
            from: Long,
            to: Long,
            vehicles: List<VehicleEntity>,
            drivers: List<DriverEntity>,
            workOrders: List<WorkOrderEntity>,
            fuelLogs: List<FuelLogEntity>,
            dailyWork: List<DailyWorkEntity>,
            plans: List<ServicePlanEntity>,
            serviceLogs: List<ServiceLogEntity>,
            incidents: List<IncidentEntity>,
            inspections: List<InspectionEntity>,
            now: Long = System.currentTimeMillis()
        ): FleetReport {
            val inService = vehicles.filter { it.statusEnum() != VehicleStatus.OUT_OF_SERVICE }
            val periodEnd = minOf(to, now)
            val periodLength = (periodEnd - from).coerceAtLeast(1L)

            val downtime = inService.map { v ->
                val millis = stoppedMillis(workOrders.filter { it.vehicleId == v.id }, from, periodEnd, now)
                VehicleDowntime(v.id, v.plate, millis, 1.0 - millis.toDouble() / periodLength)
            }.sortedByDescending { it.downtimeMillis }
            val availability = if (downtime.isEmpty()) 1.0 else downtime.sumOf { it.availability } / downtime.size

            val periodWork = dailyWork.filter { it.date in from..to }
            val byCategory = periodWork.groupBy { it.categoryEnum() }
            val work = WorkTotals(
                mixerTrips = byCategory[VehicleCategory.MIXER].orEmpty().sumOf { it.trips ?: 0 },
                mixerM3 = byCategory[VehicleCategory.MIXER].orEmpty().sumOf { it.concreteM3 ?: 0.0 },
                pumpM3 = byCategory[VehicleCategory.CONCRETE_PUMP].orEmpty().sumOf { it.concreteM3 ?: 0.0 },
                mineTrips = periodWork.sumOf { it.mineTrips ?: 0 },
                debrisTrips = periodWork.sumOf { it.debrisTrips ?: 0 },
                otherTrips = (byCategory[VehicleCategory.LOADER].orEmpty() + byCategory[VehicleCategory.LOGISTICS].orEmpty()).sumOf { it.trips ?: 0 }
            )

            val periodFuel = fuelLogs.filter { it.timestamp in from..to }
            val services = ServiceSchedule.all(vehicles, plans, serviceLogs, now)
            val periodIncidents = incidents.filter { it.occurredAt in from..to }.sortedBy { it.occurredAt }
            val periodInspections = inspections.filter { it.timestamp in from..to }

            return FleetReport(
                from = from,
                to = to,
                vehiclesInService = inService.size,
                availability = availability,
                downtime = downtime,
                repairsOpened = workOrders.count { it.reportedAt in from..to },
                repairsClosed = workOrders.count { it.completedAt != null && it.completedAt in from..to },
                repairsOpenNow = workOrders.count { it.statusEnum() != WorkOrderStatus.DONE },
                stoppedNow = vehicles.count { it.statusEnum() == VehicleStatus.IN_REPAIR },
                fuelLiters = periodFuel.sumOf { it.liters },
                fuelByVehicle = FuelStats.summarize(periodFuel, vehicles.associateBy { it.id }),
                fuelAnomalies = FuelStats.anomalies(fuelLogs, vehicles, now),
                work = work,
                workByVehicle = DailyWorkStats.byVehicle(periodWork),
                workByDriver = DailyWorkStats.byDriver(periodWork).filter { it.key.isNotEmpty() },
                servicesOverdue = services.filter { it.state == ServiceSchedule.State.OVERDUE },
                servicesDueSoon = services.count { it.state == ServiceSchedule.State.DUE_SOON },
                servicesDoneInPeriod = serviceLogs.count { it.doneAt in from..to },
                documentAlerts = ExpiryAlerts.build(vehicles, drivers, now),
                incidentsByType = periodIncidents.groupingBy { it.typeEnum() }.eachCount(),
                incidents = periodIncidents,
                inspectionsInPeriod = periodInspections.size,
                openDefects = InspectionStats.openDefects(inspections).size,
                topDefectParts = InspectionJson.defectCountsByPart(periodInspections).entries
                    .sortedByDescending { it.value }.take(TOP_DEFECTS).map { it.key to it.value }
            )
        }

        /**
         * Time within [from]..[to] a vehicle was stopped by STOPPING repair orders. Overlapping
         * orders (two breakdowns at once) are merged so the same hours aren't counted twice; an
         * order still open counts until [now].
         */
        fun stoppedMillis(orders: List<WorkOrderEntity>, from: Long, to: Long, now: Long): Long {
            val intervals = orders
                .filter { it.severityEnum() == WorkOrderSeverity.STOPPING }
                .map { maxOf(it.reportedAt, from) to minOf(it.completedAt ?: now, to) }
                .filter { (start, end) -> end > start }
                .sortedBy { it.first }
            var total = 0L
            var curStart = -1L
            var curEnd = -1L
            for ((start, end) in intervals) {
                if (curEnd < 0 || start > curEnd) {
                    if (curEnd > curStart) total += curEnd - curStart
                    curStart = start
                    curEnd = end
                } else if (end > curEnd) {
                    curEnd = end
                }
            }
            if (curEnd > curStart) total += curEnd - curStart
            return total
        }

        const val TOP_DEFECTS = 5
    }
}
