package ir.sobhanbeton.fleet.ui.vehicles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.MeterType
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.databinding.ActivityVehicleFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.longOrNull
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/** Add or edit a vehicle (expert only). Status and current driver aren't edited here — they
 *  have their own actions on the detail screen, which keep their history. */
class VehicleFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVehicleFormBinding
    private lateinit var repository: FleetRepository
    private var existing: VehicleEntity? = null

    private lateinit var selectedCategory: () -> Int
    private lateinit var selectedMeterType: () -> Int
    private lateinit var thirdParty: () -> Long?
    private lateinit var bodyInsurance: () -> Long?
    private lateinit var technical: () -> Long?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityVehicleFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getStringExtra(EXTRA_ID)
        binding.toolbar.title = getString(if (id == null) R.string.vehicle_add else R.string.vehicle_edit)
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bind() has set up the fields

        lifecycleScope.launch {
            existing = id?.let { repository.getVehicle(it) }
            val groups = repository.allVehiclesOnce().mapNotNull { it.groupLabel }
            bind(existing ?: VehicleEntity(), groups)
        }
    }

    private fun bind(v: VehicleEntity, groups: List<String>) {
        binding.etPlate.setText(v.plate.toPersianDigitsInString())
        binding.etBrandModel.setText(v.brandModel)
        binding.etYear.setText(v.manufactureYear?.toString())
        binding.etChassis.setText(v.chassisNumber)
        binding.etEngine.setText(v.engineNumber)
        binding.etMeter.setText(v.currentMeter?.toString())
        binding.etNotes.setText(v.notes)
        binding.etGroup.setText(v.groupLabel)
        FormFields.bindSuggestions(binding.etGroup, groups)

        val categories = VehicleCategory.values()
        selectedCategory = FormFields.bindDropdown(binding.etCategory, categories.map { it.displayName }, v.categoryEnum().ordinal)
        val meterTypes = MeterType.values()
        selectedMeterType = FormFields.bindDropdown(binding.etMeterType, meterTypes.map { it.displayName }, v.meterTypeEnum().ordinal)

        // New vehicle: picking a category (or typing a group like "پمپ ...") suggests the usual
        // meter type — pumps and loaders run on engine hours, trucks on km.
        if (existing == null) {
            binding.etCategory.setOnItemClickListener { _, _, position, _ ->
                binding.etMeterType.setText(categories[position].defaultMeterType.displayName, false)
            }
            binding.etGroup.setOnFocusChangeListener { _, hasFocus ->
                val group = binding.etGroup.text?.toString().orEmpty()
                if (!hasFocus && group.isNotBlank()) {
                    val guessed = VehicleCategory.classify(group)
                    binding.etCategory.setText(guessed.displayName, false)
                    binding.etMeterType.setText(guessed.defaultMeterType.displayName, false)
                }
            }
        }

        thirdParty = FormFields.bindDateField(binding.tilThirdParty, binding.etThirdParty, v.thirdPartyInsuranceExpiry)
        bodyInsurance = FormFields.bindDateField(binding.tilBodyInsurance, binding.etBodyInsurance, v.bodyInsuranceExpiry)
        technical = FormFields.bindDateField(binding.tilTechnical, binding.etTechnical, v.technicalInspectionExpiry)
        binding.btnSave.isEnabled = true
    }

    private fun save() {
        binding.tilPlate.error = null
        binding.tilYear.error = null
        binding.tilMeter.error = null

        val plate = binding.etPlate.textOrNull()
        if (plate == null) {
            binding.tilPlate.error = getString(R.string.error_required)
            return
        }
        val year = try { binding.etYear.longOrNull()?.toInt() } catch (e: NumberFormatException) {
            binding.tilYear.error = getString(R.string.error_number); return
        }
        val meter = try { binding.etMeter.longOrNull() } catch (e: NumberFormatException) {
            binding.tilMeter.error = getString(R.string.error_number); return
        }

        val base = existing ?: VehicleEntity()
        val input = base.copy(
            plate = plate,
            category = VehicleCategory.values()[selectedCategory()].name,
            groupLabel = binding.etGroup.textOrNull(),
            brandModel = binding.etBrandModel.textOrNull(),
            manufactureYear = year,
            chassisNumber = binding.etChassis.textOrNull(),
            engineNumber = binding.etEngine.textOrNull(),
            meterType = MeterType.values()[selectedMeterType()].name,
            currentMeter = meter,
            thirdPartyInsuranceExpiry = thirdParty(),
            bodyInsuranceExpiry = bodyInsurance(),
            technicalInspectionExpiry = technical(),
            notes = binding.etNotes.textOrNull()
        )
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.saveVehicle(input)
            binding.btnSave.isEnabled = true
            reportSave(result) { id ->
                if (existing == null) VehicleDetailActivity.start(this@VehicleFormActivity, id)
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "vehicle_id"

        fun start(context: Context, vehicleId: String?) {
            context.startActivity(Intent(context, VehicleFormActivity::class.java).putExtra(EXTRA_ID, vehicleId))
        }
    }
}
