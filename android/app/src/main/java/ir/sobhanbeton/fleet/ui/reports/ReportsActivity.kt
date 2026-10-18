package ir.sobhanbeton.fleet.ui.reports

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.CloudSettings
import ir.sobhanbeton.fleet.data.ExpiryAlerts
import ir.sobhanbeton.fleet.data.FleetReport
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.databinding.ActivityFleetDetailBinding
import ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog
import ir.sobhanbeton.fleet.ui.common.DetailSections
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.vehicles.VehicleDetailActivity
import ir.sobhanbeton.fleet.util.AiReportAnalyzer
import ir.sobhanbeton.fleet.util.AppPreferences
import ir.sobhanbeton.fleet.util.ExcelExport
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * "داشبورد و گزارش‌ها": the fleet at a glance for a period (default: this Jalali month) — for
 * the manager as much as the expert. One button exports the whole monthly report as Excel;
 * another asks Gemini for a short analysis of the aggregated numbers. Menu: change period,
 * defect heatmap.
 */
class ReportsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetDetailBinding
    private lateinit var repository: FleetRepository
    private lateinit var sections: DetailSections
    private var rangeStart = startOfJalaliMonth(System.currentTimeMillis())
    private var rangeEnd = System.currentTimeMillis()
    private var report: FleetReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sections = DetailSections(binding.container)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_reports)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.actionBar.visibility = View.VISIBLE
        binding.btnPrimaryAction.text = getString(R.string.report_export_monthly)
        binding.btnSecondaryAction.text = getString(R.string.report_ai_analysis)
        binding.btnPrimaryAction.setOnClickListener { export() }
        binding.btnSecondaryAction.setOnClickListener { analyze() }
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        lifecycleScope.launch {
            val built = withContext(Dispatchers.IO) {
                FleetReport.build(
                    from = rangeStart,
                    to = rangeEnd,
                    vehicles = repository.allVehiclesOnce(),
                    drivers = repository.allDriversOnce(),
                    workOrders = repository.workOrdersInRange(0L, Long.MAX_VALUE),
                    fuelLogs = repository.fuelInRangeOnce(0L, Long.MAX_VALUE),
                    dailyWork = repository.dailyWorkInRangeOnce(0, Int.MAX_VALUE),
                    plans = repository.servicePlansOnce(),
                    serviceLogs = repository.serviceLogsOnce(),
                    incidents = repository.incidentsInRange(0L, Long.MAX_VALUE),
                    inspections = repository.inspectionsInRange(0L, Long.MAX_VALUE)
                )
            }
            report = built
            render(built)
        }
    }

    private fun render(r: FleetReport) {
        fun n(value: Number) = FleetFormat.number(value)
        sections.clear()

        sections.section(getString(R.string.report_period, FleetFormat.date(r.from), FleetFormat.date(r.to)).toPersianDigitsInString())
        sections.row(getString(R.string.report_availability), "${MonthlyReportExporter.percent(r.availability)}٪".toPersianDigitsInString(),
            if (r.availability < 0.85) R.color.danger_red else R.color.status_green)
        sections.row(getString(R.string.report_vehicles_in_service), n(r.vehiclesInService))
        sections.row(getString(R.string.report_stopped_now), n(r.stoppedNow), if (r.stoppedNow > 0) R.color.safety_amber else null)

        sections.section(getString(R.string.report_section_work))
        sections.row(getString(R.string.report_mixer_work), getString(R.string.report_mixer_work_value, r.work.mixerTrips, FleetFormat.decimal(r.work.mixerM3)).toPersianDigitsInString())
        sections.row(getString(R.string.report_pump_work), "${FleetFormat.decimal(r.work.pumpM3)} ${getString(R.string.unit_m3)}")
        sections.row(getString(R.string.report_dump_work), getString(R.string.report_dump_work_value, r.work.mineTrips, r.work.debrisTrips).toPersianDigitsInString())
        if (r.work.otherTrips > 0) sections.row(getString(R.string.report_other_work), n(r.work.otherTrips))
        r.workByDriver.take(TOP_ROWS).forEachIndexed { i, t ->
            sections.row(
                getString(R.string.report_top_driver, i + 1).toPersianDigitsInString(),
                listOfNotNull(
                    t.label,
                    t.concreteM3.takeIf { it > 0 }?.let { getString(R.string.metric_m3, FleetFormat.decimal(it)) },
                    (t.trips + t.mineTrips + t.debrisTrips).takeIf { it > 0 }?.let { getString(R.string.metric_trips, it) }
                ).joinToString(" · ").toPersianDigitsInString()
            )
        }

        sections.section(getString(R.string.report_section_repairs))
        sections.row(getString(R.string.report_repairs_opened), n(r.repairsOpened))
        sections.row(getString(R.string.report_repairs_closed), n(r.repairsClosed))
        sections.row(getString(R.string.report_repairs_open_now), n(r.repairsOpenNow), if (r.repairsOpenNow > 0) R.color.safety_amber else null)
        r.downtime.filter { it.downtimeMillis > 0 }.take(TOP_ROWS).forEach { d ->
            sections.row(
                d.plate.toPersianDigitsInString(),
                getString(R.string.report_downtime_value, d.downtimeMillis / 3_600_000L, MonthlyReportExporter.percent(d.availability)).toPersianDigitsInString(),
                onClick = { VehicleDetailActivity.start(this, d.vehicleId) }
            )
        }

        sections.section(getString(R.string.home_fuel))
        sections.row(getString(R.string.report_fuel_total), getString(R.string.liters_format, FleetFormat.decimal(r.fuelLiters)).toPersianDigitsInString())
        sections.row(getString(R.string.report_fuel_anomalies), n(r.fuelAnomalies.size), if (r.fuelAnomalies.isNotEmpty()) R.color.danger_red else null)
        r.fuelByVehicle.take(TOP_ROWS).forEach { s ->
            sections.row(s.plate.toPersianDigitsInString(), getString(R.string.liters_format, FleetFormat.decimal(s.liters)).toPersianDigitsInString(),
                onClick = { VehicleDetailActivity.start(this, s.vehicleId) })
        }

        sections.section(getString(R.string.report_section_upkeep))
        sections.row(getString(R.string.report_services_done), n(r.servicesDoneInPeriod))
        sections.row(getString(R.string.report_services_overdue), n(r.servicesOverdue.size), if (r.servicesOverdue.isNotEmpty()) R.color.danger_red else null)
        sections.row(getString(R.string.report_services_soon), n(r.servicesDueSoon))
        sections.row(getString(R.string.report_documents), n(r.documentAlerts.size), if (r.documentAlerts.any { it.daysLeft < 0 }) R.color.danger_red else null)
        sections.row(getString(R.string.report_inspections), n(r.inspectionsInPeriod))
        sections.row(getString(R.string.report_open_defects), n(r.openDefects))
        r.topDefectParts.forEach { (part, count) -> sections.row(part, getString(R.string.report_defect_times, count).toPersianDigitsInString()) }

        sections.section(getString(R.string.home_incidents))
        if (r.incidentsByType.isEmpty()) sections.note(getString(R.string.incidents_export_empty))
        r.incidentsByType.forEach { (type, count) -> sections.row(type.displayName, n(count)) }
    }

    private fun export() {
        val r = report ?: return
        ExcelExport.exportAndShare(this, "monthly_report", MonthlyReportExporter.sheets(r))
    }

    /** Only aggregated numbers go to Gemini — no plates, no names. */
    private fun analyze() {
        val r = report ?: return
        binding.btnSecondaryAction.isEnabled = false
        lifecycleScope.launch {
            var key = AppPreferences.getAiApiKey(this@ReportsActivity)
            if (key.isBlank()) {
                key = CloudSettings.fetchAiApiKey().orEmpty()
                if (key.isNotBlank()) AppPreferences.setAiApiKey(this@ReportsActivity, key)
            }
            val result = withContext(Dispatchers.IO) { AiReportAnalyzer.analyze(key, aiSummary(r)) }
            binding.btnSecondaryAction.isEnabled = true
            MaterialAlertDialogBuilder(this@ReportsActivity)
                .setTitle(R.string.report_ai_analysis)
                .setMessage(result.getOrElse { it.message ?: getString(R.string.error_generic) })
                .setPositiveButton(R.string.btn_ok, null)
                .show()
        }
    }

    private fun aiSummary(r: FleetReport): String = buildString {
        appendLine("بازه: ${JalaliCalendar.formatDate(r.from)} تا ${JalaliCalendar.formatDate(r.to)}")
        appendLine("ماشین‌های در سرویس: ${r.vehiclesInService}، آماده‌به‌کاری: ${MonthlyReportExporter.percent(r.availability)}٪، متوقف الان: ${r.stoppedNow}")
        appendLine("میکسرها: ${r.work.mixerTrips} سرویس، ${MonthlyReportExporter.decimal(r.work.mixerM3)} مترمکعب بتن؛ پمپ‌ها: ${MonthlyReportExporter.decimal(r.work.pumpM3)} مترمکعب")
        appendLine("کمپرسی‌ها: ${r.work.mineTrips} سرویس معدن، ${r.work.debrisTrips} سرویس نخاله")
        appendLine("خرابی‌های جدید: ${r.repairsOpened}، تعمیرات تمام‌شده: ${r.repairsClosed}، تعمیرات باز: ${r.repairsOpenNow}")
        appendLine("سوخت: ${MonthlyReportExporter.decimal(r.fuelLiters)} لیتر؛ ماشین‌های با مصرف غیرعادی: ${r.fuelAnomalies.size}")
        appendLine("سرویس‌های دوره‌ای انجام‌شده: ${r.servicesDoneInPeriod}، عقب‌افتاده: ${r.servicesOverdue.size}، نزدیک به موعد: ${r.servicesDueSoon}")
        appendLine("مدارک منقضی یا رو به انقضا: ${r.documentAlerts.size}")
        appendLine("بازدیدهای هفتگی: ${r.inspectionsInPeriod}، ایرادهای باز: ${r.openDefects}، پرتکرارترین ایرادها: " +
            r.topDefectParts.joinToString("، ") { "${it.first} (${it.second})" }.ifEmpty { "—" })
        appendLine("حوادث و تخلفات: " + r.incidentsByType.entries.joinToString("، ") { "${it.key.displayName}: ${it.value}" }.ifEmpty { "—" })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_THIS_MONTH, Menu.NONE, R.string.report_this_month)
        menu.add(Menu.NONE, MENU_LAST_MONTH, Menu.NONE, R.string.report_last_month)
        menu.add(Menu.NONE, MENU_RANGE, Menu.NONE, R.string.choose_range)
            .setIcon(R.drawable.ic_calendar)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_HEATMAP, Menu.NONE, R.string.heatmap_title)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_THIS_MONTH -> {
            rangeStart = startOfJalaliMonth(System.currentTimeMillis()); rangeEnd = System.currentTimeMillis(); rebuild(); true
        }
        MENU_LAST_MONTH -> {
            val thisMonth = startOfJalaliMonth(System.currentTimeMillis())
            rangeStart = startOfJalaliMonth(thisMonth - TimeUnit.DAYS.toMillis(1))
            rangeEnd = thisMonth - 1
            rebuild(); true
        }
        MENU_RANGE -> {
            JalaliDateRangePickerDialog.show(this, rangeStart, rangeEnd) { start, end ->
                rangeStart = start; rangeEnd = end + TimeUnit.DAYS.toMillis(1) - 1; rebuild()
            }
            true
        }
        MENU_HEATMAP -> {
            startActivity(Intent(this, DefectHeatmapActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private companion object {
        const val TOP_ROWS = 5
        const val MENU_THIS_MONTH = 1
        const val MENU_LAST_MONTH = 2
        const val MENU_RANGE = 3
        const val MENU_HEATMAP = 4

        /** Midnight at the start of the Jalali month containing [millis]. */
        fun startOfJalaliMonth(millis: Long): Long {
            val j = JalaliCalendar.toJalali(millis)
            val noon = JalaliCalendar.jalaliToMillis(j.year, j.month, 1)
            return ExpiryAlerts.startOfDay(noon)
        }
    }
}
