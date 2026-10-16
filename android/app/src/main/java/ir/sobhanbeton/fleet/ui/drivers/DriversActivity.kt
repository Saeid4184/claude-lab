package ir.sobhanbeton.fleet.ui.drivers

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.DriverStatus
import ir.sobhanbeton.fleet.data.ExpiryAlerts
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.util.toPersianDigitsInString

/** Drivers grouped the way the factory already thinks of them — by the kind of vehicle they
 *  drive (mixer / pump / dump truck / loader …, from their current assignment), then
 *  unassigned, then those who've left. */
class DriversActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter { row -> DriverDetailActivity.start(this, row.id) }

    private var drivers: List<DriverEntity> = emptyList()
    private var vehiclesById: Map<String, VehicleEntity> = emptyMap()
    private var statusFilter: DriverStatus? = null
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_drivers)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        val filters = listOf<Pair<String, DriverStatus?>>(getString(R.string.filter_all) to null) +
            DriverStatus.values().map { it.displayName to it }
        filters.forEachIndexed { index, (label, status) ->
            binding.chipGroup.addView(Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = index == 0
                setOnClickListener { statusFilter = status; render() }
            })
        }
        binding.etSearch.doAfterTextChanged { query = it?.toString().orEmpty().trim(); render() }

        if (Session.canEdit()) {
            binding.fabAdd.visibility = View.VISIBLE
            binding.fabAdd.text = getString(R.string.driver_add)
            binding.fabAdd.setOnClickListener { DriverFormActivity.start(this, null) }
        }

        repository.vehicles().observe(this) { list ->
            vehiclesById = list.associateBy { it.id }
            render()
        }
        repository.drivers().observe(this) { list ->
            drivers = list
            render()
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val visible = drivers.filter { d ->
            (statusFilter == null || d.statusEnum() == statusFilter) &&
                (query.isEmpty() || listOfNotNull(d.name, d.phone, d.currentVehiclePlate).any { it.contains(query, ignoreCase = true) })
        }

        // Group key: the category of their current vehicle; null = unassigned; LEFT last.
        val groups = visible.groupBy { d ->
            when {
                d.statusEnum() == DriverStatus.LEFT -> GROUP_LEFT
                else -> d.currentVehicleId?.let { vehiclesById[it]?.categoryEnum()?.ordinal } ?: GROUP_UNASSIGNED
            }
        }.toSortedMap()

        val rows = mutableListOf<FleetRow>()
        groups.forEach { (group, list) ->
            val title = when (group) {
                GROUP_UNASSIGNED -> getString(R.string.drivers_group_unassigned)
                GROUP_LEFT -> getString(R.string.drivers_group_left)
                else -> getString(R.string.drivers_group_category, VehicleCategory.values()[group].displayName)
            }
            rows += FleetRow.Header("$title (${list.size})".toPersianDigitsInString())
            list.sortedBy { it.name }.forEach { rows += toRow(it, now) }
        }
        adapter.submitList(rows)

        binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(
            if (drivers.isEmpty()) {
                if (Session.canEdit()) R.string.drivers_empty_expert else R.string.drivers_empty
            } else R.string.list_no_match
        )
        val active = drivers.count { it.statusEnum() == DriverStatus.ACTIVE }
        val unassigned = drivers.count { it.statusEnum() == DriverStatus.ACTIVE && it.currentVehicleId == null }
        binding.tvSummary.text = getString(R.string.drivers_summary, drivers.size, active, unassigned).toPersianDigitsInString()
    }

    private fun toRow(d: DriverEntity, now: Long): FleetRow.Entry {
        val status = d.statusEnum()
        val vehicle = d.currentVehicleId?.let { vehiclesById[it] }
        val subtitle = listOfNotNull(
            d.currentVehiclePlate?.let { plate -> getString(R.string.vehicle_prefix, plate) + (vehicle?.groupLabel?.let { " ($it)" } ?: "") }
                ?: getString(R.string.no_vehicle),
            d.phone
        ).joinToString(" · ").toPersianDigitsInString()
        val alert = ExpiryAlerts.build(emptyList(), listOf(d), now).firstOrNull()
        return FleetRow.Entry(
            id = d.id,
            title = d.name,
            subtitle = subtitle,
            iconRes = R.drawable.ic_driver,
            accentRes = R.color.accent_driver,
            accentBgRes = R.color.accent_driver_bg,
            badgeText = status.takeIf { it != DriverStatus.ACTIVE }?.displayName,
            badgeColorRes = FleetFormat.statusColor(status),
            warning = alert?.let { "⚠ ${it.documentLabel}: ${FleetFormat.daysLeft(it.daysLeft)}" }
        )
    }

    private companion object {
        const val GROUP_UNASSIGNED = 100
        const val GROUP_LEFT = 101
    }
}
