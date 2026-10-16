package ir.sobhanbeton.fleet.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.ExpiryAlerts
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.ui.drivers.DriverDetailActivity
import ir.sobhanbeton.fleet.ui.vehicles.VehicleDetailActivity
import ir.sobhanbeton.fleet.util.toPersianDigitsInString

/** "مدارک در حال انقضا": every vehicle/driver document already expired or expiring within
 *  [ExpiryAlerts.WARNING_DAYS] days, most urgent first. Tap to open the vehicle/driver. */
class ExpiringDocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetListBinding
    private var vehicles: List<VehicleEntity> = emptyList()
    private var drivers: List<DriverEntity> = emptyList()
    private val adapter = FleetListAdapter { row ->
        val (type, id) = row.id.split(":", limit = 3)
        if (type == ExpiryAlerts.SubjectType.VEHICLE.name) VehicleDetailActivity.start(this, id)
        else DriverDetailActivity.start(this, id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.expiring_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tilSearch.visibility = View.GONE
        binding.chipScroller.visibility = View.GONE

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        val repository = FleetRepository.get(this)
        repository.vehicles().observe(this) { vehicles = it; render() }
        repository.drivers().observe(this) { drivers = it; render() }
    }

    private fun render() {
        val items = ExpiryAlerts.build(vehicles, drivers)
        val rows = mutableListOf<FleetRow>()
        val (expired, upcoming) = items.partition { it.daysLeft < 0 }
        if (expired.isNotEmpty()) {
            rows += FleetRow.Header(getString(R.string.expiring_group_expired, expired.size).toPersianDigitsInString())
            expired.forEach { rows += toRow(it) }
        }
        if (upcoming.isNotEmpty()) {
            rows += FleetRow.Header(getString(R.string.expiring_group_upcoming, ExpiryAlerts.WARNING_DAYS, upcoming.size).toPersianDigitsInString())
            upcoming.forEach { rows += toRow(it) }
        }
        adapter.submitList(rows)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(R.string.expiring_empty, ExpiryAlerts.WARNING_DAYS).toPersianDigitsInString()
        binding.tvSummary.visibility = View.GONE
    }

    private fun toRow(item: ExpiryAlerts.Item): FleetRow.Entry {
        val isVehicle = item.subjectType == ExpiryAlerts.SubjectType.VEHICLE
        return FleetRow.Entry(
            // Same vehicle/driver can have several documents — the label keeps row keys unique.
            id = "${item.subjectType.name}:${item.subjectId}:${item.documentLabel}",
            title = item.documentLabel,
            subtitle = "${item.subjectLabel} · ${FleetFormat.date(item.expiresAt)}".toPersianDigitsInString(),
            iconRes = if (isVehicle) R.drawable.ic_machinery else R.drawable.ic_driver,
            accentRes = if (isVehicle) R.color.accent_machinery else R.color.accent_driver,
            accentBgRes = if (isVehicle) R.color.accent_machinery_bg else R.color.accent_driver_bg,
            badgeText = FleetFormat.daysLeft(item.daysLeft),
            badgeColorRes = FleetFormat.expiryColor(item.daysLeft)
        )
    }
}
