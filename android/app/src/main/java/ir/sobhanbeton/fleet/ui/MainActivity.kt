package ir.sobhanbeton.fleet.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.AuthRepository
import ir.sobhanbeton.fleet.data.DriverEntity
import ir.sobhanbeton.fleet.data.ExpiryAlerts
import ir.sobhanbeton.fleet.data.FleetRepository
import ir.sobhanbeton.fleet.data.FuelLogEntity
import ir.sobhanbeton.fleet.data.FuelStats
import ir.sobhanbeton.fleet.data.ServiceLogEntity
import ir.sobhanbeton.fleet.data.ServicePlanEntity
import ir.sobhanbeton.fleet.data.ServiceSchedule
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.VehicleEntity
import ir.sobhanbeton.fleet.data.VehicleStatus
import ir.sobhanbeton.fleet.databinding.ActivityMainBinding
import ir.sobhanbeton.fleet.ui.drivers.DriversActivity
import ir.sobhanbeton.fleet.ui.expenses.ExpenseNotesActivity
import ir.sobhanbeton.fleet.ui.inspections.InspectionsActivity
import ir.sobhanbeton.fleet.notify.DailyAlertsWorker
import ir.sobhanbeton.fleet.ui.repairs.WorkOrdersActivity
import ir.sobhanbeton.fleet.ui.incidents.IncidentsActivity
import ir.sobhanbeton.fleet.ui.services.ServicesActivity
import ir.sobhanbeton.fleet.ui.work.DailyWorkActivity
import ir.sobhanbeton.fleet.util.AppPreferences
import ir.sobhanbeton.fleet.ui.fuel.FuelActivity
import ir.sobhanbeton.fleet.ui.vehicles.VehiclesActivity
import ir.sobhanbeton.fleet.util.AnimUtils
import ir.sobhanbeton.fleet.util.NetworkMonitor
import ir.sobhanbeton.fleet.util.toPersianDigits
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Home screen: a menu of the fleet-management sections (vehicles, drivers, fuel, repairs,
 *  periodic service, daily work, incidents, reports). Sections not built yet are listed with
 *  the roadmap phase they arrive in. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository
    private lateinit var repository: FleetRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authRepository = AuthRepository(this)

        // Defensive: if this Activity is somehow reached without an approved session
        // (session cleared, process restarted oddly), bounce back to the login screen.
        if (!Session.isApproved()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        // Title/subtitle are drawn by our own TextViews inside the Toolbar (see
        // activity_main.xml) so the logo can be a real View we keep spinning — supportActionBar
        // is still set above purely so the options menu (⋮) keeps working.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.tvToolbarTitle.text = getString(R.string.app_name)
        AnimUtils.startCoinSpin(binding.ivToolbarLogo)

        Session.currentUser?.let { user ->
            binding.tvToolbarSubtitle.text = getString(R.string.signed_in_as_format, user.name, user.role.displayName)
            binding.tvToolbarSubtitle.visibility = android.view.View.VISIBLE
        }

        binding.rvHomeMenu.layoutManager = LinearLayoutManager(this)
        binding.rvHomeMenu.adapter = HomeMenuAdapter(homeMenuItems) { item ->
            val phase = item.comingInPhase
            if (phase != null) {
                Toast.makeText(
                    this,
                    getString(R.string.home_coming_in_phase, phase.toPersianDigits(minWidth = 1)),
                    Toast.LENGTH_SHORT
                ).show()
                return@HomeMenuAdapter
            }
            when (item.destination) {
                HomeDestination.VEHICLES -> startActivity(Intent(this, VehiclesActivity::class.java))
                HomeDestination.DRIVERS -> startActivity(Intent(this, DriversActivity::class.java))
                HomeDestination.FUEL -> startActivity(Intent(this, FuelActivity::class.java))
                HomeDestination.INSPECTIONS -> startActivity(Intent(this, InspectionsActivity::class.java))
                HomeDestination.REPAIRS -> startActivity(Intent(this, WorkOrdersActivity::class.java))
                HomeDestination.SERVICES -> startActivity(Intent(this, ServicesActivity::class.java))
                HomeDestination.DAILY_WORK -> startActivity(Intent(this, DailyWorkActivity::class.java))
                HomeDestination.INCIDENTS -> startActivity(Intent(this, IncidentsActivity::class.java))
                HomeDestination.EXPENSES -> startActivity(Intent(this, ExpenseNotesActivity::class.java))
                else -> Unit
            }
        }
        AnimUtils.runLayoutAnimation(binding.rvHomeMenu)

        repository = FleetRepository.get(this)
        repository.startSync()
        observeSummary()
        binding.cardSummary.setOnClickListener {
            startActivity(Intent(this, ExpiringDocumentsActivity::class.java))
        }

        observeConnectivity()
        setupDailyAlerts()
    }

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    /** Schedules the once-a-day reminder (if enabled) and, on Android 13+, asks once for the
     *  notification permission it needs. */
    private fun setupDailyAlerts() {
        if (!AppPreferences.isDailyAlertsEnabled(this)) return
        DailyAlertsWorker.schedule(this)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** The card above the menu: vehicles by status, and how many documents need attention. */
    private fun observeSummary() {
        var vehicles: List<VehicleEntity> = emptyList()
        var drivers: List<DriverEntity> = emptyList()
        fun render() {
            val byStatus = vehicles.groupingBy { it.statusEnum() }.eachCount()
            binding.tvCountActive.text = summaryCount(byStatus[VehicleStatus.ACTIVE] ?: 0, VehicleStatus.ACTIVE)
            binding.tvCountRepair.text = summaryCount(byStatus[VehicleStatus.IN_REPAIR] ?: 0, VehicleStatus.IN_REPAIR)
            binding.tvCountOut.text = summaryCount(byStatus[VehicleStatus.OUT_OF_SERVICE] ?: 0, VehicleStatus.OUT_OF_SERVICE)

            val alerts = ExpiryAlerts.build(vehicles, drivers)
            val expired = alerts.count { it.daysLeft < 0 }
            binding.tvExpiring.text = when {
                alerts.isEmpty() -> getString(R.string.summary_no_expiring, ExpiryAlerts.WARNING_DAYS)
                expired > 0 -> getString(R.string.summary_expiring_with_expired, alerts.size, expired)
                else -> getString(R.string.summary_expiring, alerts.size, ExpiryAlerts.WARNING_DAYS)
            }.toPersianDigitsInString()
            binding.tvExpiring.setTextColor(
                ContextCompat.getColor(this, if (expired > 0) R.color.danger_red else if (alerts.isNotEmpty()) R.color.safety_amber else R.color.concrete_500)
            )
        }
        repository.vehicles().observe(this) { vehicles = it; render() }
        repository.drivers().observe(this) { drivers = it; render() }
        observeFuelAlerts()
        observeServiceAlerts()
    }

    /** "N services overdue/due soon" line on the card (tap → services). */
    private fun observeServiceAlerts() {
        var vehicles: List<VehicleEntity> = emptyList()
        var plans: List<ServicePlanEntity> = emptyList()
        var logs: List<ServiceLogEntity> = emptyList()
        fun render() {
            val items = ServiceSchedule.all(vehicles, plans, logs)
            val overdue = items.count { it.state == ServiceSchedule.State.OVERDUE }
            val soon = items.count { it.state == ServiceSchedule.State.DUE_SOON }
            binding.tvServiceAlert.visibility = if (overdue + soon > 0) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvServiceAlert.text = getString(R.string.summary_services, overdue, soon).toPersianDigitsInString()
            binding.tvServiceAlert.setTextColor(ContextCompat.getColor(this, if (overdue > 0) R.color.danger_red else R.color.safety_amber))
        }
        repository.vehicles().observe(this) { vehicles = it; render() }
        repository.servicePlans().observe(this) { plans = it; render() }
        repository.serviceLogs().observe(this) { logs = it; render() }
        binding.tvServiceAlert.setOnClickListener { startActivity(Intent(this, ServicesActivity::class.java)) }
    }

    /** One extra line on the card when some vehicle's recent fuel use jumped (tap → fuel). */
    private fun observeFuelAlerts() {
        var vehicles: List<VehicleEntity> = emptyList()
        var logs: List<FuelLogEntity> = emptyList()
        fun render() {
            val count = FuelStats.anomalies(logs, vehicles).size
            binding.tvFuelAlert.visibility = if (count > 0) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvFuelAlert.text = getString(R.string.summary_fuel_anomalies, count).toPersianDigitsInString()
        }
        val from = System.currentTimeMillis() - TimeUnit.DAYS.toMillis((FuelStats.RECENT_DAYS + FuelStats.BASELINE_DAYS).toLong())
        repository.vehicles().observe(this) { vehicles = it; render() }
        repository.fuelSince(from).observe(this) { logs = it; render() }
        binding.tvFuelAlert.setOnClickListener { startActivity(Intent(this, FuelActivity::class.java)) }
    }

    private fun summaryCount(count: Int, status: VehicleStatus): String =
        "${count.toPersianDigits(minWidth = 1)}\n${status.displayName}"

    private val homeMenuItems: List<HomeMenuItem> = listOfNotNull(
        HomeMenuItem(
            HomeDestination.VEHICLES, R.string.home_vehicles, R.string.home_subtitle_vehicles,
            R.drawable.ic_machinery, R.color.accent_machinery, R.color.accent_machinery_bg
        ),
        HomeMenuItem(
            HomeDestination.DRIVERS, R.string.home_drivers, R.string.home_subtitle_drivers,
            R.drawable.ic_driver, R.color.accent_driver, R.color.accent_driver_bg
        ),
        HomeMenuItem(
            HomeDestination.FUEL, R.string.home_fuel, R.string.home_subtitle_fuel,
            R.drawable.ic_inventory, R.color.accent_item_log, R.color.accent_item_log_bg
        ),
        HomeMenuItem(
            HomeDestination.INSPECTIONS, R.string.home_inspections, R.string.home_subtitle_inspections,
            R.drawable.ic_inspection, R.color.accent_inspection, R.color.accent_inspection_bg
        ),
        HomeMenuItem(
            HomeDestination.REPAIRS, R.string.home_repairs, R.string.home_subtitle_repairs,
            R.drawable.ic_settings_gear, R.color.accent_report, R.color.accent_report_bg
        ),
        HomeMenuItem(
            HomeDestination.SERVICES, R.string.home_services, R.string.home_subtitle_services,
            R.drawable.ic_setup, R.color.accent_personnel, R.color.accent_personnel_bg
        ),
        HomeMenuItem(
            HomeDestination.DAILY_WORK, R.string.home_daily_work, R.string.home_subtitle_daily_work,
            R.drawable.ic_calendar, R.color.accent_visitor, R.color.accent_visitor_bg
        ),
        HomeMenuItem(
            HomeDestination.INCIDENTS, R.string.home_incidents, R.string.home_subtitle_incidents,
            R.drawable.ic_report, R.color.accent_report, R.color.accent_report_bg
        ),
        HomeMenuItem(
            HomeDestination.REPORTS, R.string.home_reports, R.string.home_subtitle_reports,
            R.drawable.ic_dashboard, R.color.accent_machinery, R.color.accent_machinery_bg,
            comingInPhase = 6
        ),
        // Private: only the expert sees (and can open) the expense notebook.
        HomeMenuItem(
            HomeDestination.EXPENSES, R.string.home_expenses, R.string.home_subtitle_expenses,
            R.drawable.ic_report, R.color.accent_personnel, R.color.accent_personnel_bg
        ).takeIf { Session.canEdit() }
    )

    /**
     * Shows the offline banner whenever there's no connection, so it's clear that whatever is
     * recorded right now is being saved on the device and will reach the server on its own
     * once the connection is back — nothing needs to be redone or resent manually.
     */
    private fun observeConnectivity() {
        lifecycleScope.launch {
            NetworkMonitor.observe(applicationContext)
                .drop(1) // the first emission is just the state at launch; no need to announce it
                .collect { online ->
                    binding.tvOfflineBanner.visibility = if (online) android.view.View.GONE else android.view.View.VISIBLE
                    if (online) {
                        Toast.makeText(this@MainActivity, R.string.online_synced_toast, Toast.LENGTH_SHORT).show()
                        FirebaseFirestore.getInstance().waitForPendingWrites()
                    }
                }
        }
        binding.tvOfflineBanner.visibility =
            if (NetworkMonitor.isOnline(applicationContext)) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_sign_out -> {
                confirmSignOut()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_out_confirm_title)
            .setMessage(R.string.sign_out_confirm_message)
            .setPositiveButton(R.string.menu_sign_out) { _, _ ->
                repository.stopSyncAndClear()
                DailyAlertsWorker.cancel(this)
                authRepository.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
