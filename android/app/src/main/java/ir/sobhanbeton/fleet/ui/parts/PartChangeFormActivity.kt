package ir.sobhanbeton.fleet.ui.parts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.PartChangeEntity
import ir.sobhanbeton.fleet.data.PartKind
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.WheelPositions
import ir.sobhanbeton.fleet.databinding.ActivityPartChangeFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/**
 * Record a tyre or battery replacement — only what was asked for: the vehicle, the date and,
 * for tyres, which wheels (chips laid out for the vehicle's type, see [WheelPositions]).
 * Expert only.
 */
class PartChangeFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPartChangeFormBinding
    private lateinit var repository: FleetRepository
    private var existing: PartChangeEntity? = null
    private var vehicles: List<VehicleEntity> = emptyList()
    private var selectedVehicle: VehicleEntity? = null
    private var kind = PartKind.TIRE
    private val chipWheelCodes = mutableMapOf<Int, String>()
    private lateinit var date: () -> Long?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityPartChangeFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val id = intent.getStringExtra(EXTRA_ID)
        supportActionBar?.title = getString(if (id == null) R.string.part_change_add else R.string.part_change_edit)
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bound

        lifecycleScope.launch {
            existing = id?.let { repository.getPartChange(it) }
            vehicles = repository.allVehiclesOnce().sortedWith(compareBy({ it.categoryEnum().ordinal }, { it.plate }))
            bind()
        }
    }

    private fun bind() {
        val e = existing
        kind = e?.kindEnum() ?: PartKind.TIRE
        date = FormFields.bindDateField(binding.tilDate, binding.etDate, e?.date ?: System.currentTimeMillis())

        val kinds = PartKind.values()
        FormFields.bindDropdown(binding.etKind, kinds.map { it.displayName }, kind.ordinal)
        binding.etKind.setOnItemClickListener { _, _, position, _ -> kind = kinds[position]; updateWheelsVisibility() }

        val labels = vehicles.map { vehicleLabel(it) }
        binding.etVehicle.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, labels))
        binding.etVehicle.setOnItemClickListener { _, _, position, _ -> onVehicleChosen(vehicles[position], emptyList()) }

        binding.etNotes.setText(e?.notes)
        val vehicleId = e?.vehicleId ?: intent.getStringExtra(EXTRA_VEHICLE_ID)
        vehicles.firstOrNull { it.id == vehicleId }?.let {
            binding.etVehicle.setText(vehicleLabel(it), false)
            onVehicleChosen(it, e?.wheelCodes().orEmpty())
        }
        updateWheelsVisibility()
        binding.btnSave.isEnabled = true
        invalidateOptionsMenu()
    }

    private fun vehicleLabel(v: VehicleEntity): String =
        listOfNotNull(v.plate, v.groupLabel ?: v.categoryEnum().displayName).joinToString(" — ").toPersianDigitsInString()

    private fun onVehicleChosen(v: VehicleEntity, preselected: List<String>) {
        selectedVehicle = v
        binding.tilVehicle.error = null
        binding.chipGroupWheels.removeAllViews()
        chipWheelCodes.clear()
        // A stored code the vehicle's current layout lacks (its group changed) still shows, checked.
        val positions = WheelPositions.positionsFor(v).map { it.code to it.label } +
            preselected.filter { code -> WheelPositions.positionsFor(v).none { it.code == code } }.map { it to WheelPositions.label(it) }
        positions.forEach { (code, label) ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label.toPersianDigitsInString()
                isCheckable = true
                isChecked = code in preselected
            }
            chipWheelCodes[chip.id] = code
            binding.chipGroupWheels.addView(chip)
        }
        updateWheelsVisibility()
    }

    private fun updateWheelsVisibility() {
        val tire = kind == PartKind.TIRE
        binding.tvWheelsLabel.visibility = if (tire) View.VISIBLE else View.GONE
        binding.chipGroupWheels.visibility = if (tire) View.VISIBLE else View.GONE
        binding.tvWheelsHint.visibility = if (tire && selectedVehicle == null) View.VISIBLE else View.GONE
        binding.tvWheelsHint.setText(R.string.part_choose_vehicle_first)
    }

    private fun checkedWheels(): List<String> = (0 until binding.chipGroupWheels.childCount)
        .map { binding.chipGroupWheels.getChildAt(it) as Chip }
        .filter { it.isChecked }
        .mapNotNull { chipWheelCodes[it.id] }

    private fun save() {
        val vehicle = selectedVehicle ?: run { binding.tilVehicle.error = getString(R.string.error_required); return }
        val wheels = if (kind == PartKind.TIRE) checkedWheels() else emptyList()
        if (kind == PartKind.TIRE && wheels.isEmpty()) {
            binding.tvWheelsHint.setText(R.string.part_wheels_required)
            binding.tvWheelsHint.visibility = View.VISIBLE
            return
        }
        val input = (existing ?: PartChangeEntity()).copy(
            vehicleId = vehicle.id,
            kind = kind.name,
            date = date() ?: System.currentTimeMillis(),
            wheels = wheels.joinToString(","),
            notes = binding.etNotes.textOrNull()
        )
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.savePartChange(input)
            binding.btnSave.isEnabled = true
            reportSave(result) { finish() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (existing != null) menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.action_delete)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_DELETE -> {
            existing?.let { e ->
                confirm(R.string.action_delete, getString(R.string.part_change_delete_confirm), R.string.action_delete) {
                    lifecycleScope.launch { reportSave(repository.deletePartChange(e.id)) { finish() } }
                }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_ID = "part_change_id"
        private const val EXTRA_VEHICLE_ID = "vehicle_id"
        private const val MENU_DELETE = 1

        fun start(context: Context, changeId: String? = null, vehicleId: String? = null) {
            context.startActivity(
                Intent(context, PartChangeFormActivity::class.java).putExtra(EXTRA_ID, changeId).putExtra(EXTRA_VEHICLE_ID, vehicleId)
            )
        }
    }
}
