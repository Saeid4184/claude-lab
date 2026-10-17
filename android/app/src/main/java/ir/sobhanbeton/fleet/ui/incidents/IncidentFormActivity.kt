package ir.sobhanbeton.fleet.ui.incidents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.IncidentCatalog
import ir.sobhanbeton.fleet.data.IncidentEntity
import ir.sobhanbeton.fleet.data.IncidentType
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.databinding.ActivityIncidentFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/**
 * Record an accident, fine, violation, commendation or other note against a vehicle and/or
 * driver — the security app's "pick, don't type" report form: subject/severity/action come
 * from [IncidentCatalog] lists («سایر» opens a text field), with a live one-sentence preview.
 * No money fields. Expert only.
 */
class IncidentFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncidentFormBinding
    private lateinit var repository: FleetRepository
    private var existing: IncidentEntity? = null
    private var vehicles: List<VehicleEntity> = emptyList()
    private var drivers: List<DriverEntity> = emptyList()
    private var type = IncidentType.ACCIDENT
    private var vehicleId: String? = null
    private var driverId: String? = null
    private lateinit var date: () -> Long?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityIncidentFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val id = intent.getStringExtra(EXTRA_ID)
        supportActionBar?.title = getString(if (id == null) R.string.incident_add else R.string.incident_edit)
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bound

        lifecycleScope.launch {
            existing = id?.let { repository.getIncident(it) }
            vehicles = repository.allVehiclesOnce().sortedWith(compareBy({ it.categoryEnum().ordinal }, { it.plate }))
            drivers = repository.allDriversOnce().sortedBy { it.name }
            bind()
        }
    }

    private fun bind() {
        val e = existing
        type = e?.typeEnum() ?: IncidentType.ACCIDENT
        vehicleId = e?.vehicleId ?: intent.getStringExtra(EXTRA_VEHICLE_ID)
        driverId = e?.driverId ?: intent.getStringExtra(EXTRA_DRIVER_ID)
        date = FormFields.bindDateField(binding.tilDate, binding.etDate, e?.occurredAt ?: System.currentTimeMillis())

        val types = IncidentType.values()
        FormFields.bindDropdown(binding.etType, types.map { it.displayName }, type.ordinal)
        binding.etType.setOnItemClickListener { _, _, position, _ -> type = types[position]; onTypeChanged(resetChoices = true) }

        val none = getString(R.string.choice_none)
        val vehicleLabels = listOf(none) + vehicles.map { it.plate.toPersianDigitsInString() + (it.groupLabel?.let { g -> " — $g" } ?: "") }
        binding.etVehicle.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, vehicleLabels))
        binding.etVehicle.setText(vehicles.indexOfFirst { it.id == vehicleId }.let { if (it >= 0) vehicleLabels[it + 1] else none }, false)
        binding.etVehicle.setOnItemClickListener { _, _, position, _ -> vehicleId = if (position == 0) null else vehicles[position - 1].id; updatePreview() }

        val driverLabels = listOf(none) + drivers.map { it.name }
        binding.etDriver.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, driverLabels))
        binding.etDriver.setText(drivers.indexOfFirst { it.id == driverId }.let { if (it >= 0) driverLabels[it + 1] else none }, false)
        binding.etDriver.setOnItemClickListener { _, _, position, _ -> driverId = if (position == 0) null else drivers[position - 1].id; updatePreview() }

        binding.etSeverity.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, IncidentCatalog.severityOptions))
        binding.etSeverity.setOnItemClickListener { _, _, _, _ -> updatePreview() }
        binding.etAction.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, IncidentCatalog.actionOptions))
        binding.etAction.setOnItemClickListener { _, _, _, _ -> onChoicesChanged() }
        binding.etSubject.setOnItemClickListener { _, _, _, _ -> onChoicesChanged() }

        listOf(binding.etSubjectOther, binding.etActionOther, binding.etLocation).forEach { it.doAfterTextChanged { updatePreview() } }
        binding.etDate.doAfterTextChanged { updatePreview() }
        onTypeChanged(resetChoices = false)

        if (e != null) {
            setChoice(binding.etSubject, binding.etSubjectOther, IncidentCatalog.subjectsFor(type), e.subject)
            e.severity?.let { binding.etSeverity.setText(it, false) }
            e.actionTaken?.let { setChoice(binding.etAction, binding.etActionOther, IncidentCatalog.actionOptions, it) }
            binding.etLocation.setText(e.location)
            binding.etDescription.setText(e.description)
        }
        onChoicesChanged()
        binding.btnSave.isEnabled = true
        invalidateOptionsMenu()
    }

    /** A stored value that isn't one of the list's phrases was typed under «سایر». */
    private fun setChoice(dropdown: android.widget.AutoCompleteTextView, other: android.widget.EditText, options: List<String>, value: String) {
        if (value in options) {
            dropdown.setText(value, false)
        } else {
            dropdown.setText(IncidentCatalog.OTHER, false)
            other.setText(value)
        }
    }

    private fun onTypeChanged(resetChoices: Boolean) {
        binding.etSubject.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, IncidentCatalog.subjectsFor(type)))
        if (resetChoices) {
            binding.etSubject.setText("", false)
            binding.etSubjectOther.setText("")
        }
        binding.tilSeverity.visibility = if (IncidentCatalog.showsSeverity(type)) View.VISIBLE else View.GONE
        binding.tilAction.visibility = if (IncidentCatalog.showsAction(type)) View.VISIBLE else View.GONE
        onChoicesChanged()
    }

    private fun onChoicesChanged() {
        binding.tilSubjectOther.visibility = if (binding.etSubject.text.toString() == IncidentCatalog.OTHER) View.VISIBLE else View.GONE
        binding.tilActionOther.visibility =
            if (IncidentCatalog.showsAction(type) && binding.etAction.text.toString() == IncidentCatalog.OTHER) View.VISIBLE else View.GONE
        updatePreview()
    }

    private fun subject(): String? =
        binding.etSubject.text.toString().let { if (it == IncidentCatalog.OTHER) binding.etSubjectOther.textOrNull() else it.ifBlank { null } }

    private fun action(): String? = if (!IncidentCatalog.showsAction(type)) null else
        binding.etAction.text.toString().let { if (it == IncidentCatalog.OTHER) binding.etActionOther.textOrNull() else it.ifBlank { null } }

    private fun severity(): String? = if (IncidentCatalog.showsSeverity(type)) binding.etSeverity.text.toString().ifBlank { null } else null

    private fun updatePreview() {
        if (!::date.isInitialized) return
        val plate = vehicles.firstOrNull { it.id == vehicleId }?.plate
        val driver = drivers.firstOrNull { it.id == driverId }?.name
        binding.tvPreview.text = IncidentCatalog.buildSummary(
            type, FleetFormat.date(date() ?: System.currentTimeMillis()), subject().orEmpty(), plate, driver,
            severity(), binding.etLocation.textOrNull(), action()
        ).toPersianDigitsInString()
    }

    private fun save() {
        binding.tilSubject.error = null
        val subject = subject() ?: run { binding.tilSubject.error = getString(R.string.error_required); return }
        if (vehicleId == null && driverId == null) {
            binding.tilVehicle.error = getString(R.string.incident_need_subject)
            return
        }
        binding.tilVehicle.error = null
        val input = (existing ?: IncidentEntity()).copy(
            type = type.name,
            occurredAt = date() ?: System.currentTimeMillis(),
            vehicleId = vehicleId,
            vehiclePlate = null,
            driverId = driverId,
            driverName = null,
            subject = subject,
            severity = severity(),
            location = binding.etLocation.textOrNull(),
            actionTaken = action(),
            description = binding.etDescription.textOrNull()
        )
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.saveIncident(input)
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
                confirm(R.string.action_delete, getString(R.string.incident_delete_confirm), R.string.action_delete) {
                    lifecycleScope.launch { reportSave(repository.deleteIncident(e.id)) { finish() } }
                }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_ID = "incident_id"
        private const val EXTRA_VEHICLE_ID = "vehicle_id"
        private const val EXTRA_DRIVER_ID = "driver_id"
        private const val MENU_DELETE = 1

        fun start(context: Context, incidentId: String? = null, vehicleId: String? = null, driverId: String? = null) {
            context.startActivity(
                Intent(context, IncidentFormActivity::class.java)
                    .putExtra(EXTRA_ID, incidentId).putExtra(EXTRA_VEHICLE_ID, vehicleId).putExtra(EXTRA_DRIVER_ID, driverId)
            )
        }
    }
}
