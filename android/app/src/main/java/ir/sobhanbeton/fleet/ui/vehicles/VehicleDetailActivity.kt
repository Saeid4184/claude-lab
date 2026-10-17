package ir.sobhanbeton.fleet.ui.vehicles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.AssignmentEntity
import ir.sobhanbeton.fleet.data.DriverStatus
import ir.sobhanbeton.fleet.data.ExpiryAlerts
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.FuelLogEntity
import ir.sobhanbeton.fleet.data.FuelStats
import ir.sobhanbeton.fleet.data.InspectionEntity
import ir.sobhanbeton.fleet.data.InspectionStats
import ir.sobhanbeton.fleet.data.PartStatus
import ir.sobhanbeton.fleet.data.WorkOrderEntity
import ir.sobhanbeton.fleet.data.WorkOrderStatus
import ir.sobhanbeton.fleet.data.MeterType
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.VehicleStatus
import ir.sobhanbeton.fleet.databinding.ActivityFleetDetailBinding
import ir.sobhanbeton.fleet.ui.common.DetailSections
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.promptText
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.JalaliDatePickerDialog
import ir.sobhanbeton.fleet.ui.drivers.DriverDetailActivity
import ir.sobhanbeton.fleet.ui.fuel.FuelFormActivity
import ir.sobhanbeton.fleet.ui.inspections.InspectionFormActivity
import ir.sobhanbeton.fleet.ui.repairs.WorkOrderDetailActivity
import ir.sobhanbeton.fleet.ui.repairs.WorkOrderFormActivity
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** "شناسنامهٔ ماشین": everything known about one vehicle, its driver history, and (expert
 *  only) the actions to change driver/status, edit or delete it. Later phases add its fuel,
 *  service history here; fuel, repairs and inspections are shown already. */
class VehicleDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetDetailBinding
    private lateinit var repository: FleetRepository
    private lateinit var sections: DetailSections
    private lateinit var vehicleId: String

    private var vehicle: VehicleEntity? = null
    private var history: List<AssignmentEntity> = emptyList()
    private var fuel: List<FuelLogEntity> = emptyList()
    private var repairs: List<WorkOrderEntity> = emptyList()
    private var inspections: List<InspectionEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicleId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityFleetDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sections = DetailSections(binding.container)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (Session.canEdit()) {
            binding.actionBar.visibility = View.VISIBLE
            binding.btnPrimaryAction.text = getString(R.string.action_change_driver)
            binding.btnSecondaryAction.text = getString(R.string.action_change_status)
            binding.btnPrimaryAction.setOnClickListener { chooseDriver() }
            binding.btnSecondaryAction.setOnClickListener { chooseStatus() }
        }

        repository.vehicle(vehicleId).observe(this) { v ->
            if (v == null) {
                // Deleted (here or on the other phone) — nothing left to show.
                if (vehicle != null) finish()
                return@observe
            }
            vehicle = v
            render()
        }
        repository.vehicleAssignments(vehicleId).observe(this) { list ->
            history = list
            render()
        }
        repository.vehicleFuel(vehicleId).observe(this) { list ->
            fuel = list
            render()
        }
        repository.vehicleWorkOrders(vehicleId).observe(this) { list ->
            repairs = list
            render()
        }
        repository.vehicleInspections(vehicleId).observe(this) { list ->
            inspections = list
            render()
        }
    }

    private fun render() {
        val v = vehicle ?: return
        supportActionBar?.title = v.plate.toPersianDigitsInString()
        val status = v.statusEnum()
        val meterType = v.meterTypeEnum()
        sections.clear()

        sections.section(getString(R.string.form_section_identity))
        sections.row(getString(R.string.field_plate), FleetFormat.text(v.plate))
        sections.row(getString(R.string.field_category), v.categoryEnum().displayName)
        sections.row(getString(R.string.field_group), FleetFormat.text(v.groupLabel))
        sections.row(getString(R.string.field_brand_model), FleetFormat.text(v.brandModel))
        sections.row(getString(R.string.field_year), FleetFormat.number(v.manufactureYear))
        sections.row(getString(R.string.field_chassis), FleetFormat.text(v.chassisNumber))
        sections.row(getString(R.string.field_engine), FleetFormat.text(v.engineNumber))

        sections.section(getString(R.string.detail_section_status))
        sections.row(getString(R.string.field_status), status.displayName, FleetFormat.statusColor(status))
        v.statusNote?.let { sections.row(getString(R.string.field_status_note), it) }
        v.statusChangedAt?.let { sections.row(getString(R.string.field_status_since), FleetFormat.date(it)) }
        sections.row(
            getString(R.string.field_current_driver),
            v.currentDriverName ?: getString(R.string.no_driver),
            onClick = v.currentDriverId?.let { id -> { DriverDetailActivity.start(this, id) } }
        )

        sections.section(getString(R.string.form_section_meter))
        sections.row(getString(R.string.field_meter_type), meterType.displayName)
        sections.row(
            getString(R.string.field_current_meter),
            v.currentMeter?.let { "${FleetFormat.number(it)} ${meterType.unit}" } ?: FleetFormat.EMPTY
        )
        v.meterUpdatedAt?.let { sections.row(getString(R.string.field_meter_updated), FleetFormat.date(it)) }

        sections.section(getString(R.string.form_section_documents))
        expiryRow(getString(R.string.field_third_party_expiry), v.thirdPartyInsuranceExpiry)
        expiryRow(getString(R.string.field_body_insurance_expiry), v.bodyInsuranceExpiry)
        expiryRow(getString(R.string.field_technical_expiry), v.technicalInspectionExpiry)

        renderFuel(meterType)
        renderRepairs()
        renderInspection()

        sections.section(getString(R.string.detail_section_driver_history))
        if (history.isEmpty()) {
            sections.note(getString(R.string.history_empty))
        } else {
            history.forEach { a ->
                val period = getString(
                    R.string.period_format,
                    FleetFormat.date(a.startAt),
                    a.endAt?.let { FleetFormat.date(it) } ?: getString(R.string.until_now)
                )
                sections.row(a.driverName, period, onClick = { DriverDetailActivity.start(this, a.driverId) })
            }
        }

        if (!v.notes.isNullOrBlank()) {
            sections.section(getString(R.string.form_section_other))
            sections.row(getString(R.string.field_notes), v.notes)
        }
    }

    /** Average consumption over the last [FUEL_AVERAGE_DAYS] days and the latest fills. */
    private fun renderFuel(meterType: MeterType) {
        sections.section(getString(R.string.home_fuel))
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(FUEL_AVERAGE_DAYS)
        val recent = fuel.filter { it.timestamp >= since }
        val consumption = FuelStats.consumption(recent, meterType)
        sections.row(
            getString(R.string.fuel_average_label, FUEL_AVERAGE_DAYS.toInt()).toPersianDigitsInString(),
            consumption?.let {
                getString(
                    if (meterType == MeterType.KM) R.string.consumption_per_100km else R.string.consumption_per_hour,
                    "%.1f".format(java.util.Locale.US, it.rate)
                ).toPersianDigitsInString()
            } ?: getString(R.string.fuel_no_consumption)
        )
        sections.row(
            getString(R.string.fuel_total_label, FUEL_AVERAGE_DAYS.toInt()).toPersianDigitsInString(),
            getString(R.string.liters_format, FuelFormActivity.formatLiters(recent.sumOf { it.liters })).toPersianDigitsInString()
        )
        if (fuel.isEmpty()) {
            sections.note(getString(R.string.fuel_none_for_vehicle))
            return
        }
        fuel.take(RECENT_FUEL_ROWS).forEach { log ->
            val detail = listOfNotNull(
                getString(R.string.liters_format, FuelFormActivity.formatLiters(log.liters)),
                log.meterReading?.let { "${FleetFormat.number(it)} ${meterType.unit}" },
                log.driverName
            ).joinToString(" · ").toPersianDigitsInString()
            sections.row(
                FleetFormat.date(log.timestamp),
                detail,
                onClick = if (Session.canEdit()) ({ FuelFormActivity.start(this, fuelId = log.id) }) else null
            )
        }
    }

    /** Open repairs, then the latest few finished ones. */
    private fun renderRepairs() {
        sections.section(getString(R.string.home_repairs))
        if (repairs.isEmpty()) {
            sections.note(getString(R.string.work_orders_none_for_vehicle))
            return
        }
        val (open, done) = repairs.partition { it.statusEnum() != WorkOrderStatus.DONE }
        (open + done.take(RECENT_REPAIR_ROWS)).forEach { o ->
            sections.row(
                FleetFormat.date(o.reportedAt),
                "${o.title} — ${o.statusEnum().displayName}".toPersianDigitsInString(),
                if (o.statusEnum() == WorkOrderStatus.DONE) null else R.color.safety_amber,
                onClick = { WorkOrderDetailActivity.start(this, o.id) }
            )
        }
    }

    /** Latest weekly inspection and what it left open. */
    private fun renderInspection() {
        sections.section(getString(R.string.home_inspections))
        val latest = inspections.maxByOrNull { it.timestamp }
        if (latest == null) {
            sections.note(getString(R.string.inspection_never_done))
            return
        }
        sections.row(
            getString(R.string.inspection_latest_label),
            getString(R.string.inspection_last_format, FleetFormat.date(latest.timestamp), latest.rejectedCount).toPersianDigitsInString(),
            onClick = if (Session.canEdit()) ({ InspectionFormActivity.start(this, vehicleId, latest.id) }) else null
        )
        InspectionStats.openDefects(listOf(latest)).forEach { d ->
            sections.row(
                d.partName,
                getString(R.string.defect_since, FleetFormat.date(d.since)).toPersianDigitsInString(),
                if (d.status == PartStatus.BAD) R.color.danger_red else R.color.safety_amber
            )
        }
    }

    private fun expiryRow(label: String, millis: Long?) {
        val color = millis?.let { FleetFormat.daysUntil(it) }?.takeIf { it <= ExpiryAlerts.WARNING_DAYS }
            ?.let { FleetFormat.expiryColor(it) }
        sections.row(label, FleetFormat.expiry(millis), color)
    }

    // ------------------------------------------------------------------ expert actions

    private fun chooseDriver() {
        val v = vehicle ?: return
        lifecycleScope.launch {
            val drivers = repository.allDriversOnce()
                .filter { it.statusEnum() != DriverStatus.LEFT && it.id != v.currentDriverId }
                .sortedWith(compareBy({ it.currentVehicleId != null }, { it.name }))
            val labels = mutableListOf<String>()
            if (v.currentDriverId != null) labels += getString(R.string.assign_none)
            labels += drivers.map { d ->
                val suffix = when {
                    d.statusEnum() == DriverStatus.SUSPENDED -> " (${DriverStatus.SUSPENDED.displayName})"
                    d.currentVehiclePlate != null -> " — ${getString(R.string.driver_of, d.currentVehiclePlate)}"
                    else -> ""
                }
                (d.name + suffix).toPersianDigitsInString()
            }
            if (labels.isEmpty()) {
                android.widget.Toast.makeText(this@VehicleDetailActivity, R.string.no_drivers_to_assign, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@VehicleDetailActivity)
                .setTitle(R.string.action_change_driver)
                .setItems(labels.toTypedArray()) { _, which ->
                    val offset = if (v.currentDriverId != null) 1 else 0
                    val driver = if (which < offset) null else drivers[which - offset]
                    pickAssignmentDate(v, driver?.id, driver?.name, driver?.currentVehiclePlate)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun pickAssignmentDate(v: VehicleEntity, driverId: String?, driverName: String?, driverCurrentPlate: String?) {
        JalaliDatePickerDialog.show(this, getString(R.string.assign_date_title), System.currentTimeMillis(), onPicked = { at ->
            val message = when {
                driverId == null -> getString(R.string.assign_confirm_none, v.currentDriverName)
                driverCurrentPlate != null -> getString(R.string.assign_confirm_move, driverName, driverCurrentPlate, v.plate)
                else -> getString(R.string.assign_confirm, driverName, v.plate)
            }.toPersianDigitsInString()
            confirm(R.string.action_change_driver, message, R.string.btn_ok) {
                lifecycleScope.launch { reportSave(repository.assignDriver(v.id, driverId, at)) }
            }
        })
    }

    private fun chooseStatus() {
        val v = vehicle ?: return
        val statuses = VehicleStatus.values()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_change_status)
            .setSingleChoiceItems(statuses.map { it.displayName }.toTypedArray(), v.statusEnum().ordinal) { dialog, which ->
                dialog.dismiss()
                val status = statuses[which]
                promptText(status.displayName, R.string.field_status_note, null) { note ->
                    lifecycleScope.launch { reportSave(repository.setVehicleStatus(v.id, status, note)) }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Session.canEdit()) {
            menu.add(Menu.NONE, MENU_ADD_FUEL, Menu.NONE, R.string.fuel_add)
            menu.add(Menu.NONE, MENU_ADD_REPAIR, Menu.NONE, R.string.work_order_add)
            menu.add(Menu.NONE, MENU_INSPECT, Menu.NONE, R.string.inspection_submit)
            menu.add(Menu.NONE, MENU_EDIT, Menu.NONE, R.string.action_edit)
            menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.action_delete)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_ADD_FUEL -> {
            FuelFormActivity.start(this, vehicleId = vehicleId)
            true
        }
        MENU_ADD_REPAIR -> {
            WorkOrderFormActivity.start(this, vehicleId = vehicleId)
            true
        }
        MENU_INSPECT -> {
            InspectionFormActivity.start(this, vehicleId)
            true
        }
        MENU_EDIT -> {
            VehicleFormActivity.start(this, vehicleId)
            true
        }
        MENU_DELETE -> {
            val plate = vehicle?.plate.orEmpty()
            confirm(R.string.action_delete, getString(R.string.vehicle_delete_confirm, plate).toPersianDigitsInString(), R.string.action_delete) {
                lifecycleScope.launch { reportSave(repository.deleteVehicle(vehicleId)) { finish() } }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_ID = "vehicle_id"
        private const val MENU_EDIT = 1
        private const val MENU_DELETE = 2
        private const val MENU_ADD_FUEL = 3
        private const val FUEL_AVERAGE_DAYS = 90L
        private const val RECENT_FUEL_ROWS = 5
        private const val RECENT_REPAIR_ROWS = 3
        private const val MENU_ADD_REPAIR = 4
        private const val MENU_INSPECT = 5

        fun start(context: Context, vehicleId: String) {
            context.startActivity(Intent(context, VehicleDetailActivity::class.java).putExtra(EXTRA_ID, vehicleId))
        }
    }
}
