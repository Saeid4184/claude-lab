package ir.sobhanbeton.fleet.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.BulkImport
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.databinding.ActivityBulkImportBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/** "ورود گروهی": paste the existing roster once (see [BulkImport] for the format), check it,
 *  then import. Expert only. */
class BulkImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBulkImportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        binding = ActivityBulkImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = getString(R.string.bulk_import_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnImport.setOnClickListener { check() }
    }

    private fun check() {
        val parsed = BulkImport.parse(binding.etImport.text?.toString().orEmpty())
        if (parsed.rows.isEmpty() && parsed.errors.isEmpty()) {
            binding.etImport.error = getString(R.string.bulk_import_empty)
            return
        }
        val vehicles = parsed.rows.count { it.plate != null }
        val drivers = parsed.rows.mapNotNull { it.driverName?.let(BulkImport::nameKey) }.distinct().size
        val message = buildString {
            append(getString(R.string.bulk_import_preview, parsed.rows.size, vehicles, drivers))
            if (parsed.errors.isNotEmpty()) {
                append("\n\n")
                append(getString(R.string.bulk_import_errors, parsed.errors.size))
                append("\n")
                append(parsed.errors.take(10).joinToString("\n"))
            }
        }.toPersianDigitsInString()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bulk_import_title)
            .setMessage(message)
            .setNegativeButton(R.string.btn_cancel, null)
        if (parsed.rows.isNotEmpty()) dialog.setPositiveButton(R.string.bulk_import_run) { _, _ -> run(parsed.rows) }
        dialog.show()
    }

    private fun run(rows: List<BulkImport.Row>) {
        setLoading(true)
        lifecycleScope.launch {
            val result = FleetRepository.get(this@BulkImportActivity).bulkImport(rows)
            setLoading(false)
            reportSave(result) { s ->
                MaterialAlertDialogBuilder(this@BulkImportActivity)
                    .setTitle(R.string.bulk_import_done)
                    .setMessage(
                        getString(
                            R.string.bulk_import_summary,
                            s.vehiclesCreated, s.vehiclesExisting, s.driversCreated, s.driversExisting, s.assignmentsCreated
                        ).toPersianDigitsInString()
                    )
                    .setPositiveButton(R.string.btn_ok) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnImport.isEnabled = !loading
        binding.etImport.isEnabled = !loading
    }
}
