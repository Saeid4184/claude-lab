package ir.sobhanbeton.fleet.ui.repairs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.WorkOrderEntity
import ir.sobhanbeton.fleet.data.WorkOrderLocation
import ir.sobhanbeton.fleet.data.WorkOrderSeverity
import ir.sobhanbeton.fleet.databinding.ActivityWorkOrderFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.longOrNull
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/**
 * Register (or edit) a breakdown / repair order — no money fields by design. A «متوقف‌کننده»
 * order puts the vehicle «در تعمیر» until it's finished. Can be opened prefilled from a weekly
 * inspection defect ([startFromDefect]); finishing that order then marks the defect repaired.
 * Expert only.
 */
class WorkOrderFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkOrderFormBinding
    private lateinit var repository: FleetRepository
    private var existing: WorkOrderEntity? = null
    private var vehicles: List<VehicleEntity> = emptyList()
    private var selectedVehicle: VehicleEntity? = null

    private lateinit var reportedAt: () -> Long?
    private lateinit var selectedSeverity: () -> Int
    private lateinit var selectedLocation: () -> Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityWorkOrderFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getStringExtra(EXTRA_ID)
        binding.toolbar.title = getString(if (id == null) R.string.work_order_add else R.string.work_order_edit)
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bind() has set up the fields

        lifecycleScope.launch {
            existing = id?.let { repository.getWorkOrder(it) }
            vehicles = repository.allVehiclesOnce().sortedWith(compareBy({ it.categoryEnum().ordinal }, { it.plate }))
            val history = repository.workOrdersInRange(0L, Long.MAX_VALUE)
            bind(history)
        }
    }

    private fun bind(history: List<WorkOrderEntity>) {
        val o = existing
        val labels = vehicles.map(::vehicleLabel)
        binding.etVehicle.setAdapter(android.widget.ArrayAdapter(this, R.layout.item_dropdown_option, labels))
        binding.etVehicle.setOnItemClickListener { _, _, position, _ -> onVehicleChosen(vehicles[position]) }

        FormFields.bindSuggestions(binding.etTitle, history.map { it.title })
        FormFields.bindSuggestions(binding.etWorkshop, history.mapNotNull { it.workshopName })

        reportedAt = FormFields.bindDateField(binding.tilDate, binding.etDate, o?.reportedAt ?: System.currentTimeMillis())
        selectedSeverity = FormFields.bindDropdown(
            binding.etSeverity, WorkOrderSeverity.values().map { it.displayName },
            (o?.severityEnum() ?: WorkOrderSeverity.STOPPING).ordinal
        )
        selectedLocation = FormFields.bindDropdown(
            binding.etLocation, WorkOrderLocation.values().map { it.displayName },
            (o?.locationEnum() ?: WorkOrderLocation.INTERNAL).ordinal
        )

        if (o != null) {
            binding.etTitle.setText(o.title)
            binding.etDetails.setText(o.details)
            binding.etWorkshop.setText(o.workshopName)
            binding.etMeter.setText(o.meterReading?.toString())
        } else {
            intent.getStringExtra(EXTRA_DEFECT_PART)?.let { part ->
                binding.etTitle.setText(getString(R.string.work_order_from_defect_title, part))
            }
        }
        val preselected = o?.vehicleId ?: intent.getStringExtra(EXTRA_VEHICLE_ID)
        vehicles.firstOrNull { it.id == preselected }?.let {
            binding.etVehicle.setText(vehicleLabel(it), false)
            onVehicleChosen(it)
        }
        binding.btnSave.isEnabled = true
    }

    private fun vehicleLabel(v: VehicleEntity): String =
        listOfNotNull(v.plate, v.groupLabel ?: v.categoryEnum().displayName).joinToString(" — ").toPersianDigitsInString()

    private fun onVehicleChosen(v: VehicleEntity) {
        selectedVehicle = v
        binding.tilVehicle.error = null
        binding.tilMeter.hint = getString(R.string.field_meter_at_breakdown_unit, v.meterTypeEnum().unit)
        binding.tvDriver.text = getString(R.string.driver_prefix, v.currentDriverName ?: getString(R.string.no_driver))
    }

    private fun save() {
        binding.tilTitle.error = null
        binding.tilMeter.error = null
        val vehicle = selectedVehicle ?: run { binding.tilVehicle.error = getString(R.string.error_required); return }
        val title = binding.etTitle.textOrNull() ?: run { binding.tilTitle.error = getString(R.string.error_required); return }
        val at = reportedAt() ?: run { binding.tilDate.error = getString(R.string.error_required); return }
        val meter = try { binding.etMeter.longOrNull() } catch (e: NumberFormatException) {
            binding.tilMeter.error = getString(R.string.error_number); return
        }
        val location = WorkOrderLocation.values()[selectedLocation()]
        val input = (existing ?: WorkOrderEntity(
            sourceInspectionId = intent.getStringExtra(EXTRA_DEFECT_INSPECTION_ID),
            sourcePartName = intent.getStringExtra(EXTRA_DEFECT_PART)
        )).copy(
            vehicleId = vehicle.id,
            reportedAt = at,
            title = title,
            details = binding.etDetails.textOrNull(),
            severity = WorkOrderSeverity.values()[selectedSeverity()].name,
            location = location.name,
            workshopName = binding.etWorkshop.textOrNull(),
            meterReading = meter
        )
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.saveWorkOrder(input)
            binding.btnSave.isEnabled = true
            reportSave(result) { id ->
                if (existing == null) WorkOrderDetailActivity.start(this@WorkOrderFormActivity, id)
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "work_order_id"
        private const val EXTRA_VEHICLE_ID = "vehicle_id"
        private const val EXTRA_DEFECT_INSPECTION_ID = "defect_inspection_id"
        private const val EXTRA_DEFECT_PART = "defect_part"

        fun start(context: Context, workOrderId: String? = null, vehicleId: String? = null) {
            context.startActivity(
                Intent(context, WorkOrderFormActivity::class.java).putExtra(EXTRA_ID, workOrderId).putExtra(EXTRA_VEHICLE_ID, vehicleId)
            )
        }

        fun startFromDefect(context: Context, vehicleId: String, inspectionId: String, partName: String) {
            context.startActivity(
                Intent(context, WorkOrderFormActivity::class.java)
                    .putExtra(EXTRA_VEHICLE_ID, vehicleId)
                    .putExtra(EXTRA_DEFECT_INSPECTION_ID, inspectionId)
                    .putExtra(EXTRA_DEFECT_PART, partName)
            )
        }
    }
}
