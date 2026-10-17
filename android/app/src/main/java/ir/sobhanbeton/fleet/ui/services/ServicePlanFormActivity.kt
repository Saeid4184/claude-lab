package ir.sobhanbeton.fleet.ui.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.ServicePlanEntity
import ir.sobhanbeton.fleet.data.ServiceSchedule
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.databinding.ActivityServicePlanFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.longOrNull
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import kotlinx.coroutines.launch

/** Add/edit a periodic service rule for one category ("every X km/hours or Y days"). Expert only. */
class ServicePlanFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServicePlanFormBinding
    private lateinit var repository: FleetRepository
    private var existing: ServicePlanEntity? = null
    private lateinit var selectedCategory: () -> Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityServicePlanFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val id = intent.getStringExtra(EXTRA_ID)
        supportActionBar?.title = getString(if (id == null) R.string.service_plan_add else R.string.service_plan_edit)
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bound

        lifecycleScope.launch {
            existing = id?.let { repository.getServicePlan(it) }
            val p = existing
            val names = repository.servicePlansOnce().map { it.name } + ServiceSchedule.defaultPlans().map { it.name }
            FormFields.bindSuggestions(binding.etName, names)
            selectedCategory = FormFields.bindDropdown(
                binding.etCategory, VehicleCategory.values().map { it.displayName }, (p?.categoryEnum() ?: VehicleCategory.MIXER).ordinal
            )
            binding.etName.setText(p?.name)
            binding.etIntervalMeter.setText(p?.intervalMeter?.toString())
            binding.etIntervalDays.setText(p?.intervalDays?.toString())
            binding.etNotes.setText(p?.notes)
            binding.btnSave.isEnabled = true
        }
    }

    private fun save() {
        binding.tilName.error = null
        binding.tilIntervalMeter.error = null
        binding.tilIntervalDays.error = null
        val name = binding.etName.textOrNull() ?: run { binding.tilName.error = getString(R.string.error_required); return }
        val meter = try { binding.etIntervalMeter.longOrNull() } catch (e: NumberFormatException) {
            binding.tilIntervalMeter.error = getString(R.string.error_number); return
        }
        val days = try { binding.etIntervalDays.longOrNull()?.toInt() } catch (e: NumberFormatException) {
            binding.tilIntervalDays.error = getString(R.string.error_number); return
        }
        if (meter == null && days == null) {
            binding.tilIntervalMeter.error = getString(R.string.service_plan_need_interval); return
        }
        val input = (existing ?: ServicePlanEntity()).copy(
            name = name,
            category = VehicleCategory.values()[selectedCategory()].name,
            intervalMeter = meter,
            intervalDays = days,
            notes = binding.etNotes.textOrNull()
        )
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.saveServicePlan(input)
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
                confirm(R.string.action_delete, getString(R.string.service_plan_delete_confirm), R.string.action_delete) {
                    lifecycleScope.launch { reportSave(repository.deleteServicePlan(id)) { finish() } }
                }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_ID = "plan_id"
        private const val MENU_DELETE = 1

        fun start(context: Context, planId: String?) {
            context.startActivity(Intent(context, ServicePlanFormActivity::class.java).putExtra(EXTRA_ID, planId))
        }
    }
}
