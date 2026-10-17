package ir.sobhanbeton.fleet.ui.incidents

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
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.IncidentEntity
import ir.sobhanbeton.fleet.data.IncidentType
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.util.ExcelExport
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.XlsxWriter
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** "حوادث و تخلفات": every recorded accident, fine, violation and commendation, filterable by
 *  type and searchable; Excel export of a date range. */
class IncidentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter { row -> if (Session.canEdit()) IncidentFormActivity.start(this, incidentId = row.id) }
    private var incidents: List<IncidentEntity> = emptyList()
    private var filter: IncidentType? = null
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_incidents)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        (listOf<Pair<String, IncidentType?>>(getString(R.string.filter_all) to null) + IncidentType.values().map { it.displayName to it })
            .forEachIndexed { index, (label, t) ->
                binding.chipGroup.addView(Chip(this).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                    isChecked = index == 0
                    setOnClickListener { filter = t; render() }
                })
            }
        binding.etSearch.doAfterTextChanged { query = it?.toString().orEmpty().trim(); render() }
        if (Session.canEdit()) {
            binding.fabAdd.visibility = View.VISIBLE
            binding.fabAdd.text = getString(R.string.incident_add)
            binding.fabAdd.setOnClickListener { IncidentFormActivity.start(this) }
        }
        repository.incidents().observe(this) { incidents = it; render() }
    }

    private fun render() {
        val visible = incidents.filter { i ->
            (filter == null || i.typeEnum() == filter) &&
                (query.isEmpty() || listOfNotNull(i.vehiclePlate, i.driverName, i.subject, i.description).any { it.contains(query, ignoreCase = true) })
        }
        adapter.submitList(visible.map { i -> row(i) })
        binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(if (incidents.isEmpty()) R.string.incidents_empty else R.string.list_no_match)
        val byType = incidents.groupingBy { it.typeEnum() }.eachCount()
        binding.tvSummary.text = IncidentType.values().filter { (byType[it] ?: 0) > 0 }
            .joinToString(" · ") { "${it.displayName}: ${byType[it]}" }.ifEmpty { FleetFormat.EMPTY }.toPersianDigitsInString()
    }

    private fun row(i: IncidentEntity): FleetRow.Entry {
        val type = i.typeEnum()
        return FleetRow.Entry(
            id = i.id,
            title = listOfNotNull(i.subject, i.vehiclePlate ?: i.driverName).joinToString(" — ").toPersianDigitsInString(),
            subtitle = listOfNotNull(FleetFormat.date(i.occurredAt), i.driverName.takeIf { i.vehiclePlate != null }, i.severity, i.actionTaken)
                .joinToString(" · ").toPersianDigitsInString(),
            iconRes = R.drawable.ic_report,
            accentRes = R.color.accent_report,
            accentBgRes = R.color.accent_report_bg,
            badgeText = type.displayName,
            badgeColorRes = when (type) {
                IncidentType.ACCIDENT -> R.color.danger_red
                IncidentType.FINE, IncidentType.VIOLATION -> R.color.safety_amber_dark
                IncidentType.COMMENDATION -> R.color.status_green
                IncidentType.OTHER -> R.color.concrete_300
            }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.export_excel)
            .setIcon(R.drawable.ic_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_EXPORT -> {
            val now = System.currentTimeMillis()
            JalaliDateRangePickerDialog.show(this, now - TimeUnit.DAYS.toMillis(90), now) { start, end ->
                lifecycleScope.launch { export(start, end + TimeUnit.DAYS.toMillis(1) - 1) }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private suspend fun export(from: Long, to: Long) {
        val list = repository.incidentsInRange(from, to)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.incidents_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        ExcelExport.exportAndShare(
            this, "incidents",
            listOf(
                XlsxWriter.Sheet(
                    getString(R.string.home_incidents),
                    listOf("تاریخ", "نوع", "پلاک", "راننده", "موضوع", "شدت", "محل", "اقدام", "توضیحات", "خلاصه", "ثبت‌کننده"),
                    list.map { i ->
                        listOf(
                            JalaliCalendar.formatDate(i.occurredAt), i.typeEnum().displayName, i.vehiclePlate.orEmpty(), i.driverName.orEmpty(),
                            i.subject, i.severity.orEmpty(), i.location.orEmpty(), i.actionTaken.orEmpty(), i.description.orEmpty(),
                            i.summaryText, i.performedByName.orEmpty()
                        )
                    }
                )
            )
        )
    }

    private companion object {
        const val MENU_EXPORT = 1
    }
}
