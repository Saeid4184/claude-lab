package ir.sobhanbeton.fleet.ui.work

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.DailyWorkEntity
import ir.sobhanbeton.fleet.data.DailyWorkStats
import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.DriverStatus
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.VehicleStatus
import ir.sobhanbeton.fleet.data.WorkMetrics
import ir.sobhanbeton.fleet.databinding.ActivityDailyWorkFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.fuel.FuelFormActivity
import ir.sobhanbeton.fleet.ui.common.FormFields.longOrNull
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import ir.sobhanbeton.fleet.util.normalizeDigitsForParsing
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/**
 * One vehicle's work on one day. The fields shown depend on the vehicle ([WorkMetrics]): mixer
 * = services + m³ carried; dump truck = quarry loads + rubble loads; pump = m³ discharged;
 * loader/logistics = services. No km/hours — they aren't the performance measure. Saving the
 * same vehicle and day again corrects the existing record. Expert only.
 */
class DailyWorkFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDailyWorkFormBinding
    private lateinit var repository: FleetRepository
    private var existing: DailyWorkEntity? = null
    private var vehicles: List<VehicleEntity> = emptyList()
    private var drivers: List<DriverEntity> = emptyList()
    private var selectedVehicle: VehicleEntity? = null
    /** Null = "use whoever is assigned that day". */
    private var selectedDriverId: String? = null
    private lateinit var date: () -> Long?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityDailyWorkFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.daily_work_add)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bound

        lifecycleScope.launch {
            val vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID)
            val day = intent.getLongExtra(EXTRA_DATE, System.currentTimeMillis())
            existing = vehicleId?.let { repository.getDailyWork(DailyWorkEntity.idFor(it, DailyWorkStats.dayKey(day))) }
            vehicles = repository.allVehiclesOnce()
                .filter { it.statusEnum() != VehicleStatus.OUT_OF_SERVICE || it.id == vehicleId }
                .sortedWith(compareBy({ it.categoryEnum().ordinal }, { it.plate }))
            drivers = repository.allDriversOnce().filter { it.statusEnum() != DriverStatus.LEFT }.sortedBy { it.name }

            binding.etVehicle.setAdapter(android.widget.ArrayAdapter(this@DailyWorkFormActivity, R.layout.item_dropdown_option, vehicles.map(::vehicleLabel)))
            binding.etVehicle.setOnItemClickListener { _, _, position, _ -> onVehicleChosen(vehicles[position]) }
            val driverLabels = listOf(getString(R.string.work_driver_assigned)) + drivers.map { it.name }
            binding.etDriver.setAdapter(android.widget.ArrayAdapter(this@DailyWorkFormActivity, R.layout.item_dropdown_option, driverLabels))
            binding.etDriver.setOnItemClickListener { _, _, position, _ -> selectedDriverId = if (position == 0) null else drivers[position - 1].id }
            date = FormFields.bindDateField(binding.tilDate, binding.etDate, existing?.date ?: day)

            val e = existing
            if (e != null) {
                supportActionBar?.title = getString(R.string.daily_work_edit)
                e.trips?.let { binding.etTrips.setText(it.toString()) }
                e.concreteM3?.let { binding.etConcrete.setText(FuelFormActivity.formatLiters(it)) }
                e.mineTrips?.let { binding.etMineTrips.setText(it.toString()) }
                e.debrisTrips?.let { binding.etDebrisTrips.setText(it.toString()) }
                binding.etNotes.setText(e.notes)
                selectedDriverId = e.driverId
                binding.etDriver.setText(e.driverName ?: driverLabels[0], false)
            } else {
                binding.etDriver.setText(driverLabels[0], false)
            }
            vehicles.firstOrNull { it.id == vehicleId }?.let {
                binding.etVehicle.setText(vehicleLabel(it), false)
                onVehicleChosen(it)
            } ?: showFields(emptyList())
            binding.btnSave.isEnabled = true
            invalidateOptionsMenu()
        }
    }

    private fun vehicleLabel(v: VehicleEntity): String =
        listOfNotNull(v.plate, v.groupLabel ?: v.categoryEnum().displayName).joinToString(" — ").toPersianDigitsInString()

    private fun onVehicleChosen(v: VehicleEntity) {
        selectedVehicle = v
        binding.tilVehicle.error = null
        showFields(WorkMetrics.fieldsFor(v.categoryEnum()))
        // A mixer's "trips" are concrete deliveries; for loaders/logistics they're just services.
        binding.tilTrips.hint = getString(if (v.categoryEnum() == VehicleCategory.MIXER) R.string.field_trips_mixer else R.string.field_trips)
        binding.tilConcrete.hint = getString(if (v.categoryEnum() == VehicleCategory.CONCRETE_PUMP) R.string.field_concrete_pumped else R.string.field_concrete_m3)
    }

    private fun showFields(fields: List<WorkMetrics.Field>) {
        binding.tilTrips.visibility = if (WorkMetrics.Field.TRIPS in fields) View.VISIBLE else View.GONE
        binding.tilConcrete.visibility = if (WorkMetrics.Field.CONCRETE_M3 in fields) View.VISIBLE else View.GONE
        binding.tilMineTrips.visibility = if (WorkMetrics.Field.MINE_TRIPS in fields) View.VISIBLE else View.GONE
        binding.tilDebrisTrips.visibility = if (WorkMetrics.Field.DEBRIS_TRIPS in fields) View.VISIBLE else View.GONE
    }

    private fun save() {
        listOf(binding.tilTrips, binding.tilConcrete, binding.tilMineTrips, binding.tilDebrisTrips).forEach { it.error = null }
        val vehicle = selectedVehicle ?: run { binding.tilVehicle.error = getString(R.string.error_required); return }
        fun int(field: android.widget.EditText, layout: com.google.android.material.textfield.TextInputLayout): Int? =
            try { field.longOrNull()?.toInt() } catch (e: NumberFormatException) { layout.error = getString(R.string.error_number); throw e }
        val input = try {
            DailyWorkEntity(
                id = existing?.id.orEmpty(),
                vehicleId = vehicle.id,
                driverId = selectedDriverId,
                date = date() ?: System.currentTimeMillis(),
                trips = int(binding.etTrips, binding.tilTrips),
                concreteM3 = binding.etConcrete.textOrNull()?.normalizeDigitsForParsing()?.let {
                    it.toDoubleOrNull() ?: run { binding.tilConcrete.error = getString(R.string.error_number); throw NumberFormatException() }
                },
                mineTrips = int(binding.etMineTrips, binding.tilMineTrips),
                debrisTrips = int(binding.etDebrisTrips, binding.tilDebrisTrips),
                notes = binding.etNotes.textOrNull()
            )
        } catch (e: NumberFormatException) {
            return
        }
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.saveDailyWork(input)
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
                confirm(R.string.action_delete, getString(R.string.daily_work_delete_confirm), R.string.action_delete) {
                    lifecycleScope.launch { reportSave(repository.deleteDailyWork(e.id)) { finish() } }
                }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_VEHICLE_ID = "vehicle_id"
        private const val EXTRA_DATE = "date"
        private const val MENU_DELETE = 1

        fun start(context: Context, vehicleId: String?, date: Long) {
            context.startActivity(Intent(context, DailyWorkFormActivity::class.java).putExtra(EXTRA_VEHICLE_ID, vehicleId).putExtra(EXTRA_DATE, date))
        }
    }
}
