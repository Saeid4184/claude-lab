package ir.sobhanbeton.fleet.ui.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.ServicePlanEntity
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.VehicleStatus
import ir.sobhanbeton.fleet.databinding.ActivityServiceLogFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.longOrNull
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/**
 * "ثبت سرویس": one visit, one or more services ticked (oil + filters together), date and
 * meter. Opened from the due list with the vehicle and service preselected. Expert only.
 */
class ServiceLogFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServiceLogFormBinding
    private lateinit var repository: FleetRepository
    private var vehicles: List<VehicleEntity> = emptyList()
    private var plans: List<ServicePlanEntity> = emptyList()
    private var selectedVehicle: VehicleEntity? = null
    private val chipPlanIds = mutableMapOf<Int, String>()
    private lateinit var date: () -> Long?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityServiceLogFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = getString(R.string.service_log_add)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bound

        lifecycleScope.launch {
            vehicles = repository.allVehiclesOnce()
                .filter { it.statusEnum() != VehicleStatus.OUT_OF_SERVICE }
                .sortedWith(compareBy({ it.categoryEnum().ordinal }, { it.plate }))
            plans = repository.servicePlansOnce()
            val labels = vehicles.map(::vehicleLabel)
            binding.etVehicle.setAdapter(android.widget.ArrayAdapter(this@ServiceLogFormActivity, R.layout.item_dropdown_option, labels))
            binding.etVehicle.setOnItemClickListener { _, _, position, _ -> onVehicleChosen(vehicles[position], null) }
            date = FormFields.bindDateField(binding.tilDate, binding.etDate, System.currentTimeMillis())

            vehicles.firstOrNull { it.id == intent.getStringExtra(EXTRA_VEHICLE_ID) }?.let {
                binding.etVehicle.setText(vehicleLabel(it), false)
                onVehicleChosen(it, intent.getStringExtra(EXTRA_PLAN_ID))
            } ?: run { binding.tvPlansEmpty.visibility = View.GONE }
            binding.btnSave.isEnabled = true
        }
    }

    private fun vehicleLabel(v: VehicleEntity): String =
        listOfNotNull(v.plate, v.groupLabel ?: v.categoryEnum().displayName).joinToString(" — ").toPersianDigitsInString()

    private fun onVehicleChosen(v: VehicleEntity, preselectPlanId: String?) {
        selectedVehicle = v
        binding.tilVehicle.error = null
        binding.tilMeter.hint = getString(R.string.field_meter_unit_optional, v.meterTypeEnum().unit)
        binding.tilMeter.helperText = v.currentMeter?.let { getString(R.string.fuel_current_meter, FleetFormat.number(it)) }
        if (binding.etMeter.text.isNullOrBlank()) v.currentMeter?.let { binding.etMeter.setText(it.toString()) }

        binding.chipGroupPlans.removeAllViews()
        chipPlanIds.clear()
        val forCategory = plans.filter { it.category == v.category }.sortedBy { it.name }
        binding.tvPlansEmpty.visibility = if (forCategory.isEmpty()) View.VISIBLE else View.GONE
        forCategory.forEach { p ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = p.name
                isCheckable = true
                isChecked = p.id == preselectPlanId
            }
            chipPlanIds[chip.id] = p.id
            binding.chipGroupPlans.addView(chip)
        }
    }

    private fun save() {
        binding.tilMeter.error = null
        val vehicle = selectedVehicle ?: run { binding.tilVehicle.error = getString(R.string.error_required); return }
        val planIds = (0 until binding.chipGroupPlans.childCount)
            .map { binding.chipGroupPlans.getChildAt(it) as Chip }
            .filter { it.isChecked }
            .mapNotNull { chipPlanIds[it.id] }
        if (planIds.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.service_log_choose_at_least_one, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val meter = try { binding.etMeter.longOrNull() } catch (e: NumberFormatException) {
            binding.tilMeter.error = getString(R.string.error_number); return
        }
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.logServices(vehicle.id, planIds, date() ?: System.currentTimeMillis(), meter, binding.etNotes.textOrNull())
            binding.btnSave.isEnabled = true
            reportSave(result) { finish() }
        }
    }

    companion object {
        private const val EXTRA_VEHICLE_ID = "vehicle_id"
        private const val EXTRA_PLAN_ID = "plan_id"

        fun start(context: Context, vehicleId: String? = null, planId: String? = null) {
            context.startActivity(
                Intent(context, ServiceLogFormActivity::class.java).putExtra(EXTRA_VEHICLE_ID, vehicleId).putExtra(EXTRA_PLAN_ID, planId)
            )
        }
    }
}
