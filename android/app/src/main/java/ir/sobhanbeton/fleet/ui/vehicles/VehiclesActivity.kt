package ir.sobhanbeton.fleet.ui.vehicles

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.BulkImport
import ir.sobhanbeton.fleet.data.ExpiryAlerts
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.VehicleStatus
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.BulkImportActivity
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.util.buildPlateSpannable
import ir.sobhanbeton.fleet.util.toPersianDigitsInString

/** The fleet roster: grouped by category, filterable by category or by non-active status, and
 *  searchable by plate, group, model or current driver. */
class VehiclesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter { row -> VehicleDetailActivity.start(this, row.id) }

    private var all: List<VehicleEntity> = emptyList()
    private var filter: Filter = Filter.All
    private var query: String = ""

    private sealed class Filter {
        object All : Filter()
        data class Category(val category: VehicleCategory) : Filter()
        data class Status(val status: VehicleStatus) : Filter()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_vehicles)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        setupChips()
        binding.etSearch.doAfterTextChanged { query = it?.toString().orEmpty().trim(); render() }

        if (Session.canEdit()) {
            binding.fabAdd.visibility = View.VISIBLE
            binding.fabAdd.text = getString(R.string.vehicle_add)
            binding.fabAdd.setOnClickListener { VehicleFormActivity.start(this, null) }
        }

        repository.vehicles().observe(this) { list ->
            all = list
            render()
        }
    }

    private fun setupChips() {
        val filters = listOf<Pair<String, Filter>>(getString(R.string.filter_all) to Filter.All) +
            VehicleCategory.values().map { it.displayName to Filter.Category(it) } +
            listOf(VehicleStatus.IN_REPAIR, VehicleStatus.OUT_OF_SERVICE).map { it.displayName to Filter.Status(it) }

        filters.forEachIndexed { index, (label, f) ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = index == 0
                setOnClickListener { filter = f; render() }
            }
            binding.chipGroup.addView(chip)
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val visible = all.filter { v ->
            when (val f = filter) {
                Filter.All -> true
                is Filter.Category -> v.categoryEnum() == f.category
                is Filter.Status -> v.statusEnum() == f.status
            } && matches(v)
        }

        val rows = mutableListOf<FleetRow>()
        visible.groupBy { it.categoryEnum() }
            .toSortedMap(compareBy { it.ordinal })
            .forEach { (category, vehicles) ->
                rows += FleetRow.Header("${category.displayName} (${vehicles.size})".toPersianDigitsInString())
                vehicles.sortedWith(compareBy({ it.groupLabel.orEmpty() }, { it.plate }))
                    .forEach { rows += toRow(it, now) }
            }
        adapter.submitList(rows)

        binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(
            if (all.isEmpty()) {
                if (Session.canEdit()) R.string.vehicles_empty_expert else R.string.vehicles_empty
            } else R.string.list_no_match
        )
        val byStatus = all.groupingBy { it.statusEnum() }.eachCount()
        binding.tvSummary.text = getString(
            R.string.vehicles_summary,
            all.size, byStatus[VehicleStatus.ACTIVE] ?: 0, byStatus[VehicleStatus.IN_REPAIR] ?: 0, byStatus[VehicleStatus.OUT_OF_SERVICE] ?: 0
        ).toPersianDigitsInString()
    }

    private fun matches(v: VehicleEntity): Boolean {
        if (query.isEmpty()) return true
        val digits = BulkImport.plateKey(query)
        if (digits.isNotEmpty() && BulkImport.plateKey(v.plate).contains(digits)) return true
        return listOfNotNull(v.plate, v.groupLabel, v.brandModel, v.currentDriverName)
            .any { it.contains(query, ignoreCase = true) }
    }

    private fun toRow(v: VehicleEntity, now: Long): FleetRow.Entry {
        val category = v.categoryEnum()
        val status = v.statusEnum()
        val driver = v.currentDriverName ?: getString(R.string.no_driver)
        val subtitle = listOfNotNull(v.groupLabel ?: v.brandModel, getString(R.string.driver_prefix, driver)).joinToString(" · ")
        val alert = ExpiryAlerts.build(listOf(v), emptyList(), now).firstOrNull()
        return FleetRow.Entry(
            id = v.id,
            title = buildPlateSpannable(this, v.plate.toPersianDigitsInString()),
            subtitle = subtitle,
            iconRes = R.drawable.ic_machinery,
            accentRes = FleetFormat.categoryAccent(category),
            accentBgRes = FleetFormat.categoryAccentBg(category),
            badgeText = status.takeIf { it != VehicleStatus.ACTIVE }?.displayName,
            badgeColorRes = FleetFormat.statusColor(status),
            warning = alert?.let { "⚠ ${it.documentLabel}: ${FleetFormat.daysLeft(it.daysLeft)}" }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Session.canEdit()) {
            menu.add(Menu.NONE, MENU_BULK_IMPORT, Menu.NONE, R.string.bulk_import_title)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_BULK_IMPORT -> {
            startActivity(Intent(this, BulkImportActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private companion object {
        const val MENU_BULK_IMPORT = 1
    }
}
