package ir.sobhanbeton.fleet.ui.inspections

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.InspectionCatalog
import ir.sobhanbeton.fleet.data.InspectionEntity
import ir.sobhanbeton.fleet.data.InspectionJson
import ir.sobhanbeton.fleet.data.InspectionPartResult
import ir.sobhanbeton.fleet.data.PartStatus
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.databinding.ActivityInspectionFormBinding
import ir.sobhanbeton.fleet.util.AnimUtils
import ir.sobhanbeton.fleet.util.NetworkMonitor
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Weekly visual inspection, carried over from the security app: tap a part on the vehicle
 * diagram once for «نیاز به بررسی» (yellow), twice for «خراب» (red), a third time back to
 * «سالم»; long-press jumps straight to red. Anything last week's inspection left open (not
 * marked repaired) gets a reminder ring so it isn't mistaken for a fresh defect. With
 * [EXTRA_INSPECTION_ID] it corrects an existing inspection instead. Expert only.
 */
class InspectionFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInspectionFormBinding
    private lateinit var repository: FleetRepository
    private var vehicle: VehicleEntity? = null
    private var editingInspectionId: String? = null

    /** Part name currently waiting on a photo pick from [pickPhoto]. */
    private var pendingPhotoFor: String? = null
    private var pendingPhotoNoteDraft: String = ""

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = pendingPhotoFor ?: return@registerForActivityResult
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            binding.diagramView.setNoteAndPhoto(name, pendingPhotoNoteDraft.trim().ifEmpty { null }, uri.toString())
        }
        pendingPhotoFor = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        val vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID) ?: run { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityInspectionFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        editingInspectionId = intent.getStringExtra(EXTRA_INSPECTION_ID)
        if (editingInspectionId != null) {
            binding.toolbar.title = getString(R.string.inspection_correction_title)
            binding.btnSubmit.text = getString(R.string.inspection_correction_submit)
        }

        binding.diagramView.onStatusChanged = { _, _ -> updateSummary() }
        binding.diagramView.onNoteBadgeTapped = { name -> showNoteDialog(name) }
        binding.btnSubmit.setOnClickListener { submit() }
        binding.btnSubmit.isEnabled = false // until the form is built
        AnimUtils.applyPressFeedback(binding.btnSubmit)

        lifecycleScope.launch {
            val loaded = repository.getVehicle(vehicleId)
            if (loaded == null) {
                Toast.makeText(this@InspectionFormActivity, R.string.error_generic, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            vehicle = loaded
            buildForm(loaded)
        }
        observeConnectivity()
    }

    private fun observeConnectivity() {
        binding.tvOfflineBanner.visibility = if (NetworkMonitor.isOnline(applicationContext)) View.GONE else View.VISIBLE
        lifecycleScope.launch {
            NetworkMonitor.observe(applicationContext).drop(1).collect { online ->
                binding.tvOfflineBanner.visibility = if (online) View.GONE else View.VISIBLE
            }
        }
    }

    private suspend fun buildForm(v: VehicleEntity) {
        binding.tvSubtitle.text = getString(R.string.inspection_form_subtitle_format, v.plate, v.currentDriverName ?: "—").toPersianDigitsInString()
        val category = v.categoryEnum()

        val editingId = editingInspectionId
        val existing: InspectionEntity? = editingId?.let { repository.getInspection(it) }
        if (editingId != null && existing == null) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // "Still open" rings reflect the inspection BEFORE the one being filled/corrected.
        val previous = if (editingId != null) repository.previousInspection(v.id, editingId) else repository.latestInspection(v.id)
        val stillOpen = previous?.let {
            InspectionJson.parse(it.partsJson).filter { p -> p.status != PartStatus.OK && p.repairedAt == null }.map { p -> p.name }.toSet()
        }.orEmpty()

        val existingParts = existing?.let { InspectionJson.parse(it.partsJson) }.orEmpty()
        // A correction keeps the category the inspection was made under.
        val formCategory = existing?.categoryEnum() ?: category
        binding.diagramView.setup(formCategory, existingParts, stillOpen)
        binding.tvStillOpenHint.visibility = if (stillOpen.isNotEmpty()) View.VISIBLE else View.GONE
        buildNonSpatialChips(existingParts)
        existing?.notes?.let { binding.etNotes.setText(it) }
        updateSummary()
        binding.btnSubmit.isEnabled = true
    }

    private fun buildNonSpatialChips(existing: List<InspectionPartResult>) {
        val existingByName = existing.associateBy { it.name }
        binding.chipGroupNonSpatial.removeAllViews()
        for (name in InspectionCatalog.nonSpatialParts) {
            binding.chipGroupNonSpatial.addView(Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = existingByName[name]?.status == PartStatus.BAD
                setOnCheckedChangeListener { _, _ -> updateSummary() }
            })
        }
    }

    private fun nonSpatialResults(): List<InspectionPartResult> =
        (0 until binding.chipGroupNonSpatial.childCount).map { i ->
            val chip = binding.chipGroupNonSpatial.getChildAt(i) as Chip
            InspectionPartResult(name = chip.text.toString(), status = if (chip.isChecked) PartStatus.BAD else PartStatus.OK)
        }

    private fun allResults(): List<InspectionPartResult> = binding.diagramView.currentResults() + nonSpatialResults()

    private fun updateSummary() {
        val all = allResults()
        val approved = all.count { it.ok }
        binding.tvSummary.text = getString(R.string.inspection_summary_format, approved.toString(), (all.size - approved).toString())
            .toPersianDigitsInString()
    }

    /** Optional description and/or photo for a part marked yellow/red. */
    private fun showNoteDialog(partName: String) {
        val current = binding.diagramView.currentResults().find { it.name == partName }
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(current?.note.orEmpty())
            hint = getString(R.string.inspection_note_hint)
        }
        val wrapper = com.google.android.material.textfield.TextInputLayout(this).apply { addView(input) }
        val padding = (16 * resources.displayMetrics.density).toInt()
        wrapper.setPadding(padding, padding, padding, 0)
        val hasPhoto = !current?.photoUri.isNullOrBlank()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.inspection_note_dialog_title)
            .setView(wrapper)
            .setNeutralButton(if (hasPhoto) R.string.inspection_photo_attached else R.string.inspection_attach_photo) { _, _ ->
                pendingPhotoFor = partName
                pendingPhotoNoteDraft = input.text?.toString().orEmpty()
                pickPhoto.launch(arrayOf("image/*"))
            }
            .setPositiveButton(R.string.inspection_save_note) { _, _ ->
                binding.diagramView.setNoteAndPhoto(partName, input.text?.toString()?.trim()?.ifEmpty { null }, current?.photoUri)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun submit() {
        val v = vehicle ?: return
        val parts = allResults()
        val notes = binding.etNotes.text?.toString()
        binding.btnSubmit.isEnabled = false
        lifecycleScope.launch {
            val editingId = editingInspectionId
            val result = if (editingId != null) repository.correctInspection(editingId, parts, notes)
            else repository.submitInspection(v.id, parts, notes)
            binding.btnSubmit.isEnabled = true
            result.onSuccess {
                Toast.makeText(
                    this@InspectionFormActivity,
                    if (editingId != null) R.string.inspection_correction_success else R.string.inspection_submit_success,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }.onFailure { error ->
                Toast.makeText(this@InspectionFormActivity, error.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val EXTRA_VEHICLE_ID = "vehicle_id"
        private const val EXTRA_INSPECTION_ID = "inspection_id"

        fun start(context: Context, vehicleId: String, correctInspectionId: String? = null) {
            context.startActivity(
                Intent(context, InspectionFormActivity::class.java)
                    .putExtra(EXTRA_VEHICLE_ID, vehicleId)
                    .putExtra(EXTRA_INSPECTION_ID, correctInspectionId)
            )
        }
    }
}
