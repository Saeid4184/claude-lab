package ir.sobhanbeton.fleet.ui.repairs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.WorkOrderEntity
import ir.sobhanbeton.fleet.data.WorkOrderStatus
import ir.sobhanbeton.fleet.data.WorkOrders
import ir.sobhanbeton.fleet.databinding.ActivityFleetDetailBinding
import ir.sobhanbeton.fleet.ui.common.DetailSections
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.JalaliDatePickerDialog
import ir.sobhanbeton.fleet.ui.vehicles.VehicleDetailActivity
import ir.sobhanbeton.fleet.util.normalizeDigitsForParsing
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/** One repair order: the breakdown, the repair, downtime; expert actions to start/finish,
 *  edit or delete it. */
class WorkOrderDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetDetailBinding
    private lateinit var repository: FleetRepository
    private lateinit var sections: DetailSections
    private lateinit var orderId: String
    private var order: WorkOrderEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orderId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityFleetDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sections = DetailSections(binding.container)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (Session.canEdit()) {
            binding.btnSecondaryAction.text = getString(R.string.action_edit)
            binding.btnSecondaryAction.setOnClickListener { WorkOrderFormActivity.start(this, workOrderId = orderId) }
            binding.btnPrimaryAction.setOnClickListener { advance() }
        }

        repository.workOrder(orderId).observe(this) { o ->
            if (o == null) {
                if (order != null) finish()
                return@observe
            }
            order = o
            render(o)
        }
    }

    private fun render(o: WorkOrderEntity) {
        supportActionBar?.title = o.vehiclePlate.toPersianDigitsInString()
        val status = o.statusEnum()
        if (Session.canEdit()) {
            binding.actionBar.visibility = View.VISIBLE
            binding.btnPrimaryAction.visibility = if (status == WorkOrderStatus.DONE) View.GONE else View.VISIBLE
            binding.btnPrimaryAction.text = getString(if (status == WorkOrderStatus.OPEN) R.string.work_order_start else R.string.work_order_finish)
        }
        sections.clear()

        sections.section(getString(R.string.work_order_section_breakdown))
        sections.row(getString(R.string.field_vehicle), o.vehiclePlate.toPersianDigitsInString(), onClick = { VehicleDetailActivity.start(this, o.vehicleId) })
        sections.row(getString(R.string.field_reported_at), FleetFormat.date(o.reportedAt))
        sections.row(getString(R.string.field_wo_title), o.title)
        o.details?.let { sections.row(getString(R.string.field_wo_details), it) }
        sections.row(getString(R.string.field_wo_severity), o.severityEnum().displayName)
        sections.row(getString(R.string.field_current_driver), o.driverName ?: FleetFormat.EMPTY)
        o.meterReading?.let { sections.row(getString(R.string.field_meter_optional), FleetFormat.number(it)) }
        o.sourcePartName?.let { sections.row(getString(R.string.work_order_source), getString(R.string.work_order_source_defect, it)) }

        sections.section(getString(R.string.work_order_section_repair))
        sections.row(getString(R.string.field_status), status.displayName, statusColor(status))
        sections.row(getString(R.string.field_wo_location), o.locationEnum().displayName)
        o.workshopName?.let { sections.row(getString(R.string.field_wo_workshop), it) }
        o.startedAt?.let { sections.row(getString(R.string.work_order_started), FleetFormat.date(it)) }
        o.completedAt?.let { sections.row(getString(R.string.work_order_completed), FleetFormat.date(it)) }
        WorkOrders.downtimeMillis(o)?.let {
            sections.row(getString(if (o.completedAt == null) R.string.work_order_downtime_so_far else R.string.work_order_downtime), formatDuration(it))
        }
        o.workDone?.let { sections.row(getString(R.string.field_work_done), it) }
        o.partsUsed?.let { sections.row(getString(R.string.field_parts_used), it) }
    }

    private fun statusColor(status: WorkOrderStatus): Int = when (status) {
        WorkOrderStatus.OPEN -> R.color.danger_red
        WorkOrderStatus.IN_PROGRESS -> R.color.safety_amber
        WorkOrderStatus.DONE -> R.color.status_green
    }

    /** OPEN → pick the start date; IN_PROGRESS → pick the end date, then what was done. */
    private fun advance() {
        val o = order ?: return
        when (o.statusEnum()) {
            WorkOrderStatus.OPEN -> JalaliDatePickerDialog.show(this, getString(R.string.work_order_start), System.currentTimeMillis(), onPicked = { at ->
                lifecycleScope.launch { reportSave(repository.startWorkOrder(o.id, at)) }
            })
            WorkOrderStatus.IN_PROGRESS -> JalaliDatePickerDialog.show(this, getString(R.string.work_order_finish_date), System.currentTimeMillis(), onPicked = { at ->
                askCompletionDetails(o, at)
            })
            WorkOrderStatus.DONE -> Unit
        }
    }

    private fun askCompletionDetails(o: WorkOrderEntity, at: Long) {
        val pad = resources.getDimensionPixelSize(R.dimen.dim_20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: Int, type: Int, initial: String?): TextInputEditText {
            val layout = TextInputLayout(this).apply { this.hint = getString(hint) }
            val edit = TextInputEditText(layout.context).apply { inputType = type; setText(initial) }
            layout.addView(edit)
            container.addView(layout)
            return edit
        }
        val workDone = field(R.string.field_work_done, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, o.workDone)
        val parts = field(R.string.field_parts_used, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, o.partsUsed)
        val meter = field(R.string.field_meter_optional, InputType.TYPE_CLASS_NUMBER, o.meterReading?.toString())

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.work_order_finish)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val meterValue = meter.text?.toString()?.trim()?.normalizeDigitsForParsing()?.toLongOrNull()
                lifecycleScope.launch {
                    reportSave(repository.completeWorkOrder(o.id, at, workDone.text?.toString(), parts.text?.toString(), meterValue))
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun formatDuration(millis: Long): String {
        val hours = millis / 3_600_000L
        val days = hours / 24
        val rest = hours % 24
        return when {
            days > 0 && rest > 0 -> getString(R.string.duration_days_hours, days, rest)
            days > 0 -> getString(R.string.duration_days, days)
            else -> getString(R.string.duration_hours, hours)
        }.toPersianDigitsInString()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Session.canEdit()) menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.action_delete)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_DELETE -> {
            confirm(R.string.action_delete, getString(R.string.work_order_delete_confirm), R.string.action_delete) {
                lifecycleScope.launch { reportSave(repository.deleteWorkOrder(orderId)) { finish() } }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_ID = "work_order_id"
        private const val MENU_DELETE = 1

        fun start(context: Context, workOrderId: String) {
            context.startActivity(Intent(context, WorkOrderDetailActivity::class.java).putExtra(EXTRA_ID, workOrderId))
        }
    }
}
