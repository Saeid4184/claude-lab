package ir.sobhanbeton.fleet.ui.drivers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.AssignmentEntity
import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.DriverStatus
import ir.sobhanbeton.fleet.data.ExpiryAlerts
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.databinding.ActivityFleetDetailBinding
import ir.sobhanbeton.fleet.ui.common.DetailSections
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.vehicles.VehicleDetailActivity
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/** "شناسنامهٔ راننده": details, documents, current vehicle and vehicle history. Assigning a
 *  vehicle is done from the vehicle's screen (one place for that action); here the expert can
 *  edit, change status, or delete. Later phases add the driver's work, incidents, etc. */
class DriverDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetDetailBinding
    private lateinit var repository: FleetRepository
    private lateinit var sections: DetailSections
    private lateinit var driverId: String

    private var driver: DriverEntity? = null
    private var history: List<AssignmentEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driverId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityFleetDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sections = DetailSections(binding.container)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (Session.canEdit()) {
            binding.actionBar.visibility = View.VISIBLE
            binding.btnPrimaryAction.text = getString(R.string.action_edit)
            binding.btnSecondaryAction.text = getString(R.string.action_change_status)
            binding.btnPrimaryAction.setOnClickListener { DriverFormActivity.start(this, driverId) }
            binding.btnSecondaryAction.setOnClickListener { chooseStatus() }
        }

        repository.driver(driverId).observe(this) { d ->
            if (d == null) {
                if (driver != null) finish()
                return@observe
            }
            driver = d
            render()
        }
        repository.driverAssignments(driverId).observe(this) { list ->
            history = list
            render()
        }
    }

    private fun render() {
        val d = driver ?: return
        supportActionBar?.title = d.name
        val status = d.statusEnum()
        sections.clear()

        sections.section(getString(R.string.form_section_identity))
        sections.row(getString(R.string.field_driver_name), d.name)
        sections.row(
            getString(R.string.field_phone),
            FleetFormat.text(d.phone),
            onClick = d.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
            }
        )
        sections.row(getString(R.string.field_national_code), FleetFormat.text(d.nationalCode))
        sections.row(getString(R.string.field_status), status.displayName, FleetFormat.statusColor(status))
        sections.row(
            getString(R.string.field_current_vehicle),
            d.currentVehiclePlate?.toPersianDigitsInString() ?: getString(R.string.no_vehicle),
            onClick = d.currentVehicleId?.let { id -> { VehicleDetailActivity.start(this, id) } }
        )

        sections.section(getString(R.string.form_section_documents))
        sections.row(getString(R.string.field_license_type), FleetFormat.text(d.licenseType))
        sections.row(getString(R.string.field_license_number), FleetFormat.text(d.licenseNumber))
        expiryRow(getString(R.string.field_license_expiry), d.licenseExpiry)
        expiryRow(getString(R.string.field_smart_card_expiry), d.smartCardExpiry)
        expiryRow(getString(R.string.field_health_card_expiry), d.healthCardExpiry)

        sections.section(getString(R.string.detail_section_vehicle_history))
        if (history.isEmpty()) {
            sections.note(getString(R.string.history_empty))
        } else {
            history.forEach { a ->
                val period = getString(
                    R.string.period_format,
                    FleetFormat.date(a.startAt),
                    a.endAt?.let { FleetFormat.date(it) } ?: getString(R.string.until_now)
                )
                sections.row(a.vehiclePlate.toPersianDigitsInString(), period, onClick = { VehicleDetailActivity.start(this, a.vehicleId) })
            }
        }

        if (!d.notes.isNullOrBlank()) {
            sections.section(getString(R.string.form_section_other))
            sections.row(getString(R.string.field_notes), d.notes)
        }
    }

    private fun expiryRow(label: String, millis: Long?) {
        val color = millis?.let { FleetFormat.daysUntil(it) }?.takeIf { it <= ExpiryAlerts.WARNING_DAYS }
            ?.let { FleetFormat.expiryColor(it) }
        sections.row(label, FleetFormat.expiry(millis), color)
    }

    private fun chooseStatus() {
        val d = driver ?: return
        val statuses = DriverStatus.values()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_change_status)
            .setSingleChoiceItems(statuses.map { it.displayName }.toTypedArray(), d.statusEnum().ordinal) { dialog, which ->
                dialog.dismiss()
                val status = statuses[which]
                if (status == d.statusEnum()) return@setSingleChoiceItems
                val apply = { lifecycleScope.launch { reportSave(repository.setDriverStatus(d.id, status)) }; Unit }
                if (status == DriverStatus.LEFT && d.currentVehiclePlate != null) {
                    confirm(R.string.action_change_status, getString(R.string.driver_left_confirm, d.name, d.currentVehiclePlate).toPersianDigitsInString(), R.string.btn_ok, apply)
                } else {
                    apply()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Session.canEdit()) menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.action_delete)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_DELETE -> {
            confirm(R.string.action_delete, getString(R.string.driver_delete_confirm, driver?.name.orEmpty()), R.string.action_delete) {
                lifecycleScope.launch { reportSave(repository.deleteDriver(driverId)) { finish() } }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_ID = "driver_id"
        private const val MENU_DELETE = 1

        fun start(context: Context, driverId: String) {
            context.startActivity(Intent(context, DriverDetailActivity::class.java).putExtra(EXTRA_ID, driverId))
        }
    }
}
