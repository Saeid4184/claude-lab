package ir.sobhanbeton.fleet.ui.repairs

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
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.WorkOrderEntity
import ir.sobhanbeton.fleet.data.WorkOrderSeverity
import ir.sobhanbeton.fleet.data.WorkOrderStatus
import ir.sobhanbeton.fleet.data.WorkOrders
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.ui.inspections.InspectionsActivity
import ir.sobhanbeton.fleet.util.ExcelExport
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.XlsxWriter
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** "خرابی و تعمیرات": open repairs first (stopping ones on top with their downtime so far),
 *  finished ones, and an Excel export of a date range. */
class WorkOrdersActivity : AppCompatActivity() {

    private enum class Tab { OPEN, DONE, ALL }

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter { row -> WorkOrderDetailActivity.start(this, row.id) }
    private var orders: List<WorkOrderEntity> = emptyList()
    private var tab = Tab.OPEN
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_repairs)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        listOf(
            getString(R.string.work_orders_tab_open) to Tab.OPEN,
            getString(R.string.work_orders_tab_done) to Tab.DONE,
            getString(R.string.filter_all) to Tab.ALL
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
            binding.fabAdd.text = getString(R.string.work_order_add)
            binding.fabAdd.setOnClickListener { WorkOrderFormActivity.start(this) }
        }
        repository.workOrders().observe(this) { orders = it; render() }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val visible = orders.filter { o ->
            when (tab) {
                Tab.OPEN -> o.statusEnum() != WorkOrderStatus.DONE
                Tab.DONE -> o.statusEnum() == WorkOrderStatus.DONE
                Tab.ALL -> true
            } && (query.isEmpty() || listOfNotNull(o.vehiclePlate, o.title, o.workshopName, o.driverName).any { it.contains(query, ignoreCase = true) })
        }.sortedWith(
            compareBy<WorkOrderEntity> { it.statusEnum() == WorkOrderStatus.DONE }
                .thenBy { it.severityEnum() != WorkOrderSeverity.STOPPING }
                .thenByDescending { it.reportedAt }
        )
        adapter.submitList(visible.map { o ->
            val status = o.statusEnum()
            val stoppedFor = WorkOrders.downtimeMillis(o, now)?.takeIf { status != WorkOrderStatus.DONE }
            FleetRow.Entry(
                id = o.id,
                title = "${o.vehiclePlate} — ${o.title}".toPersianDigitsInString(),
                subtitle = listOfNotNull(
                    FleetFormat.date(o.reportedAt),
                    o.severityEnum().displayName,
                    o.workshopName ?: o.locationEnum().displayName
                ).joinToString(" · ").toPersianDigitsInString(),
                iconRes = R.drawable.ic_inspection,
                accentRes = R.color.accent_inspection,
                accentBgRes = R.color.accent_inspection_bg,
                badgeText = status.displayName,
                badgeColorRes = when (status) {
                    WorkOrderStatus.OPEN -> R.color.danger_red
                    WorkOrderStatus.IN_PROGRESS -> R.color.safety_amber_dark
                    WorkOrderStatus.DONE -> R.color.status_green
                },
                warning = stoppedFor?.let { getString(R.string.work_order_stopped_days, TimeUnit.MILLISECONDS.toDays(it)).toPersianDigitsInString() }
            )
        })
        binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(if (orders.isEmpty()) R.string.work_orders_empty else R.string.list_no_match)
        val open = orders.count { it.statusEnum() != WorkOrderStatus.DONE }
        val stopping = orders.count { it.statusEnum() != WorkOrderStatus.DONE && it.severityEnum() == WorkOrderSeverity.STOPPING }
        binding.tvSummary.text = getString(R.string.work_orders_summary, open, stopping).toPersianDigitsInString()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_INSPECTIONS, Menu.NONE, R.string.home_inspections)
        menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.export_excel)
            .setIcon(R.drawable.ic_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_INSPECTIONS -> {
            startActivity(android.content.Intent(this, InspectionsActivity::class.java))
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

    private suspend fun export(from: Long, to: Long) {
        val list = repository.workOrdersInRange(from, to)
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.work_orders_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val sheet = XlsxWriter.Sheet(
            getString(R.string.home_repairs),
            listOf("تاریخ خرابی", "پلاک", "راننده", "شرح خرابی", "جزئیات", "نوع", "محل تعمیر", "تعمیرگاه", "وضعیت",
                "شروع تعمیر", "پایان تعمیر", "توقف (ساعت)", "کار انجام‌شده", "قطعات مصرفی", "ثبت‌کننده"),
            list.map { o ->
                listOf(
                    JalaliCalendar.formatDate(o.reportedAt), o.vehiclePlate, o.driverName.orEmpty(), o.title, o.details.orEmpty(),
                    o.severityEnum().displayName, o.locationEnum().displayName, o.workshopName.orEmpty(), o.statusEnum().displayName,
                    o.startedAt?.let { JalaliCalendar.formatDate(it) }.orEmpty(), o.completedAt?.let { JalaliCalendar.formatDate(it) }.orEmpty(),
                    WorkOrders.downtimeMillis(o, now)?.let { (it / 3_600_000L).toString() }.orEmpty(),
                    o.workDone.orEmpty(), o.partsUsed.orEmpty(), o.performedByName.orEmpty()
                )
            }
        )
        ExcelExport.exportAndShare(this, "repairs", listOf(sheet))
    }

    private companion object {
        const val MENU_EXPORT = 1
        const val MENU_INSPECTIONS = 2
    }
}
