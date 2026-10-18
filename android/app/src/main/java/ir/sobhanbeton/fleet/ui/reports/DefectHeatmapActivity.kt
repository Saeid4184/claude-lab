package ir.sobhanbeton.fleet.ui.reports

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.InspectionCatalog
import ir.sobhanbeton.fleet.data.InspectionEntity
import ir.sobhanbeton.fleet.data.InspectionJson
import ir.sobhanbeton.fleet.data.VehicleCategory
import ir.sobhanbeton.fleet.databinding.ActivityDefectHeatmapBinding
import ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * From the security app: every weekly inspection of one vehicle type in a period painted onto a
 * single diagram, so a part that keeps failing across the fleet (e.g. mixers' left mirror)
 * stands out at a glance. Default period: the last 90 days.
 */
class DefectHeatmapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDefectHeatmapBinding
    private var rangeEnd = System.currentTimeMillis()
    private var rangeStart = rangeEnd - TimeUnit.DAYS.toMillis(90)
    private var inRange: List<InspectionEntity> = emptyList()
    /** One chip per distinct checklist (dump trucks and logistics share one). */
    private val categories = VehicleCategory.values().distinctBy { InspectionCatalog.sheetNameFor(it) }
    private var category = VehicleCategory.MIXER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDefectHeatmapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.heatmap_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        categories.forEachIndexed { index, c ->
            binding.chipGroup.addView(Chip(this).apply {
                id = View.generateViewId()
                text = InspectionCatalog.sheetNameFor(c)
                isCheckable = true
                isChecked = index == 0
                setOnClickListener { category = c; render() }
            })
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            inRange = FleetRepository.get(this@DefectHeatmapActivity).inspectionsInRange(rangeStart, rangeEnd)
            render()
        }
    }

    private fun render() {
        val sheet = InspectionCatalog.sheetNameFor(category)
        val forChecklist = inRange.filter { InspectionCatalog.sheetNameFor(it.categoryEnum()) == sheet }
        binding.diagramView.setHeatmapData(category, InspectionJson.defectCountsByPart(forChecklist))
        binding.tvEmpty.visibility = if (forChecklist.isEmpty()) View.VISIBLE else View.GONE
        binding.tvSummary.text = getString(
            R.string.heatmap_summary, FleetFormat.date(rangeStart), FleetFormat.date(rangeEnd), forChecklist.size
        ).toPersianDigitsInString()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_RANGE, Menu.NONE, R.string.choose_range)
            .setIcon(R.drawable.ic_calendar)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_RANGE -> {
            JalaliDateRangePickerDialog.show(this, rangeStart, rangeEnd) { start, end ->
                rangeStart = start; rangeEnd = end + TimeUnit.DAYS.toMillis(1) - 1; load()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private companion object {
        const val MENU_RANGE = 1
    }
}
