package ir.sobhanbeton.fleet.ui.services

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
import ir.sobhanbeton.fleet.data.ServiceLogEntity
import ir.sobhanbeton.fleet.data.ServicePlanEntity
import ir.sobhanbeton.fleet.data.ServiceSchedule
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.ui.vehicles.VehicleDetailActivity
import ir.sobhanbeton.fleet.util.ExcelExport
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.XlsxWriter
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * "سرویس دوره‌ای": what's overdue or coming up across the fleet, every vehicle's status per
 * service, the history of services done, and the service plans themselves (expert edits them).
 */
class ServicesActivity : AppCompatActivity() {

    private enum class Tab { DUE, ALL, HISTORY, PLANS }

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter(::onRowClicked)
    private var tab = Tab.DUE
    private var query = ""
    private var vehicles: List<VehicleEntity> = emptyList()
    private var plans: List<ServicePlanEntity> = emptyList()
    private var logs: List<ServiceLogEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_services)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        listOf(
            getString(R.string.services_tab_due) to Tab.DUE,
            getString(R.string.services_tab_all) to Tab.ALL,
            getString(R.string.services_tab_history) to Tab.HISTORY,
            getString(R.string.services_tab_plans) to Tab.PLANS
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

        if (Session.canEdit()) {
            binding.fabAdd.visibility = View.VISIBLE
            binding.fabAdd.setOnClickListener {
                if (tab == Tab.PLANS) ServicePlanFormActivity.start(this, null) else ServiceLogFormActivity.start(this)
            }
        }
        repository.vehicles().observe(this) { vehicles = it; render() }
        repository.servicePlans().observe(this) { plans = it; render(); invalidateOptionsMenu() }
        repository.serviceLogs().observe(this) { logs = it; render() }
    }

    private fun render() {
        binding.fabAdd.text = getString(if (tab == Tab.PLANS) R.string.service_plan_add else R.string.service_log_add)
        val items = ServiceSchedule.all(vehicles, plans, logs)
        val rows = mutableListOf<FleetRow>()
        when (tab) {
            Tab.DUE, Tab.ALL -> {
                val shown = items.filter {
                    (tab == Tab.ALL || it.state == ServiceSchedule.State.OVERDUE || it.state == ServiceSchedule.State.DUE_SOON) &&
                        (query.isEmpty() || it.vehicle.plate.contains(query) || it.plan.name.contains(query))
                }
                shown.groupBy { it.state }.forEach { (state, list) ->
                    rows += FleetRow.Header("${stateLabel(state)} (${list.size})".toPersianDigitsInString())
                    list.forEach { rows += itemRow(it) }
                }
            }
            Tab.HISTORY -> logs.filter { query.isEmpty() || it.vehiclePlate.contains(query) || it.planName.contains(query) }.forEach { l ->
                rows += FleetRow.Entry(
                    id = "log:${l.id}",
                    title = "${l.vehiclePlate} — ${l.planName}".toPersianDigitsInString(),
                    subtitle = listOfNotNull(FleetFormat.date(l.doneAt), l.meterReading?.let { FleetFormat.number(it) }, l.notes)
                        .joinToString(" · ").toPersianDigitsInString(),
                    iconRes = R.drawable.ic_setup,
                    accentRes = R.color.accent_personnel,
                    accentBgRes = R.color.accent_personnel_bg
                )
            }
            Tab.PLANS -> plans.filter { query.isEmpty() || it.name.contains(query) }
                .groupBy { it.categoryEnum() }.toSortedMap(compareBy { it.ordinal })
                .forEach { (category, list) ->
                    rows += FleetRow.Header(category.displayName)
                    list.forEach { p ->
                        rows += FleetRow.Entry(
                            id = "plan:${p.id}",
                            title = p.name,
                            subtitle = intervalText(p, category.defaultMeterType.unit),
                            iconRes = R.drawable.ic_setup,
                            accentRes = FleetFormat.categoryAccent(category),
                            accentBgRes = FleetFormat.categoryAccentBg(category)
                        )
                    }
                }
        }
        adapter.submitList(rows)

        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(
            when {
                plans.isEmpty() -> if (Session.canEdit()) R.string.services_no_plans_expert else R.string.services_no_plans
                tab == Tab.DUE -> R.string.services_nothing_due
                tab == Tab.HISTORY -> R.string.services_no_history
                else -> R.string.list_no_match
            }
        )
        val overdue = items.count { it.state == ServiceSchedule.State.OVERDUE }
        val soon = items.count { it.state == ServiceSchedule.State.DUE_SOON }
        binding.tvSummary.text = getString(R.string.services_summary, overdue, soon, plans.size).toPersianDigitsInString()
    }

    private fun itemRow(item: ServiceSchedule.Item): FleetRow.Entry {
        val unit = item.vehicle.meterTypeEnum().unit
        val last = item.last?.let { l ->
            getString(R.string.service_last, FleetFormat.date(l.doneAt) + (l.meterReading?.let { " — ${FleetFormat.number(it)} $unit" } ?: ""))
        } ?: getString(R.string.service_never_done)
        val badge = when {
            item.state == ServiceSchedule.State.NEVER_DONE -> getString(R.string.service_never_done_short)
            item.meterLeft != null && (item.daysLeft == null || meterIsTighter(item)) ->
                if (item.meterLeft < 0) getString(R.string.service_meter_over, -item.meterLeft, unit)
                else getString(R.string.service_meter_left, item.meterLeft, unit)
            item.daysLeft != null -> if (item.daysLeft < 0) getString(R.string.service_days_over, -item.daysLeft) else getString(R.string.service_days_left, item.daysLeft)
            else -> FleetFormat.EMPTY
        }
        val category = item.vehicle.categoryEnum()
        return FleetRow.Entry(
            id = "item:${item.vehicle.id}:${item.plan.id}",
            title = "${item.vehicle.plate} — ${item.plan.name}".toPersianDigitsInString(),
            subtitle = last.toPersianDigitsInString(),
            iconRes = R.drawable.ic_setup,
            accentRes = FleetFormat.categoryAccent(category),
            accentBgRes = FleetFormat.categoryAccentBg(category),
            badgeText = badge.toPersianDigitsInString(),
            badgeColorRes = when (item.state) {
                ServiceSchedule.State.OVERDUE -> R.color.danger_red
                ServiceSchedule.State.DUE_SOON -> R.color.safety_amber_dark
                ServiceSchedule.State.OK -> R.color.status_green
                else -> R.color.concrete_300
            }
        )
    }

    /** Show whichever side (meter or days) is closer to its due point, relative to its interval. */
    private fun meterIsTighter(item: ServiceSchedule.Item): Boolean {
        val meterShare = item.meterLeft!!.toDouble() / (item.plan.intervalMeter ?: return false)
        val dayShare = item.daysLeft!!.toDouble() / (item.plan.intervalDays ?: return true)
        return meterShare <= dayShare
    }

    private fun stateLabel(state: ServiceSchedule.State): String = getString(
        when (state) {
            ServiceSchedule.State.OVERDUE -> R.string.service_state_overdue
            ServiceSchedule.State.DUE_SOON -> R.string.service_state_soon
            ServiceSchedule.State.OK -> R.string.service_state_ok
            ServiceSchedule.State.NEVER_DONE -> R.string.service_state_never
            ServiceSchedule.State.UNKNOWN -> R.string.service_state_unknown
        }
    )

    private fun intervalText(p: ServicePlanEntity, unit: String): String = listOfNotNull(
        p.intervalMeter?.let { getString(R.string.service_every_meter, it, unit) },
        p.intervalDays?.let { getString(R.string.service_every_days, it) }
    ).joinToString(getString(R.string.or_word)).toPersianDigitsInString()

    private fun onRowClicked(row: FleetRow.Entry) {
        val parts = row.id.split(":")
        when (parts[0]) {
            "item" -> if (Session.canEdit()) ServiceLogFormActivity.start(this, parts[1], parts[2]) else VehicleDetailActivity.start(this, parts[1])
            "plan" -> if (Session.canEdit()) ServicePlanFormActivity.start(this, parts[1])
            "log" -> if (Session.canEdit()) {
                confirm(R.string.action_delete, getString(R.string.service_log_delete_confirm), R.string.action_delete) {
                    lifecycleScope.launch { reportSave(repository.deleteServiceLog(parts[1])) }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Session.canEdit() && plans.isEmpty()) menu.add(Menu.NONE, MENU_DEFAULTS, Menu.NONE, R.string.services_add_defaults)
        menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.export_excel)
            .setIcon(R.drawable.ic_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_DEFAULTS -> {
            lifecycleScope.launch { reportSave(repository.addDefaultServicePlans()) }
            true
        }
        MENU_EXPORT -> {
            val now = System.currentTimeMillis()
            JalaliDateRangePickerDialog.show(this, now - TimeUnit.DAYS.toMillis(30), now) { start, end ->
                lifecycleScope.launch { export(start, end + TimeUnit.DAYS.toMillis(1) - 1) }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** Two sheets: services done in the range, and the fleet's current status per service. */
    private suspend fun export(from: Long, to: Long) {
        val done = repository.serviceLogsInRange(from, to)
        val status = ServiceSchedule.all(vehicles, plans, logs)
        if (done.isEmpty() && status.isEmpty()) {
            Toast.makeText(this, R.string.services_no_history, Toast.LENGTH_SHORT).show()
            return
        }
        val doneSheet = XlsxWriter.Sheet(
            getString(R.string.services_tab_history),
            listOf("تاریخ", "پلاک", "سرویس", "کارکرد", "توضیحات", "ثبت‌کننده"),
            done.map { listOf(JalaliCalendar.formatDate(it.doneAt), it.vehiclePlate, it.planName, it.meterReading?.toString().orEmpty(), it.notes.orEmpty(), it.performedByName.orEmpty()) }
        )
        val statusSheet = XlsxWriter.Sheet(
            getString(R.string.services_status_sheet),
            listOf("پلاک", "سرویس", "وضعیت", "آخرین انجام", "کارکرد آخرین انجام", "کارکرد مانده", "روز مانده"),
            status.map {
                listOf(
                    it.vehicle.plate, it.plan.name, stateLabel(it.state),
                    it.last?.let { l -> JalaliCalendar.formatDate(l.doneAt) }.orEmpty(),
                    it.last?.meterReading?.toString().orEmpty(),
                    it.meterLeft?.toString().orEmpty(),
                    it.daysLeft?.toString().orEmpty()
                )
            }
        )
        ExcelExport.exportAndShare(this, "services", listOf(doneSheet, statusSheet))
    }

    private companion object {
        const val MENU_DEFAULTS = 1
        const val MENU_EXPORT = 2
    }
}
