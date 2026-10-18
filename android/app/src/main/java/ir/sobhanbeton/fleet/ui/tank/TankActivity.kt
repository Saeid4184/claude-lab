package ir.sobhanbeton.fleet.ui.tank

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.FuelLogEntity
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.TankConfigEntity
import ir.sobhanbeton.fleet.data.TankDeliveryEntity
import ir.sobhanbeton.fleet.data.TankReadingEntity
import ir.sobhanbeton.fleet.data.TankStock
import ir.sobhanbeton.fleet.data.TankWithdrawalEntity
import ir.sobhanbeton.fleet.databinding.ActivityTankBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.ui.fuel.FuelFormActivity
import ir.sobhanbeton.fleet.ui.reports.MonthlyReportExporter
import ir.sobhanbeton.fleet.util.ExcelExport
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.XlsxWriter
import ir.sobhanbeton.fleet.util.normalizeDigitsForParsing
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/**
 * "مخزن گازوئیل": current stock (last measurement + tanker deliveries − fuel given from the tank),
 * every movement, and each measurement with its discrepancy. Fleet fuelling from the tank comes
 * straight from the fuel logs — it's never entered here twice.
 */
class TankActivity : AppCompatActivity() {

    private enum class Tab { MOVEMENTS, READINGS }

    private lateinit var binding: ActivityTankBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter(::onRowClicked)
    private var tab = Tab.MOVEMENTS
    private var config = TankConfigEntity()
    private var deliveries: List<TankDeliveryEntity> = emptyList()
    private var withdrawals: List<TankWithdrawalEntity> = emptyList()
    private var readings: List<TankReadingEntity> = emptyList()
    private var tankFuel: List<FuelLogEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityTankBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_tank)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        listOf(getString(R.string.tank_tab_movements) to Tab.MOVEMENTS, getString(R.string.tank_tab_readings) to Tab.READINGS)
            .forEachIndexed { index, (label, t) ->
                binding.chipGroup.addView(Chip(this).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                    isChecked = index == 0
                    setOnClickListener { tab = t; render() }
                })
            }
        if (Session.canEdit()) {
            binding.fabAdd.visibility = View.VISIBLE
            binding.fabAdd.text = getString(R.string.tank_add)
            binding.fabAdd.setOnClickListener { chooseEntry() }
        }

        repository.tankConfig().observe(this) { config = it ?: TankConfigEntity(); render() }
        repository.tankDeliveries().observe(this) { deliveries = it; render() }
        repository.tankWithdrawals().observe(this) { withdrawals = it; render() }
        repository.tankReadings().observe(this) { readings = it; render() }
        repository.tankFuel().observe(this) { tankFuel = it; render() }
    }

    private fun render() {
        val status = TankStock.status(deliveries, withdrawals, readings, tankFuel)
        val stock = status.stock
        if (stock == null) {
            binding.tvStock.text = getString(R.string.tank_no_reading)
            binding.progressStock.progress = 0
            binding.tvStockDetail.text = getString(R.string.tank_no_reading_hint)
        } else {
            binding.tvStock.text = getString(R.string.tank_stock_value, FleetFormat.decimal(stock), FleetFormat.decimal(config.capacityLiters)).toPersianDigitsInString()
            val share = (stock / config.capacityLiters).coerceIn(0.0, 1.0)
            binding.progressStock.progress = (share * 1000).toInt()
            val color = when {
                stock < 0 -> R.color.danger_red
                stock < config.lowLevelLiters -> R.color.safety_amber
                else -> R.color.status_green
            }
            binding.progressStock.setIndicatorColor(ContextCompat.getColor(this, color))
            binding.tvStockDetail.text = getString(
                R.string.tank_stock_detail,
                FleetFormat.date(status.lastReading!!.date), FleetFormat.decimal(status.lastReading.liters),
                FleetFormat.decimal(status.deliveredSince), FleetFormat.decimal(status.usedSince)
            ).toPersianDigitsInString()
            if (stock < config.lowLevelLiters) {
                binding.tvStockDetail.text = "${binding.tvStockDetail.text}\n${getString(R.string.tank_low_warning, FleetFormat.decimal(config.lowLevelLiters))}".toPersianDigitsInString()
            }
        }

        val rows = when (tab) {
            Tab.MOVEMENTS -> TankStock.movements(deliveries, withdrawals, readings, tankFuel).map { m ->
                val (label, sign, colorRes) = when (m.kind) {
                    TankStock.Movement.Kind.DELIVERY -> Triple(getString(R.string.tank_kind_delivery), "+", R.color.status_green)
                    TankStock.Movement.Kind.VEHICLE_FUEL -> Triple(getString(R.string.tank_kind_vehicle), "−", R.color.safety_amber_dark)
                    TankStock.Movement.Kind.OTHER_WITHDRAWAL -> Triple(getString(R.string.tank_kind_other), "−", R.color.safety_amber_dark)
                    TankStock.Movement.Kind.READING -> Triple(getString(R.string.tank_kind_reading), "=", R.color.accent_machinery)
                }
                FleetRow.Entry(
                    id = "${m.kind.name}:${m.refId}",
                    title = listOfNotNull(label, m.label.takeIf { it.isNotBlank() }).joinToString(" — ").toPersianDigitsInString(),
                    subtitle = FleetFormat.date(m.date),
                    iconRes = R.drawable.ic_inventory,
                    accentRes = R.color.accent_item_log,
                    accentBgRes = R.color.accent_item_log_bg,
                    badgeText = "$sign ${getString(R.string.liters_format, FleetFormat.decimal(m.liters))}".toPersianDigitsInString(),
                    badgeColorRes = colorRes
                )
            }
            Tab.READINGS -> TankStock.checks(deliveries, withdrawals, readings, tankFuel).map { c ->
                val d = c.discrepancy
                FleetRow.Entry(
                    id = "${TankStock.Movement.Kind.READING.name}:${c.reading.id}",
                    title = getString(R.string.liters_format, FleetFormat.decimal(c.reading.liters)).toPersianDigitsInString(),
                    subtitle = listOfNotNull(
                        FleetFormat.date(c.reading.date),
                        c.expected?.let { getString(R.string.tank_expected, FleetFormat.decimal(it)) },
                        c.reading.notes
                    ).joinToString(" · ").toPersianDigitsInString(),
                    iconRes = R.drawable.ic_inventory,
                    accentRes = R.color.accent_machinery,
                    accentBgRes = R.color.accent_machinery_bg,
                    badgeText = d?.let { (if (it >= 0) "+" else "−") + getString(R.string.liters_format, FleetFormat.decimal(kotlin.math.abs(it))) }
                        ?.toPersianDigitsInString() ?: getString(R.string.tank_first_reading),
                    badgeColorRes = when {
                        d == null -> R.color.concrete_300
                        d < -DISCREPANCY_TOLERANCE -> R.color.danger_red
                        d > DISCREPANCY_TOLERANCE -> R.color.safety_amber_dark
                        else -> R.color.status_green
                    }
                )
            }
        }
        adapter.submitList(rows)
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(R.string.tank_empty)
        binding.tvSummary.text = getString(R.string.tank_priority_note)
    }

    private fun onRowClicked(row: FleetRow.Entry) {
        if (!Session.canEdit()) return
        val (kind, id) = row.id.split(":", limit = 2)
        when (TankStock.Movement.Kind.valueOf(kind)) {
            TankStock.Movement.Kind.DELIVERY -> TankEntryFormActivity.start(this, TankEntryFormActivity.Mode.DELIVERY, id)
            TankStock.Movement.Kind.OTHER_WITHDRAWAL -> TankEntryFormActivity.start(this, TankEntryFormActivity.Mode.WITHDRAWAL, id)
            TankStock.Movement.Kind.READING -> TankEntryFormActivity.start(this, TankEntryFormActivity.Mode.READING, id)
            TankStock.Movement.Kind.VEHICLE_FUEL -> FuelFormActivity.start(this, fuelId = id)
        }
    }

    private fun chooseEntry() {
        val options = listOf(
            getString(R.string.tank_add_delivery) to TankEntryFormActivity.Mode.DELIVERY,
            getString(R.string.tank_add_vehicle_fuel) to null,
            getString(R.string.tank_add_withdrawal) to TankEntryFormActivity.Mode.WITHDRAWAL,
            getString(R.string.tank_add_reading) to TankEntryFormActivity.Mode.READING
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tank_add)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val mode = options[which].second
                if (mode == null) FuelFormActivity.start(this) else TankEntryFormActivity.start(this, mode)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun editSettings() {
        val pad = resources.getDimensionPixelSize(R.dimen.dim_20)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun field(hint: Int, value: Double): TextInputEditText {
            val layout = TextInputLayout(this).apply { this.hint = getString(hint) }
            val edit = TextInputEditText(layout.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(FleetFormat.decimal(value))
            }
            layout.addView(edit); container.addView(layout); return edit
        }
        val capacity = field(R.string.field_tank_capacity, config.capacityLiters)
        val low = field(R.string.field_tank_low_level, config.lowLevelLiters)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tank_settings)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val c = capacity.text?.toString()?.normalizeDigitsForParsing()?.toDoubleOrNull() ?: return@setPositiveButton
                val l = low.text?.toString()?.normalizeDigitsForParsing()?.toDoubleOrNull() ?: return@setPositiveButton
                lifecycleScope.launch { reportSave(repository.saveTankConfig(c, l)) }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun export() {
        val checks = TankStock.checks(deliveries, withdrawals, readings, tankFuel).associateBy { it.reading.id }
        val movements = TankStock.movements(deliveries, withdrawals, readings, tankFuel).reversed()
        val sheet = XlsxWriter.Sheet(
            getString(R.string.home_tank),
            listOf("تاریخ", "نوع", "شرح", "ورود (لیتر)", "خروج (لیتر)", "اندازه‌گیری (لیتر)", "مغایرت (لیتر)"),
            movements.map { m ->
                val litres = MonthlyReportExporter.decimal(m.liters)
                listOf(
                    JalaliCalendar.formatDate(m.date),
                    when (m.kind) {
                        TankStock.Movement.Kind.DELIVERY -> "ورود تانکر"
                        TankStock.Movement.Kind.VEHICLE_FUEL -> "سوخت ماشین"
                        TankStock.Movement.Kind.OTHER_WITHDRAWAL -> "برداشت متفرقه"
                        TankStock.Movement.Kind.READING -> "اندازه‌گیری"
                    },
                    m.label,
                    if (m.kind == TankStock.Movement.Kind.DELIVERY) litres else "",
                    if (m.kind == TankStock.Movement.Kind.VEHICLE_FUEL || m.kind == TankStock.Movement.Kind.OTHER_WITHDRAWAL) litres else "",
                    if (m.kind == TankStock.Movement.Kind.READING) litres else "",
                    if (m.kind == TankStock.Movement.Kind.READING) checks[m.refId]?.discrepancy?.let { MonthlyReportExporter.decimal(it) }.orEmpty() else ""
                )
            }
        )
        ExcelExport.exportAndShare(this, "diesel_tank", listOf(sheet))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (Session.canEdit()) menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.tank_settings)
        menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.export_excel)
            .setIcon(R.drawable.ic_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SETTINGS -> { editSettings(); true }
        MENU_EXPORT -> { export(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private companion object {
        const val MENU_SETTINGS = 1
        const val MENU_EXPORT = 2
        /** Measurement noise below this is shown as "ok". */
        const val DISCREPANCY_TOLERANCE = 50.0
    }
}
