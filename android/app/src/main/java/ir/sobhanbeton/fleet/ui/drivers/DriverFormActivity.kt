package ir.sobhanbeton.fleet.ui.drivers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.databinding.ActivityDriverFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import kotlinx.coroutines.launch

/** Add or edit a driver (expert only). Status and vehicle have their own actions. */
class DriverFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDriverFormBinding
    private lateinit var repository: FleetRepository
    private var existing: DriverEntity? = null

    private lateinit var licenseExpiry: () -> Long?
    private lateinit var smartCard: () -> Long?
    private lateinit var healthCard: () -> Long?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityDriverFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getStringExtra(EXTRA_ID)
        binding.toolbar.title = getString(if (id == null) R.string.driver_add else R.string.driver_edit)
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bind() has set up the fields

        lifecycleScope.launch {
            existing = id?.let { repository.getDriver(it) }
            bind(existing ?: DriverEntity())
        }
    }

    private fun bind(d: DriverEntity) {
        binding.etName.setText(d.name)
        binding.etPhone.setText(d.phone)
        binding.etNationalCode.setText(d.nationalCode)
        binding.etLicenseNumber.setText(d.licenseNumber)
        binding.etNotes.setText(d.notes)
        binding.etLicenseType.setText(d.licenseType)
        FormFields.bindSuggestions(binding.etLicenseType, resources.getStringArray(R.array.license_types).toList())

        licenseExpiry = FormFields.bindDateField(binding.tilLicenseExpiry, binding.etLicenseExpiry, d.licenseExpiry)
        smartCard = FormFields.bindDateField(binding.tilSmartCard, binding.etSmartCard, d.smartCardExpiry)
        healthCard = FormFields.bindDateField(binding.tilHealthCard, binding.etHealthCard, d.healthCardExpiry)
        binding.btnSave.isEnabled = true
    }

    private fun save() {
        binding.tilName.error = null
        val name = binding.etName.textOrNull()
        if (name == null) {
            binding.tilName.error = getString(R.string.error_required)
            return
        }
        val input = (existing ?: DriverEntity()).copy(
            name = name,
            phone = binding.etPhone.textOrNull(),
            nationalCode = binding.etNationalCode.textOrNull(),
            licenseType = binding.etLicenseType.textOrNull(),
            licenseNumber = binding.etLicenseNumber.textOrNull(),
            licenseExpiry = licenseExpiry(),
            smartCardExpiry = smartCard(),
            healthCardExpiry = healthCard(),
            notes = binding.etNotes.textOrNull()
        )
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.saveDriver(input)
            binding.btnSave.isEnabled = true
            reportSave(result) { id ->
                if (existing == null) DriverDetailActivity.start(this@DriverFormActivity, id)
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "driver_id"

        fun start(context: Context, driverId: String?) {
            context.startActivity(Intent(context, DriverFormActivity::class.java).putExtra(EXTRA_ID, driverId))
        }
    }
}
