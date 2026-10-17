package ir.sobhanbeton.fleet.ui.expenses

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.LinearLayoutManager
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.ExpenseNoteEntity
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog
import ir.sobhanbeton.fleet.ui.common.FleetFormat
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * "یادداشت هزینه‌ها" — the expert's private notebook of amounts. Stand-alone on purpose: it
 * isn't linked to vehicles, repairs or fuel, and nothing else in the app (lists, reports, Excel,
 * the manager's phone) ever shows it. Expert only.
 */
class ExpenseNotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter { row -> ExpenseNoteFormActivity.start(this, row.id) }
    private var notes: List<ExpenseNoteEntity> = emptyList()
    private var source: LiveData<List<ExpenseNoteEntity>>? = null
    private var query = ""
    private var rangeStart = startOfJalaliMonth()
    private var rangeEnd = endOfDay(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.canEdit()) { finish(); return }
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_expenses)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.chipScroller.visibility = View.GONE

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.etSearch.doAfterTextChanged { query = it?.toString().orEmpty().trim(); render() }

        binding.fabAdd.visibility = View.VISIBLE
        binding.fabAdd.text = getString(R.string.expense_add)
        binding.fabAdd.setOnClickListener { ExpenseNoteFormActivity.start(this, null) }
        observeRange()
    }

    private fun observeRange() {
        source?.removeObservers(this)
        source = repository.expenseNotes(rangeStart, rangeEnd).also { live -> live.observe(this) { notes = it; render() } }
    }

    private fun render() {
        val visible = notes.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) || it.notes?.contains(query, ignoreCase = true) == true }
        adapter.submitList(visible.map { n ->
            FleetRow.Entry(
                id = n.id,
                title = n.title,
                subtitle = listOfNotNull(FleetFormat.date(n.date), n.notes).joinToString(" · ").toPersianDigitsInString(),
                iconRes = R.drawable.ic_report,
                accentRes = R.color.accent_personnel,
                accentBgRes = R.color.accent_personnel_bg,
                badgeText = formatAmount(n.amount),
                badgeColorRes = R.color.concrete_300
            )
        })
        binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(if (notes.isEmpty()) R.string.expenses_empty else R.string.list_no_match)
        binding.tvSummary.text = getString(
            R.string.expenses_summary,
            FleetFormat.date(rangeStart), FleetFormat.date(rangeEnd), formatAmount(visible.sumOf { it.amount })
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
                rangeStart = startOfDay(start)
                rangeEnd = endOfDay(end)
                observeRange()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val MENU_RANGE = 1

        fun formatAmount(amount: Long): String = "%,d ریال".format(java.util.Locale.US, amount).toPersianDigitsInString()

        private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        private fun endOfDay(millis: Long): Long = startOfDay(millis) + TimeUnit.DAYS.toMillis(1) - 1

        private fun startOfJalaliMonth(): Long {
            val today = JalaliCalendar.toJalali(System.currentTimeMillis())
            return startOfDay(JalaliCalendar.jalaliToMillis(today.year, today.month, 1))
        }
    }
}
