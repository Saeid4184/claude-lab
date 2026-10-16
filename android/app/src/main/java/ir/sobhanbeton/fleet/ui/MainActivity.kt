package ir.sobhanbeton.fleet.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.AuthRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.databinding.ActivityMainBinding
import ir.sobhanbeton.fleet.util.AnimUtils
import ir.sobhanbeton.fleet.util.NetworkMonitor
import ir.sobhanbeton.fleet.util.toPersianDigits
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Home screen: a menu of the fleet-management sections (vehicles, drivers, fuel, repairs,
 *  periodic service, daily work, incidents, reports). Sections not built yet are listed with
 *  the roadmap phase they arrive in. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository

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
            item.comingInPhase?.let { phase ->
                Toast.makeText(
                    this,
                    getString(R.string.home_coming_in_phase, phase.toPersianDigits(minWidth = 1)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        AnimUtils.runLayoutAnimation(binding.rvHomeMenu)

        observeConnectivity()
    }

    private val homeMenuItems: List<HomeMenuItem> = listOf(
        HomeMenuItem(
            HomeDestination.VEHICLES, R.string.home_vehicles, R.string.home_subtitle_vehicles,
            R.drawable.ic_machinery, R.color.accent_machinery, R.color.accent_machinery_bg,
            comingInPhase = 1
        ),
        HomeMenuItem(
            HomeDestination.DRIVERS, R.string.home_drivers, R.string.home_subtitle_drivers,
            R.drawable.ic_driver, R.color.accent_driver, R.color.accent_driver_bg,
            comingInPhase = 1
        ),
        HomeMenuItem(
            HomeDestination.FUEL, R.string.home_fuel, R.string.home_subtitle_fuel,
            R.drawable.ic_inventory, R.color.accent_item_log, R.color.accent_item_log_bg,
            comingInPhase = 2
        ),
        HomeMenuItem(
            HomeDestination.REPAIRS, R.string.home_repairs, R.string.home_subtitle_repairs,
            R.drawable.ic_inspection, R.color.accent_inspection, R.color.accent_inspection_bg,
            comingInPhase = 3
        ),
        HomeMenuItem(
            HomeDestination.SERVICES, R.string.home_services, R.string.home_subtitle_services,
            R.drawable.ic_setup, R.color.accent_personnel, R.color.accent_personnel_bg,
            comingInPhase = 4
        ),
        HomeMenuItem(
            HomeDestination.DAILY_WORK, R.string.home_daily_work, R.string.home_subtitle_daily_work,
            R.drawable.ic_calendar, R.color.accent_visitor, R.color.accent_visitor_bg,
            comingInPhase = 5
        ),
        HomeMenuItem(
            HomeDestination.INCIDENTS, R.string.home_incidents, R.string.home_subtitle_incidents,
            R.drawable.ic_report, R.color.accent_report, R.color.accent_report_bg,
            comingInPhase = 5
        ),
        HomeMenuItem(
            HomeDestination.REPORTS, R.string.home_reports, R.string.home_subtitle_reports,
            R.drawable.ic_dashboard, R.color.accent_machinery, R.color.accent_machinery_bg,
            comingInPhase = 6
        )
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
                authRepository.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
