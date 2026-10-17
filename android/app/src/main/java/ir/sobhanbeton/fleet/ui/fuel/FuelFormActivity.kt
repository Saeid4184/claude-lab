package ir.sobhanbeton.fleet.ui.fuel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.FuelLogEntity
import ir.sobhanbeton.fleet.data.FuelSource
import ir.sobhanbeton.fleet.data.FuelStats
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.VehicleStatus
import ir.sobhanbeton.fleet.databinding.ActivityFuelFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.longOrNull
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import ir.sobhanbeton.fleet.util.normalizeDigitsForParsing
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/**
 * "ثبت سوخت": X litres went to vehicle Y on date Z. The meter reading is optional — consumption
 * is averaged over a period from whichever fills carry one (see [FuelStats]). Expert only.
 * Opened from the fuel list, or from a vehicle's screen with that vehicle preselected.
 */
class FuelFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFuelFormBinding
    private lateinit var repository: FleetRepository
    private var existing: FuelLogEntity? = null

    private var vehicles: List<VehicleEntity> = emptyList()
    private var selectedVehicle: VehicleEntity? = null
    private lateinit var date: () -> Long?
    private lateinit var selectedSource: () -> Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityFuelFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getStringExtra(EXTRA_ID)
        supportActionBar?.title = getString(if (id == null) R.string.fuel_add else R.string.fuel_edit)
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bind() has set up the fields

        lifecycleScope.launch {
            existing = id?.let { repository.getFuelLog(it) }
            // Out-of-service vehicles can't take fuel, but keep the one being edited selectable.
            vehicles = repository.allVehiclesOnce()
                .filter { it.statusEnum() != VehicleStatus.OUT_OF_SERVICE || it.id == existing?.vehicleId }
                .sortedWith(compareBy({ it.categoryEnum().ordinal }, { it.plate }))
            val preselected = existing?.vehicleId ?: intent.getStringExtra(EXTRA_VEHICLE_ID)
            bind(existing, vehicles.firstOrNull { it.id == preselected })
        }
    }

    private fun bind(log: FuelLogEntity?, vehicle: VehicleEntity?) {
        val labels = vehicles.map(::vehicleLabel)
        binding.etVehicle.setAdapter(android.widget.ArrayAdapter(this, R.layout.item_dropdown_option, labels))
        binding.etVehicle.setOnItemClickListener { _, _, position, _ -> onVehicleChosen(vehicles[position]) }

        date = FormFields.bindDateField(binding.tilDate, binding.etDate, log?.timestamp ?: System.currentTimeMillis())
        // Re-resolve the driver when the date changes (assignments are by date).
        binding.etDate.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshDriverAndMeterHint()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        val sources = FuelSource.values()
        selectedSource = FormFields.bindDropdown(binding.etSource, sources.map { it.displayName }, (log?.sourceEnum() ?: FuelSource.INTERNAL_TANK).ordinal)

        log?.let {
            binding.etLiters.setText(formatLiters(it.liters))
            binding.etMeter.setText(it.meterReading?.toString())
            binding.etNotes.setText(it.notes)
        }
        vehicle?.let {
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
        binding.tilMeter.hint = getString(R.string.field_meter_at_fuel_unit, v.meterTypeEnum().unit)
        refreshDriverAndMeterHint()
    }

    /** Shows who was driving on the chosen date and the last meter reading before it. */
    private fun refreshDriverAndMeterHint() {
        val v = selectedVehicle ?: return
        val at = date() ?: return
        lifecycleScope.launch {
            val driver = repository.driverAt(v.id, at)
            binding.tvDriver.text = getString(R.string.driver_prefix, driver?.driverName ?: getString(R.string.no_driver))
            val previous = FuelStats.previousReading(repository.vehicleFuelOnce(v.id), at, existing?.id)
            binding.tilMeter.helperText = previous?.let {
                getString(R.string.fuel_previous_reading, FleetFormat.number(it.meterReading), FleetFormat.date(it.timestamp))
            } ?: v.currentMeter?.let { getString(R.string.fuel_current_meter, FleetFormat.number(it)) }
        }
    }

    private fun save() {
        binding.tilLiters.error = null
        binding.tilMeter.error = null
        val vehicle = selectedVehicle ?: run { binding.tilVehicle.error = getString(R.string.error_required); return }
        val at = date() ?: run { binding.tilDate.error = getString(R.string.error_required); return }
        val liters = binding.etLiters.textOrNull()?.normalizeDigitsForParsing()?.toDoubleOrNull()
        if (liters == null || liters <= 0.0) {
            binding.tilLiters.error = getString(R.string.error_positive_number); return
        }
        val meter = try { binding.etMeter.longOrNull() } catch (e: NumberFormatException) {
            binding.tilMeter.error = getString(R.string.error_number); return
        }
        val input = (existing ?: FuelLogEntity()).copy(
            vehicleId = vehicle.id,
            timestamp = at,
            liters = liters,
            meterReading = meter,
            source = FuelSource.values()[selectedSource()].name,
            notes = binding.etNotes.textOrNull()
        )

        lifecycleScope.launch {
            // A reading lower than the previous one is usually a typo — but can be a replaced
            // odometer, so ask rather than refuse.
            val previous = FuelStats.previousReading(repository.vehicleFuelOnce(vehicle.id), at, existing?.id)
            if (meter != null && previous?.meterReading != null && meter < previous.meterReading) {
                confirm(
                    R.string.fuel_meter_backwards_title,
                    getString(R.string.fuel_meter_backwards, FleetFormat.number(meter), FleetFormat.number(previous.meterReading)),
                    R.string.btn_save
                ) { persist(input) }
            } else {
                persist(input)
            }
        }
    }

    private fun persist(input: FuelLogEntity) {
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.saveFuel(input)
            binding.btnSave.isEnabled = true
            reportSave(result) { finish() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (intent.getStringExtra(EXTRA_ID) != null) menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.action_delete)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_DELETE -> {
            val id = intent.getStringExtra(EXTRA_ID)
            if (id != null) {
                confirm(R.string.action_delete, getString(R.string.fuel_delete_confirm), R.string.action_delete) {
                    lifecycleScope.launch { reportSave(repository.deleteFuel(id)) { finish() } }
                }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_ID = "fuel_id"
        private const val EXTRA_VEHICLE_ID = "vehicle_id"
        private const val MENU_DELETE = 1

        fun start(context: Context, fuelId: String? = null, vehicleId: String? = null) {
            context.startActivity(
                Intent(context, FuelFormActivity::class.java).putExtra(EXTRA_ID, fuelId).putExtra(EXTRA_VEHICLE_ID, vehicleId)
            )
        }

        fun formatLiters(liters: Double): String =
            if (liters % 1.0 == 0.0) liters.toLong().toString() else "%.1f".format(java.util.Locale.US, liters)
    }
}
