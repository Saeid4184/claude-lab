package ir.sobhanbeton.fleet.ui.housing

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.DriverStatus
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.HousingRoomEntity
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.databinding.ActivityFleetListBinding
import ir.sobhanbeton.fleet.databinding.DialogRoomBinding
import ir.sobhanbeton.fleet.ui.common.Dialogs.confirm
import ir.sobhanbeton.fleet.ui.common.Dialogs.reportSave
import ir.sobhanbeton.fleet.ui.common.FormFields.longOrNull
import ir.sobhanbeton.fleet.ui.common.FormFields.textOrNull
import ir.sobhanbeton.fleet.ui.common.FleetListAdapter
import ir.sobhanbeton.fleet.ui.common.FleetRow
import ir.sobhanbeton.fleet.ui.drivers.DriverDetailActivity
import ir.sobhanbeton.fleet.util.ExcelExport
import ir.sobhanbeton.fleet.util.XlsxWriter
import ir.sobhanbeton.fleet.util.toPersianDigits
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.launch

/**
 * "محل اسکان رانندگان": every room with who lives in it (and how full it is), then the active
 * drivers without a room. The expert taps a room to choose its occupants, edit or delete it;
 * a driver can also be moved from their own screen. Excel export of the whole list.
 */
class HousingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetListBinding
    private lateinit var repository: FleetRepository
    private val adapter = FleetListAdapter { row -> onRowClicked(row.id) }
    private var rooms: List<HousingRoomEntity> = emptyList()
    private var drivers: List<DriverEntity> = emptyList()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FleetRepository.get(this)
        binding = ActivityFleetListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.home_housing)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.chipScroller.visibility = View.GONE

        binding.etSearch.doAfterTextChanged { query = it?.toString().orEmpty().trim(); render() }
        if (Session.canEdit()) {
            binding.fabAdd.visibility = View.VISIBLE
            binding.fabAdd.text = getString(R.string.housing_add_room)
            binding.fabAdd.setOnClickListener { editRoom(null) }
        }
        repository.housingRooms().observe(this) { rooms = it; render() }
        repository.drivers().observe(this) { drivers = it; render() }
    }

    /** Drivers who still work here — a driver who has left isn't offered a bed. */
    private fun activeDrivers() = drivers.filter { it.statusEnum() != DriverStatus.LEFT }

    private fun occupants(roomId: String) = drivers.filter { it.roomId == roomId }.sortedBy { it.name }

    private fun render() {
        val q = query.toPersianDigitsInString()
        fun matches(text: String) = query.isEmpty() || text.contains(query, ignoreCase = true) || text.toPersianDigitsInString().contains(q)

        val rows = mutableListOf<FleetRow>()
        val roomIds = rooms.map { it.id }.toSet()
        rooms.filter { r -> matches(r.name) || occupants(r.id).any { matches(it.name) } }
            .sortedWith(compareBy({ it.name.length }, { it.name }))
            .forEach { r -> rows += roomRow(r) }
        // A roomId pointing at a deleted room (deleted on the other phone mid-sync) counts as none.
        val unhoused = activeDrivers().filter { it.roomId == null || it.roomId !in roomIds }.sortedBy { it.name }
        val unhousedVisible = unhoused.filter { matches(it.name) }
        if (unhousedVisible.isNotEmpty()) {
            rows += FleetRow.Header(getString(R.string.housing_unhoused))
            unhousedVisible.forEach { d ->
                rows += FleetRow.Entry(
                    id = DRIVER_PREFIX + d.id,
                    title = d.name,
                    subtitle = d.currentVehiclePlate?.toPersianDigitsInString() ?: getString(R.string.no_vehicle),
                    iconRes = R.drawable.ic_driver,
                    accentRes = R.color.accent_driver,
                    accentBgRes = R.color.accent_driver_bg
                )
            }
        }
        adapter.submitList(rows)
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(if (rooms.isEmpty()) R.string.housing_empty else R.string.list_no_match)
        val housed = drivers.count { it.roomId != null && it.roomId in roomIds }
        binding.tvSummary.text = getString(R.string.housing_summary, rooms.size, housed, unhoused.size).toPersianDigitsInString()
    }

    private fun roomRow(r: HousingRoomEntity): FleetRow.Entry {
        val people = occupants(r.id)
        val full = r.capacity != null && people.size >= r.capacity
        return FleetRow.Entry(
            id = ROOM_PREFIX + r.id,
            title = r.name.toPersianDigitsInString(),
            subtitle = people.joinToString("، ") { it.name }.ifEmpty { getString(R.string.housing_room_empty) } +
                (r.notes?.let { "\n$it" } ?: ""),
            iconRes = R.drawable.ic_home,
            accentRes = R.color.accent_visitor,
            accentBgRes = R.color.accent_visitor_bg,
            badgeText = (r.capacity?.let { "${people.size} از $it" } ?: people.size.toString()).toPersianDigitsInString(),
            badgeColorRes = when {
                full -> R.color.safety_amber_dark
                people.isEmpty() -> R.color.concrete_300
                else -> R.color.status_green
            }
        )
    }

    private fun onRowClicked(rowId: String) {
        when {
            rowId.startsWith(DRIVER_PREFIX) -> DriverDetailActivity.start(this, rowId.removePrefix(DRIVER_PREFIX))
            rowId.startsWith(ROOM_PREFIX) && Session.canEdit() -> {
                val room = rooms.firstOrNull { it.id == rowId.removePrefix(ROOM_PREFIX) } ?: return
                val actions = arrayOf(getString(R.string.housing_occupants), getString(R.string.action_edit), getString(R.string.action_delete))
                MaterialAlertDialogBuilder(this)
                    .setTitle(room.name.toPersianDigitsInString())
                    .setItems(actions) { _, which ->
                        when (which) {
                            0 -> chooseOccupants(room)
                            1 -> editRoom(room)
                            2 -> deleteRoom(room)
                        }
                    }
                    .show()
            }
        }
    }

    /** Multi-choice of active drivers: this room's occupants first, then those without a room,
     *  then drivers in other rooms (checking one moves them here). */
    private fun chooseOccupants(room: HousingRoomEntity) {
        val roomNames = rooms.associate { it.id to it.name }
        val candidates = activeDrivers().sortedWith(compareBy({
            when (it.roomId) {
                room.id -> 0
                null -> 1
                else -> if (it.roomId in roomNames) 2 else 1
            }
        }, { it.name }))
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.housing_no_drivers, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { d ->
            val other = d.roomId?.takeIf { it != room.id }?.let { roomNames[it] }
            (if (other != null) "${d.name} — ${getString(R.string.housing_in_room, other)}" else d.name).toPersianDigitsInString()
        }
        val checked = BooleanArray(candidates.size) { candidates[it].roomId == room.id }
        val title = getString(R.string.housing_occupants) + " — " + room.name +
            (room.capacity?.let { " (${it.toPersianDigits(minWidth = 1)})" } ?: "")
        MaterialAlertDialogBuilder(this)
            .setTitle(title.toPersianDigitsInString())
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val ids = candidates.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                lifecycleScope.launch { reportSave(repository.setRoomOccupants(room.id, ids)) }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun editRoom(room: HousingRoomEntity?) {
        val form = DialogRoomBinding.inflate(layoutInflater)
        form.etName.setText(room?.name)
        form.etCapacity.setText(room?.capacity?.toString())
        form.etNotes.setText(room?.notes)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (room == null) R.string.housing_add_room else R.string.housing_edit_room)
            .setView(form.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
        // Validate before closing: the positive button's default listener would dismiss anyway.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = form.etName.textOrNull() ?: run { form.tilName.error = getString(R.string.error_required); return@setOnClickListener }
            val capacity = runCatching { form.etCapacity.longOrNull() }.getOrNull()?.toInt()
            val input = (room ?: HousingRoomEntity()).copy(name = name, capacity = capacity, notes = form.etNotes.textOrNull())
            lifecycleScope.launch { reportSave(repository.saveRoom(input)) { dialog.dismiss() } }
        }
    }

    private fun deleteRoom(room: HousingRoomEntity) {
        confirm(R.string.action_delete, getString(R.string.housing_delete_confirm, room.name).toPersianDigitsInString(), R.string.action_delete) {
            lifecycleScope.launch { reportSave(repository.deleteRoom(room.id)) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.export_excel)
            .setIcon(R.drawable.ic_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_EXPORT -> {
            export()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun export() {
        val roomIds = rooms.map { it.id }.toSet()
        val rows = rooms.sortedWith(compareBy({ it.name.length }, { it.name })).flatMap { r ->
            val people = occupants(r.id)
            if (people.isEmpty()) listOf(listOf(r.name, r.capacity?.toString().orEmpty(), "", "", "", r.notes.orEmpty()))
            else people.map { d -> listOf(r.name, r.capacity?.toString().orEmpty(), d.name, d.phone.orEmpty(), d.currentVehiclePlate.orEmpty(), r.notes.orEmpty()) }
        } + activeDrivers().filter { it.roomId == null || it.roomId !in roomIds }.sortedBy { it.name }.map { d ->
            listOf(getString(R.string.no_room), "", d.name, d.phone.orEmpty(), d.currentVehiclePlate.orEmpty(), "")
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.housing_empty, Toast.LENGTH_SHORT).show()
            return
        }
        ExcelExport.exportAndShare(
            this, "housing",
            listOf(XlsxWriter.Sheet(getString(R.string.home_housing), listOf("اتاق", "ظرفیت", "راننده", "تلفن", "ماشین", "توضیحات"), rows))
        )
    }

    private companion object {
        const val MENU_EXPORT = 1
        const val ROOM_PREFIX = "room:"
        const val DRIVER_PREFIX = "driver:"
    }
}
