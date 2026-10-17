package ir.sobhanbeton.fleet.ui.work

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.DailyWorkEntity
import ir.sobhanbeton.fleet.data.DailyWorkStats
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.VehicleStatus
import ir.sobhanbeton.fleet.data.WorkMetrics
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.ui.common.JalaliDatePickerDialog
import ir.sobhanbeton.fleet.ui.drivers.DriverDetailActivity
import ir.sobhanbeton.fleet.ui.fuel.FuelFormActivity
import ir.sobhanbeton.fleet.ui.vehicles.VehicleDetailActivity
import ir.sobhanbeton.fleet.util.ExcelExport
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.XlsxWriter
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/**
 * "کارکرد روزانه": for a chosen day, every vehicle with what it did (or «ثبت نشده»); and for a
 * date range, totals per vehicle and per driver — the basis for comparing performance. Excel
 * export gives the detail plus both summaries.
 */
class DailyWorkActivity : AppCompatActivity() {

    private enum class Tab { DAY, BY_VEHICLE, BY_DRIVER }

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter(::onRowClicked)
    private var tab = Tab.DAY
    private var query = ""

    private var day = System.currentTimeMillis()
    private var rangeStart = startOfJalaliMonth()
    private var rangeEnd = System.currentTimeMillis()
    private var vehicles: List<VehicleEntity> = emptyList()
    private var dayRecords: List<DailyWorkEntity> = emptyList()
    private var rangeRecords: List<DailyWorkEntity> = emptyList()
    private var daySource: LiveData<List<DailyWorkEntity>>? = null
    private var rangeSource: LiveData<List<DailyWorkEntity>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_daily_work)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        listOf(
            getString(R.string.work_tab_day) to Tab.DAY,
            getString(R.string.work_tab_by_vehicle) to Tab.BY_VEHICLE,
            getString(R.string.work_tab_by_driver) to Tab.BY_DRIVER
        ).forEachIndexed { index, (label, t) ->
            binding.chipGroup.addView(Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = index == 0
                setOnClickListener { tab = t; render(); invalidateOptionsMenu() }
            })
        }
        binding.etSearch.doAfterTextChanged { query = it?.toString().orEmpty().trim(); render() }
        if (Session.canEdit()) {
            binding.fabAdd.visibility = View.VISIBLE
            binding.fabAdd.text = getString(R.string.daily_work_add)
            binding.fabAdd.setOnClickListener { DailyWorkFormActivity.start(this, null, day) }
        }

        repository.vehicles().observe(this) { vehicles = it; render() }
        observeDay()
        observeRange()
    }

    private fun observeDay() {
        daySource?.removeObservers(this)
        daySource = repository.dailyWorkForDay(DailyWorkStats.dayKey(day)).also { it.observe(this) { list -> dayRecords = list; render() } }
    }

    private fun observeRange() {
        rangeSource?.removeObservers(this)
        rangeSource = repository.dailyWorkInRange(DailyWorkStats.dayKey(rangeStart), DailyWorkStats.dayKey(rangeEnd))
            .also { it.observe(this) { list -> rangeRecords = list; render() } }
    }

    private fun render() {
        val rows = mutableListOf<FleetRow>()
        when (tab) {
            Tab.DAY -> {
                val byVehicle = dayRecords.associateBy { it.vehicleId }
                vehicles.filter { it.statusEnum() != VehicleStatus.OUT_OF_SERVICE || byVehicle.containsKey(it.id) }
                    .filter { query.isEmpty() || it.plate.contains(query) || it.currentDriverName?.contains(query) == true }
                    .groupBy { it.categoryEnum() }.toSortedMap(compareBy { it.ordinal })
                    .forEach { (category, list) ->
                        val done = list.count { byVehicle.containsKey(it.id) }
                        rows += FleetRow.Header("${category.displayName} ($done/${list.size})".toPersianDigitsInString())
                        list.sortedBy { it.plate }.forEach { v ->
                            val r = byVehicle[v.id]
                            rows += FleetRow.Entry(
                                id = "day:${v.id}",
                                title = v.plate.toPersianDigitsInString(),
                                subtitle = listOfNotNull(r?.driverName ?: v.currentDriverName, r?.notes).joinToString(" · ").ifEmpty { FleetFormat.EMPTY },
                                iconRes = R.drawable.ic_calendar,
                                accentRes = FleetFormat.categoryAccent(category),
                                accentBgRes = FleetFormat.categoryAccentBg(category),
                                badgeText = r?.let { metricsText(category, it.trips, it.concreteM3, it.mineTrips, it.debrisTrips) } ?: getString(R.string.work_not_recorded),
                                badgeColorRes = if (r != null) R.color.status_green else R.color.concrete_300
                            )
                        }
                    }
                binding.tvSummary.text = getString(R.string.work_day_summary, FleetFormat.date(day), dayRecords.size).toPersianDigitsInString()
            }
            Tab.BY_VEHICLE -> {
                val vehiclesById = vehicles.associateBy { it.id }
                DailyWorkStats.byVehicle(rangeRecords)
                    .filter { query.isEmpty() || it.label.contains(query) }
                    .groupBy { (vehiclesById[it.key]?.categoryEnum() ?: categoryOf(it.key)) }
                    .toSortedMap(compareBy { it.ordinal })
                    .forEach { (category, list) ->
                        rows += FleetRow.Header(category.displayName)
                        list.forEach { t ->
                            rows += FleetRow.Entry(
                                id = "vehicle:${t.key}",
                                title = t.label.toPersianDigitsInString(),
                                subtitle = getString(R.string.work_days_count, t.days).toPersianDigitsInString(),
                                iconRes = R.drawable.ic_machinery,
                                accentRes = FleetFormat.categoryAccent(category),
                                accentBgRes = FleetFormat.categoryAccentBg(category),
                                badgeText = metricsText(category, t.trips, t.concreteM3, t.mineTrips, t.debrisTrips),
                                badgeColorRes = R.color.concrete_300
                            )
                        }
                    }
                binding.tvSummary.text = rangeSummary()
            }
            Tab.BY_DRIVER -> {
                DailyWorkStats.byDriver(rangeRecords).filter { query.isEmpty() || it.label.contains(query) }.forEach { t ->
                    // A driver's records can span vehicle types; show every metric that has a value.
                    rows += FleetRow.Entry(
                        id = "driver:${t.key}",
                        title = t.label.ifEmpty { getString(R.string.no_driver) },
                        subtitle = getString(R.string.work_days_count, t.days).toPersianDigitsInString(),
                        iconRes = R.drawable.ic_driver,
                        accentRes = R.color.accent_driver,
                        accentBgRes = R.color.accent_driver_bg,
                        badgeText = allMetricsText(t.trips, t.concreteM3, t.mineTrips, t.debrisTrips),
                        badgeColorRes = R.color.concrete_300
                    )
                }
                binding.tvSummary.text = rangeSummary()
            }
        }
        adapter.submitList(rows)
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(if (tab == Tab.DAY) R.string.vehicles_empty else R.string.work_range_empty)
    }

    private fun categoryOf(vehicleId: String): VehicleCategory =
        rangeRecords.firstOrNull { it.vehicleId == vehicleId }?.categoryEnum() ?: VehicleCategory.LOGISTICS

    private fun rangeSummary(): String {
        val t = DailyWorkStats.byVehicle(rangeRecords)
        return getString(
            R.string.work_range_summary, FleetFormat.date(rangeStart), FleetFormat.date(rangeEnd),
            FleetFormat.decimal(t.sumOf { it.concreteM3 }), t.sumOf { it.trips }, t.sumOf { it.mineTrips }, t.sumOf { it.debrisTrips }
        ).toPersianDigitsInString()
    }

    /** Only the metrics that apply to [category], e.g. "۸ سرویس · ۶۴ م³". */
    private fun metricsText(category: VehicleCategory, trips: Int?, m3: Double?, mine: Int?, debris: Int?): String =
        WorkMetrics.fieldsFor(category).mapNotNull { f ->
            when (f) {
                WorkMetrics.Field.TRIPS -> trips?.let { getString(R.string.metric_trips, it) }
                WorkMetrics.Field.CONCRETE_M3 -> m3?.let { getString(R.string.metric_m3, FleetFormat.decimal(it)) }
                WorkMetrics.Field.MINE_TRIPS -> mine?.let { getString(R.string.metric_mine, it) }
                WorkMetrics.Field.DEBRIS_TRIPS -> debris?.let { getString(R.string.metric_debris, it) }
            }
        }.joinToString(" · ").ifEmpty { FleetFormat.EMPTY }.toPersianDigitsInString()

    private fun allMetricsText(trips: Int, m3: Double, mine: Int, debris: Int): String = listOfNotNull(
        trips.takeIf { it > 0 }?.let { getString(R.string.metric_trips, it) },
        m3.takeIf { it > 0 }?.let { getString(R.string.metric_m3, FleetFormat.decimal(it)) },
        mine.takeIf { it > 0 }?.let { getString(R.string.metric_mine, it) },
        debris.takeIf { it > 0 }?.let { getString(R.string.metric_debris, it) }
    ).joinToString(" · ").ifEmpty { FleetFormat.EMPTY }.toPersianDigitsInString()

    private fun onRowClicked(row: FleetRow.Entry) {
        val (kind, id) = row.id.split(":", limit = 2)
        when (kind) {
            "day" -> if (Session.canEdit()) DailyWorkFormActivity.start(this, id, day) else VehicleDetailActivity.start(this, id)
            "vehicle" -> VehicleDetailActivity.start(this, id)
            "driver" -> if (id.isNotEmpty()) DriverDetailActivity.start(this, id)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_DATE, Menu.NONE, if (tab == Tab.DAY) R.string.work_choose_day else R.string.choose_range)
            .setIcon(R.drawable.ic_calendar)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.export_excel)
            .setIcon(R.drawable.ic_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_DATE -> {
            if (tab == Tab.DAY) {
                JalaliDatePickerDialog.show(this, getString(R.string.work_choose_day), day, onPicked = { day = it; observeDay() })
            } else {
                JalaliDateRangePickerDialog.show(this, rangeStart, rangeEnd) { start, end -> rangeStart = start; rangeEnd = end; observeRange() }
            }
            true
        }
        MENU_EXPORT -> {
            JalaliDateRangePickerDialog.show(this, rangeStart, rangeEnd) { start, end ->
                lifecycleScope.launch { export(start, end) }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private suspend fun export(from: Long, to: Long) {
        val records = repository.dailyWorkInRangeOnce(DailyWorkStats.dayKey(from), DailyWorkStats.dayKey(to))
        if (records.isEmpty()) {
            Toast.makeText(this, R.string.work_range_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val headers = listOf("سرویس", "متراژ بتن (م³)", "سرویس معدن", "سرویس نخاله")
        fun metrics(trips: Int?, m3: Double?, mine: Int?, debris: Int?) =
            listOf(trips?.toString().orEmpty(), m3?.let { FuelFormActivity.formatLiters(it) }.orEmpty(), mine?.toString().orEmpty(), debris?.toString().orEmpty())
        val detail = XlsxWriter.Sheet(
            getString(R.string.work_sheet_detail),
            listOf("تاریخ", "پلاک", "دسته", "راننده") + headers + listOf("توضیحات", "ثبت‌کننده"),
            records.map { r ->
                listOf(JalaliCalendar.formatDate(r.date), r.vehiclePlate, r.categoryEnum().displayName, r.driverName.orEmpty()) +
                    metrics(r.trips, r.concreteM3, r.mineTrips, r.debrisTrips) + listOf(r.notes.orEmpty(), r.performedByName.orEmpty())
            }
        )
        val perVehicle = XlsxWriter.Sheet(
            getString(R.string.work_tab_by_vehicle),
            listOf("پلاک", "روزهای کار") + headers,
            DailyWorkStats.byVehicle(records).map { t -> listOf(t.label, t.days.toString()) + metrics(t.trips, t.concreteM3, t.mineTrips, t.debrisTrips) }
        )
        val perDriver = XlsxWriter.Sheet(
            getString(R.string.work_tab_by_driver),
            listOf("راننده", "روزهای کار") + headers,
            DailyWorkStats.byDriver(records).map { t -> listOf(t.label.ifEmpty { "—" }, t.days.toString()) + metrics(t.trips, t.concreteM3, t.mineTrips, t.debrisTrips) }
        )
        ExcelExport.exportAndShare(this, "daily_work", listOf(detail, perVehicle, perDriver))
    }

    private companion object {
        const val MENU_DATE = 1
        const val MENU_EXPORT = 2

        fun startOfJalaliMonth(): Long {
            val today = JalaliCalendar.toJalali(System.currentTimeMillis())
            return JalaliCalendar.jalaliToMillis(today.year, today.month, 1)
        }
    }
}
