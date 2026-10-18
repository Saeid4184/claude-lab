package ir.sobhanbeton.fleet.ui.fuel

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.FuelLogEntity
import ir.sobhanbeton.fleet.data.FuelStats
import ir.sobhanbeton.fleet.data.MeterType
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.ui.vehicles.VehicleDetailActivity
import ir.sobhanbeton.fleet.util.ExcelExport
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.XlsxWriter
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * "سوخت": fuel given in a date range (default: this Jalali month), either fill by fill or
 * totalled per vehicle with its average consumption, plus — always on top — vehicles whose
 * recent consumption jumped above their own baseline. Exportable to Excel (manager too).
 */
class FuelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter(::onRowClicked)

    private var rangeStart = startOfJalaliMonth()
    private var rangeEnd = endOfToday()
    private var perVehicle = false
    private var query = ""

    private var logs: List<FuelLogEntity> = emptyList()
    private var anomalyLogs: List<FuelLogEntity> = emptyList()
    private var vehiclesById: Map<String, VehicleEntity> = emptyMap()
    private var rangeSource: LiveData<List<FuelLogEntity>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_fuel)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        listOf(getString(R.string.fuel_view_fills) to false, getString(R.string.fuel_view_per_vehicle) to true)
            .forEachIndexed { index, (label, mode) ->
                binding.chipGroup.addView(Chip(this).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                    isChecked = index == 0
                    setOnClickListener { perVehicle = mode; render() }
                })
            }
        binding.etSearch.doAfterTextChanged { query = it?.toString().orEmpty().trim(); render() }

        if (Session.canEdit()) {
            binding.fabAdd.visibility = View.VISIBLE
            binding.fabAdd.text = getString(R.string.fuel_add)
            binding.fabAdd.setOnClickListener { FuelFormActivity.start(this) }
        }

        repository.vehicles().observe(this) { list -> vehiclesById = list.associateBy { it.id }; render() }
        // Anomalies always look at the last RECENT+BASELINE days, whatever range is shown.
        val anomalyFrom = System.currentTimeMillis() - TimeUnit.DAYS.toMillis((FuelStats.RECENT_DAYS + FuelStats.BASELINE_DAYS).toLong())
        repository.fuelSince(anomalyFrom).observe(this) { anomalyLogs = it; render() }
        observeRange()
    }

    private fun observeRange() {
        rangeSource?.removeObservers(this)
        rangeSource = repository.fuelInRange(rangeStart, rangeEnd).also { source ->
            source.observe(this) { logs = it; render() }
        }
    }

    private fun render() {
        val visible = logs.filter { log ->
            query.isEmpty() || listOfNotNull(log.vehiclePlate, log.driverName, vehiclesById[log.vehicleId]?.groupLabel)
                .any { it.contains(query, ignoreCase = true) }
        }
        val rows = mutableListOf<FleetRow>()

        val anomalies = FuelStats.anomalies(anomalyLogs, vehiclesById.values.toList())
        if (anomalies.isNotEmpty()) {
            rows += FleetRow.Header(getString(R.string.fuel_anomalies_header, anomalies.size).toPersianDigitsInString())
            anomalies.forEach { a ->
                rows += FleetRow.Entry(
                    // Own prefix: the same vehicle can also appear in the per-vehicle list below.
                    id = "anomaly:${a.vehicleId}",
                    title = a.plate.toPersianDigitsInString(),
                    subtitle = getString(R.string.fuel_anomaly_detail, rate(a.recent), rate(a.baseline)).toPersianDigitsInString(),
                    iconRes = R.drawable.ic_inventory,
                    accentRes = R.color.danger_red,
                    accentBgRes = R.color.accent_report_bg,
                    badgeText = "+${a.increasePercent}٪".toPersianDigitsInString(),
                    badgeColorRes = R.color.danger_red
                )
            }
        }

        if (perVehicle) {
            val summaries = FuelStats.summarize(visible, vehiclesById)
            rows += FleetRow.Header(getString(R.string.fuel_per_vehicle_header, summaries.size).toPersianDigitsInString())
            summaries.forEach { s ->
                val vehicle = vehiclesById[s.vehicleId]
                rows += FleetRow.Entry(
                    id = "vehicle:${s.vehicleId}",
                    title = s.plate.toPersianDigitsInString(),
                    subtitle = listOfNotNull(
                        vehicle?.groupLabel,
                        getString(R.string.fuel_fills_count, s.fills),
                        s.consumption?.let(::rate) ?: getString(R.string.fuel_no_consumption)
                    ).joinToString(" · ").toPersianDigitsInString(),
                    iconRes = R.drawable.ic_inventory,
                    accentRes = R.color.accent_item_log,
                    accentBgRes = R.color.accent_item_log_bg,
                    badgeText = getString(R.string.liters_format, FuelFormActivity.formatLiters(s.liters)).toPersianDigitsInString(),
                    badgeColorRes = R.color.concrete_300
                )
            }
        } else {
            rows += FleetRow.Header(getString(R.string.fuel_fills_header, visible.size).toPersianDigitsInString())
            visible.forEach { log ->
                val unit = vehiclesById[log.vehicleId]?.meterTypeEnum()?.unit ?: MeterType.KM.unit
                rows += FleetRow.Entry(
                    id = "fuel:${log.id}",
                    title = log.vehiclePlate.toPersianDigitsInString(),
                    subtitle = listOfNotNull(
                        FleetFormat.date(log.timestamp),
                        log.driverName,
                        log.sourceEnum().displayName,
                        log.meterReading?.let { "${FleetFormat.number(it)} $unit" }
                    ).joinToString(" · ").toPersianDigitsInString(),
                    iconRes = R.drawable.ic_inventory,
                    accentRes = R.color.accent_item_log,
                    accentBgRes = R.color.accent_item_log_bg,
                    badgeText = getString(R.string.liters_format, FuelFormActivity.formatLiters(log.liters)).toPersianDigitsInString(),
                    badgeColorRes = R.color.concrete_300
                )
            }
        }
        adapter.submitList(rows)

        binding.tvEmpty.visibility = if (visible.isEmpty() && anomalies.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(if (logs.isEmpty()) R.string.fuel_empty else R.string.list_no_match)
        binding.tvSummary.text = buildString {
            append(getString(R.string.range_format, FleetFormat.date(rangeStart), FleetFormat.date(rangeEnd)))
            append(" — ")
            append(getString(R.string.liters_format, FuelFormActivity.formatLiters(visible.sumOf { it.liters })))
        }.toPersianDigitsInString()
    }

    private fun rate(c: FuelStats.Consumption): String = getString(
        if (c.meterType == MeterType.KM) R.string.consumption_per_100km else R.string.consumption_per_hour,
        "%.1f".format(java.util.Locale.US, c.rate)
    )

    private fun onRowClicked(row: FleetRow.Entry) {
        val (kind, id) = row.id.split(":", limit = 2)
        when {
            kind == "vehicle" || kind == "anomaly" -> VehicleDetailActivity.start(this, id)
            kind == "fuel" && Session.canEdit() -> FuelFormActivity.start(this, fuelId = id)
        }
    }

    // ------------------------------------------------------------------ menu: range + Excel

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_RANGE, Menu.NONE, R.string.choose_range)
            .setIcon(R.drawable.ic_calendar)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.export_excel)
            .setIcon(R.drawable.ic_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_RANGE -> {
            JalaliDateRangePickerDialog.show(this, rangeStart, rangeEnd) { start, end ->
                rangeStart = startOfDay(start)
                rangeEnd = endOfDay(end)
                observeRange()
            }
            true
        }
        MENU_EXPORT -> {
            export()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun export() {
        if (logs.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.fuel_empty, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val fills = XlsxWriter.Sheet(
            getString(R.string.fuel_view_fills),
            listOf("تاریخ", "پلاک", "دسته", "گروه", "راننده", "لیتر", "کارکرد", "منبع", "تأیید مدیر", "توضیحات", "ثبت‌کننده"),
            logs.sortedBy { it.timestamp }.map { log ->
                val v = vehiclesById[log.vehicleId]
                listOf(
                    JalaliCalendar.formatDate(log.timestamp),
                    log.vehiclePlate,
                    v?.categoryEnum()?.displayName.orEmpty(),
                    v?.groupLabel.orEmpty(),
                    log.driverName.orEmpty(),
                    FuelFormActivity.formatLiters(log.liters),
                    log.meterReading?.toString().orEmpty(),
                    log.sourceEnum().displayName,
                    if (log.managerApproved == true) "دارد" else "",
                    log.notes.orEmpty(),
                    log.performedByName.orEmpty()
                )
            }
        )
        val perVehicleSheet = XlsxWriter.Sheet(
            getString(R.string.fuel_view_per_vehicle),
            listOf("پلاک", "گروه", "تعداد سوخت‌گیری", "جمع لیتر", "مسافت/ساعت پیموده‌شده", "میانگین مصرف", "واحد مصرف"),
            FuelStats.summarize(logs, vehiclesById).map { s ->
                val c = s.consumption
                listOf(
                    s.plate,
                    vehiclesById[s.vehicleId]?.groupLabel.orEmpty(),
                    s.fills.toString(),
                    FuelFormActivity.formatLiters(s.liters),
                    c?.distance?.toString().orEmpty(),
                    c?.let { "%.1f".format(java.util.Locale.US, it.rate) }.orEmpty(),
                    c?.let { if (it.meterType == MeterType.KM) "لیتر در ۱۰۰ کیلومتر" else "لیتر در ساعت" }.orEmpty()
                )
            }
        )
        ExcelExport.exportAndShare(this, "fuel_report", listOf(fills, perVehicleSheet))
    }

    private companion object {
        const val MENU_RANGE = 1
        const val MENU_EXPORT = 2

        fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun endOfDay(millis: Long): Long = startOfDay(millis) + TimeUnit.DAYS.toMillis(1) - 1

        fun endOfToday(): Long = endOfDay(System.currentTimeMillis())

        fun startOfJalaliMonth(): Long {
            val today = JalaliCalendar.toJalali(System.currentTimeMillis())
            return startOfDay(JalaliCalendar.jalaliToMillis(today.year, today.month, 1))
        }
    }
}
