package ir.sobhanbeton.fleet.ui.tank

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.TankDeliveryEntity
import ir.sobhanbeton.fleet.data.TankReadingEntity
import ir.sobhanbeton.fleet.data.TankWithdrawalEntity
import ir.sobhanbeton.fleet.databinding.ActivityTankEntryFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import ir.sobhanbeton.fleet.util.normalizeDigitsForParsing
import kotlinx.coroutines.launch

/** One tank movement: a tanker delivery, a non-fleet withdrawal (generator, …) or a physical
 *  measurement. Same small form, fields relabelled per [Mode]. Expert only. */
class TankEntryFormActivity : AppCompatActivity() {

    enum class Mode { DELIVERY, WITHDRAWAL, READING }

    private lateinit var binding: ActivityTankEntryFormBinding
    private lateinit var repository: FleetRepository
    private lateinit var mode: Mode
    private var editingId: String? = null
    private var createdAt = 0L
    private lateinit var date: () -> Long?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        mode = runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE)!!) }.getOrNull() ?: run { finish(); return }
        editingId = intent.getStringExtra(EXTRA_ID)
        repository = FleetRepository.get(this)
        binding = ActivityTankEntryFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(
            when (mode) {
                Mode.DELIVERY -> R.string.tank_add_delivery
                Mode.WITHDRAWAL -> R.string.tank_add_withdrawal
                Mode.READING -> R.string.tank_add_reading
            }
        )
        when (mode) {
            Mode.DELIVERY -> {
                binding.tilText1.hint = getString(R.string.field_tank_reference)
                binding.tilText2.hint = getString(R.string.field_tank_supplier)
            }
            Mode.WITHDRAWAL -> {
                binding.tilText1.hint = getString(R.string.field_tank_consumer)
                binding.tilText2.visibility = View.GONE
            }
            Mode.READING -> {
                binding.tilLiters.hint = getString(R.string.field_tank_measured)
                binding.tilText1.visibility = View.GONE
                binding.tilText2.visibility = View.GONE
            }
        }
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until bound

        lifecycleScope.launch {
            var initialDate = System.currentTimeMillis()
            val id = editingId
            if (id != null) when (mode) {
                Mode.DELIVERY -> repository.getTankDelivery(id)?.let {
                    initialDate = it.date; createdAt = it.createdAt; fill(it.liters, it.reference, it.supplier, it.notes)
                }
                Mode.WITHDRAWAL -> repository.getTankWithdrawal(id)?.let {
                    initialDate = it.date; createdAt = it.createdAt; fill(it.liters, it.consumer, null, it.notes)
                }
                Mode.READING -> repository.getTankReading(id)?.let {
                    initialDate = it.date; createdAt = it.createdAt; fill(it.liters, null, null, it.notes)
                }
            }
            date = FormFields.bindDateField(binding.tilDate, binding.etDate, initialDate)
            val status = repository.tankStatusOnce()
            val config = repository.tankConfigOnce()
            binding.tilLiters.helperText = status.stock?.let {
                getString(R.string.tank_stock_helper, FleetFormat.decimal(it), FleetFormat.decimal(config.capacityLiters))
            }
            binding.btnSave.isEnabled = true
            invalidateOptionsMenu()
        }
    }

    private fun fill(liters: Double, text1: String?, text2: String?, notes: String?) {
        binding.etLiters.setText(FleetFormat.decimal(liters))
        binding.etText1.setText(text1)
        binding.etText2.setText(text2)
        binding.etNotes.setText(notes)
    }

    private fun save() {
        binding.tilLiters.error = null
        binding.tilText1.error = null
        val liters = binding.etLiters.textOrNull()?.normalizeDigitsForParsing()?.toDoubleOrNull()
        if (liters == null || liters < 0 || (liters == 0.0 && mode != Mode.READING)) {
            binding.tilLiters.error = getString(R.string.error_positive_number); return
        }
        val at = date() ?: System.currentTimeMillis()
        val id = editingId.orEmpty()
        val notes = binding.etNotes.textOrNull()
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = when (mode) {
                Mode.DELIVERY -> repository.saveTankDelivery(
                    TankDeliveryEntity(id, at, liters, binding.etText1.textOrNull(), binding.etText2.textOrNull(), notes, createdAt = createdAt)
                )
                Mode.WITHDRAWAL -> {
                    val consumer = binding.etText1.textOrNull()
                    if (consumer == null) {
                        binding.tilText1.error = getString(R.string.error_required)
                        binding.btnSave.isEnabled = true
                        return@launch
                    }
                    repository.saveTankWithdrawal(TankWithdrawalEntity(id, at, liters, consumer, notes, createdAt = createdAt))
                }
                Mode.READING -> repository.saveTankReading(TankReadingEntity(id, at, liters, notes, createdAt = createdAt))
            }
            binding.btnSave.isEnabled = true
            reportSave(result) { finish() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (editingId != null) menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.action_delete)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_DELETE -> {
            val id = editingId
            if (id != null) confirm(R.string.action_delete, getString(R.string.tank_delete_confirm), R.string.action_delete) {
                lifecycleScope.launch {
                    reportSave(
                        when (mode) {
                            Mode.DELIVERY -> repository.deleteTankDelivery(id)
                            Mode.WITHDRAWAL -> repository.deleteTankWithdrawal(id)
                            Mode.READING -> repository.deleteTankReading(id)
                        }
                    ) { finish() }
                }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ID = "id"
        private const val MENU_DELETE = 1

        fun start(context: Context, mode: Mode, id: String? = null) {
            context.startActivity(Intent(context, TankEntryFormActivity::class.java).putExtra(EXTRA_MODE, mode.name).putExtra(EXTRA_ID, id))
        }
    }
}
