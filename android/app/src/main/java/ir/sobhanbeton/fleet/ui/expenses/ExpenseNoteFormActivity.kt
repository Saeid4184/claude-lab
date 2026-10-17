package ir.sobhanbeton.fleet.ui.expenses

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.ExpenseNoteEntity
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.databinding.ActivityExpenseNoteFormBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FormFields
import ir.sobhanbeton.fleet.ui.common.FormFields.longOrNull
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import kotlinx.coroutines.launch

/** Add/edit one private expense note: date, what for, amount, optional note. Expert only. */
class ExpenseNoteFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExpenseNoteFormBinding
    private lateinit var repository: FleetRepository
    private var existing: ExpenseNoteEntity? = null
    private lateinit var date: () -> Long?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityExpenseNoteFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val id = intent.getStringExtra(EXTRA_ID)
        supportActionBar?.title = getString(if (id == null) R.string.expense_add else R.string.expense_edit)
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.isEnabled = false // until the fields are bound

        lifecycleScope.launch {
            existing = id?.let { repository.getExpenseNote(it) }
            val e = existing
            date = FormFields.bindDateField(binding.tilDate, binding.etDate, e?.date ?: System.currentTimeMillis())
            binding.etTitle.setText(e?.title)
            binding.etAmount.setText(e?.amount?.toString())
            binding.etNotes.setText(e?.notes)
            binding.btnSave.isEnabled = true
        }
    }

    private fun save() {
        binding.tilTitle.error = null
        binding.tilAmount.error = null
        val title = binding.etTitle.textOrNull() ?: run { binding.tilTitle.error = getString(R.string.error_required); return }
        val amount = try { binding.etAmount.longOrNull() } catch (e: NumberFormatException) { null }
        if (amount == null || amount <= 0) {
            binding.tilAmount.error = getString(R.string.error_positive_number); return
        }
        val input = (existing ?: ExpenseNoteEntity()).copy(
            date = date() ?: System.currentTimeMillis(),
            title = title,
            amount = amount,
            notes = binding.etNotes.textOrNull()
        )
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val result = repository.saveExpenseNote(input)
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
                confirm(R.string.action_delete, getString(R.string.expense_delete_confirm), R.string.action_delete) {
                    lifecycleScope.launch { reportSave(repository.deleteExpenseNote(id)) { finish() } }
                }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_ID = "expense_id"
        private const val MENU_DELETE = 1

        fun start(context: Context, expenseId: String?) {
            context.startActivity(Intent(context, ExpenseNoteFormActivity::class.java).putExtra(EXTRA_ID, expenseId))
        }
    }
}
