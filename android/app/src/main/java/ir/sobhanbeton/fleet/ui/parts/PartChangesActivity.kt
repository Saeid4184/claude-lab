package ir.sobhanbeton.fleet.ui.parts

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
import ir.sobhanbeton.fleet.data.PartChangeEntity
import ir.sobhanbeton.fleet.data.PartKind
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.WheelPositions
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

/** "لاستیک و باتری": every recorded tyre/battery replacement, filterable by kind and searchable
 *  by plate; Excel export of a date range. Each vehicle's per-wheel picture is on its own screen. */
class PartChangesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter { row -> if (Session.canEdit()) PartChangeFormActivity.start(this, changeId = row.id) }
    private var changes: List<PartChangeEntity> = emptyList()
    private var filter: PartKind? = null
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_parts)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        (listOf<Pair<String, PartKind?>>(getString(R.string.filter_all) to null) + PartKind.values().map { it.displayName to it })
            .forEachIndexed { index, (label, k) ->
                binding.chipGroup.addView(Chip(this).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                    isChecked = index == 0
                    setOnClickListener { filter = k; render() }
                })
            }
        binding.etSearch.doAfterTextChanged { query = it?.toString().orEmpty().trim(); render() }
        if (Session.canEdit()) {
            binding.fabAdd.visibility = View.VISIBLE
            binding.fabAdd.text = getString(R.string.part_change_add)
            binding.fabAdd.setOnClickListener { PartChangeFormActivity.start(this) }
        }
        repository.partChanges().observe(this) { changes = it; render() }
    }

    private fun render() {
        val q = query.toPersianDigitsInString()
        val visible = changes.filter { c ->
            (filter == null || c.kindEnum() == filter) &&
                (query.isEmpty() || c.vehiclePlate.contains(query, ignoreCase = true) || c.vehiclePlate.toPersianDigitsInString().contains(q))
        }
        adapter.submitList(visible.map { row(it) })
        binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(if (changes.isEmpty()) R.string.part_changes_empty else R.string.list_no_match)
        val tires = changes.filter { it.kindEnum() == PartKind.TIRE }
        binding.tvSummary.text = getString(
            R.string.part_summary, tires.size, tires.sumOf { it.wheelCodes().size }, changes.count { it.kindEnum() == PartKind.BATTERY }
        ).toPersianDigitsInString()
    }

    private fun row(c: PartChangeEntity): FleetRow.Entry {
        val kind = c.kindEnum()
        return FleetRow.Entry(
            id = c.id,
            title = c.vehiclePlate.toPersianDigitsInString(),
            subtitle = listOfNotNull(
                FleetFormat.date(c.date),
                c.wheelCodes().takeIf { it.isNotEmpty() }?.joinToString("، ") { WheelPositions.label(it) },
                c.notes
            ).joinToString(" · ").toPersianDigitsInString(),
            iconRes = R.drawable.ic_tire,
            accentRes = R.color.accent_machinery,
            accentBgRes = R.color.accent_machinery_bg,
            badgeText = kind.displayName,
            badgeColorRes = if (kind == PartKind.TIRE) R.color.concrete_300 else R.color.safety_amber_dark
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
            JalaliDateRangePickerDialog.show(this, now - TimeUnit.DAYS.toMillis(365), now) { start, end ->
                lifecycleScope.launch { export(start, end + TimeUnit.DAYS.toMillis(1) - 1) }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private suspend fun export(from: Long, to: Long) {
        val list = repository.partChangesInRange(from, to)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.part_changes_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        ExcelExport.exportAndShare(
            this, "tyres-batteries",
            listOf(
                XlsxWriter.Sheet(
                    getString(R.string.home_parts),
                    listOf("تاریخ", "پلاک", "نوع", "چرخ‌ها", "تعداد حلقه", "توضیحات", "ثبت‌کننده"),
                    list.map { c ->
                        listOf(
                            JalaliCalendar.formatDate(c.date), c.vehiclePlate, c.kindEnum().displayName,
                            c.wheelCodes().joinToString("، ") { WheelPositions.label(it) },
                            if (c.kindEnum() == PartKind.TIRE) c.wheelCodes().size.toString() else "",
                            c.notes.orEmpty(), c.performedByName.orEmpty()
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
