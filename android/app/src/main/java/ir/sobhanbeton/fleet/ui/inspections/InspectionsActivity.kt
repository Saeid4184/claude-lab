package ir.sobhanbeton.fleet.ui.inspections

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.BulkImport
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.InspectionCatalog
import ir.sobhanbeton.fleet.data.InspectionEntity
import ir.sobhanbeton.fleet.data.InspectionJson
import ir.sobhanbeton.fleet.data.InspectionStats
import ir.sobhanbeton.fleet.data.PartStatus
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.VehicleStatus
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.ui.repairs.WorkOrderDetailActivity
import ir.sobhanbeton.fleet.ui.repairs.WorkOrderFormActivity
import ir.sobhanbeton.fleet.ui.vehicles.VehicleDetailActivity
import ir.sobhanbeton.fleet.util.ExcelExport
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.XlsxWriter
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * "بازدید ظاهری هفتگی": which vehicles still need this week's inspection, which are done, and
 * the fleet's open defects (each can become a repair order or be marked repaired). Excel export
 * mirrors the security team's workbook: one sheet per checklist, P/W/O per part.
 */
class InspectionsActivity : AppCompatActivity() {

    private enum class Tab { TODO, DONE, DEFECTS }

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter(::onRowClicked)

    private var tab = Tab.TODO
    private var query = ""
    private var vehicles: List<VehicleEntity> = emptyList()
    private var inspections: List<InspectionEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_inspections)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        listOf(
            getString(R.string.inspections_tab_todo) to Tab.TODO,
            getString(R.string.inspections_tab_done) to Tab.DONE,
            getString(R.string.inspections_tab_defects) to Tab.DEFECTS
        ).forEachIndexed { index, (label, t) ->
            binding.chipGroup.addView(Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = index == 0
                setOnClickListener { tab = t; render() }
            })
        }
        binding.etSearch.doAfterTextChanged { query = it?.toString().orEmpty().trim(); render() }

        repository.vehicles().observe(this) { vehicles = it; render() }
        repository.inspections().observe(this) { inspections = it; render() }
    }

    private fun matches(plate: String, group: String?): Boolean =
        query.isEmpty() || plate.contains(query) || group?.contains(query, ignoreCase = true) == true ||
            BulkImport.plateKey(plate).contains(BulkImport.plateKey(query).ifEmpty { "\u0000" })

    private fun render() {
        val weekStart = InspectionStats.startOfWeek()
        val latestByVehicle = inspections.groupBy { it.vehicleId }.mapValues { (_, l) -> l.maxByOrNull { it.timestamp }!! }
        val defects = InspectionStats.openDefects(inspections)
        // Out-of-service vehicles aren't inspected.
        val inService = vehicles.filter { it.statusEnum() != VehicleStatus.OUT_OF_SERVICE && matches(it.plate, it.groupLabel) }
        val (done, todo) = inService.partition { (latestByVehicle[it.id]?.timestamp ?: 0L) >= weekStart }

        val rows = mutableListOf<FleetRow>()
        when (tab) {
            Tab.TODO, Tab.DONE -> {
                val list = if (tab == Tab.TODO) todo else done
                list.groupBy { it.categoryEnum() }.toSortedMap(compareBy { it.ordinal }).forEach { (category, group) ->
                    rows += FleetRow.Header("${category.displayName} (${group.size})".toPersianDigitsInString())
                    group.sortedBy { it.plate }.forEach { v -> rows += vehicleRow(v, latestByVehicle[v.id]) }
                }
            }
            Tab.DEFECTS -> defects.filter { matches(it.vehiclePlate, null) }.forEach { d ->
                rows += FleetRow.Entry(
                    id = "defect:${d.inspectionId}:${d.partName}",
                    title = d.partName,
                    subtitle = listOfNotNull(
                        d.vehiclePlate,
                        getString(R.string.defect_since, FleetFormat.date(d.since)),
                        d.note
                    ).joinToString(" · ").toPersianDigitsInString(),
                    iconRes = R.drawable.ic_inspection,
                    accentRes = R.color.accent_inspection,
                    accentBgRes = R.color.accent_inspection_bg,
                    badgeText = getString(if (d.status == PartStatus.BAD) R.string.part_bad else R.string.inspection_status_warn),
                    badgeColorRes = if (d.status == PartStatus.BAD) R.color.danger_red else R.color.safety_amber_dark,
                    warning = if (d.recurring) getString(R.string.open_defects_recurring_badge) else null
                )
            }
        }
        adapter.submitList(rows)

        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(
            when (tab) {
                Tab.TODO -> if (inService.isEmpty()) R.string.vehicles_empty else R.string.inspections_all_done
                Tab.DONE -> R.string.inspections_none_done
                Tab.DEFECTS -> R.string.open_defects_empty
            }
        )
        binding.tvSummary.text = getString(R.string.inspections_summary, done.size, inService.size, defects.size).toPersianDigitsInString()
    }

    private fun vehicleRow(v: VehicleEntity, latest: InspectionEntity?): FleetRow.Entry {
        val category = v.categoryEnum()
        val last = latest?.let {
            getString(R.string.inspection_last_format, FleetFormat.date(it.timestamp), it.rejectedCount)
        } ?: getString(R.string.inspection_never_done)
        return FleetRow.Entry(
            id = "vehicle:${v.id}",
            title = v.plate.toPersianDigitsInString(),
            subtitle = listOfNotNull(v.groupLabel, last).joinToString(" · ").toPersianDigitsInString(),
            iconRes = R.drawable.ic_machinery,
            accentRes = FleetFormat.categoryAccent(category),
            accentBgRes = FleetFormat.categoryAccentBg(category),
            badgeText = latest?.takeIf { it.rejectedCount > 0 }?.let { getString(R.string.defects_count, it.rejectedCount).toPersianDigitsInString() },
            badgeColorRes = R.color.safety_amber_dark
        )
    }

    private fun onRowClicked(row: FleetRow.Entry) {
        val parts = row.id.split(":", limit = 3)
        when (parts[0]) {
            "vehicle" -> {
                val vehicleId = parts[1]
                if (!Session.canEdit()) {
                    VehicleDetailActivity.start(this, vehicleId)
                } else if (tab == Tab.DONE) {
                    // Done this week: open the latest for correction instead of a duplicate.
                    val latest = inspections.filter { it.vehicleId == vehicleId }.maxByOrNull { it.timestamp }
                    InspectionFormActivity.start(this, vehicleId, latest?.id)
                } else {
                    InspectionFormActivity.start(this, vehicleId)
                }
            }
            "defect" -> onDefectClicked(parts[1], parts[2])
        }
    }

    /** Expert: turn the defect into a repair order, or just mark it repaired. */
    private fun onDefectClicked(inspectionId: String, partName: String) {
        val inspection = inspections.firstOrNull { it.id == inspectionId } ?: return
        if (!Session.canEdit()) {
            VehicleDetailActivity.start(this, inspection.vehicleId)
            return
        }
        lifecycleScope.launch {
            val existingOrder = repository.openWorkOrderForDefect(inspectionId, partName)
            val options = mutableListOf<Pair<String, () -> Unit>>()
            if (existingOrder != null) {
                options += getString(R.string.defect_open_order) to {
                    WorkOrderDetailActivity.start(this@InspectionsActivity, existingOrder.id)
                }
            } else {
                options += getString(R.string.defect_create_order) to {
                    WorkOrderFormActivity.startFromDefect(this@InspectionsActivity, inspection.vehicleId, inspectionId, partName)
                }
            }
            options += getString(R.string.open_defects_mark_repaired) to {
                lifecycleScope.launch { reportSave(repository.markPartRepaired(inspectionId, partName)) }
                Unit
            }
            options += getString(R.string.defect_open_vehicle) to { VehicleDetailActivity.start(this@InspectionsActivity, inspection.vehicleId) }
            MaterialAlertDialogBuilder(this@InspectionsActivity)
                .setTitle("$partName — ${inspection.vehiclePlate}".toPersianDigitsInString())
                .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    // ------------------------------------------------------------------ Excel

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.export_excel)
            .setIcon(R.drawable.ic_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_EXPORT -> {
            val now = System.currentTimeMillis()
            JalaliDateRangePickerDialog.show(this, now - TimeUnit.DAYS.toMillis(30), now) { start, end ->
                lifecycleScope.launch { export(start, end + TimeUnit.DAYS.toMillis(1) - 1) }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private suspend fun export(from: Long, to: Long) {
        val inRange = repository.inspectionsInRange(from, to)
        if (inRange.isEmpty()) {
            Toast.makeText(this, R.string.inspection_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // One sheet per distinct checklist, same grouping as the security team's workbook.
        val sheets = VehicleCategory.values().groupBy { InspectionCatalog.sheetNameFor(it) }.mapNotNull { (sheetName, categories) ->
            val records = inRange.filter { it.categoryEnum() in categories }
            if (records.isEmpty()) return@mapNotNull null
            val partNames = InspectionCatalog.partsFor(categories.first())
            val headers = listOf("ردیف", "تاریخ", "پلاک", "گروه", "راننده") + partNames +
                listOf("سالم", "ایراد", "توضیحات", "بازدیدکننده")
            val rows = records.mapIndexed { index, r ->
                val byName = InspectionJson.parse(r.partsJson).associateBy { it.name }
                listOf((index + 1).toString(), JalaliCalendar.formatDate(r.timestamp), r.vehiclePlate, r.groupLabel.orEmpty(), r.driverName.orEmpty()) +
                    partNames.map { name ->
                        when (byName[name]?.status) {
                            PartStatus.OK, null -> "P"
                            PartStatus.WARN -> "W"
                            PartStatus.BAD -> "O"
                        }
                    } +
                    listOf(r.approvedCount.toString(), r.rejectedCount.toString(), r.notes.orEmpty(), r.performedByName.orEmpty())
            }
            XlsxWriter.Sheet(sheetName, headers, rows)
        }
        val legend = XlsxWriter.Sheet("راهنما", listOf("کد", "معنی"), listOf(listOf("P", "سالم"), listOf("W", "نیاز به بررسی"), listOf("O", "خراب")))
        ExcelExport.exportAndShare(this, "inspections", sheets + legend)
    }

    private companion object {
        const val MENU_EXPORT = 1
    }
}
