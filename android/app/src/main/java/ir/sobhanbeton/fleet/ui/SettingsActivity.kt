package ir.sobhanbeton.fleet.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.AuthRepository
import ir.sobhanbeton.fleet.data.CloudSettings
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.UserProfile
import ir.sobhanbeton.fleet.data.UserRole
import ir.sobhanbeton.fleet.databinding.ActivitySettingsBinding
import ir.sobhanbeton.fleet.notify.DailyAlertsWorker
import ir.sobhanbeton.fleet.util.AnimUtils
import ir.sobhanbeton.fleet.util.AppPreferences
import kotlinx.coroutines.launch

/** Theme/interaction preferences, the shared AI key, and (expert only) approving accounts. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authRepository = AuthRepository(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.settings_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Load current values without triggering the listeners below.
        binding.switchHaptic.isChecked = AppPreferences.isHapticEnabled(this)

        when (AppPreferences.getThemeMode(this)) {
            AppPreferences.ThemeMode.SYSTEM -> binding.radioThemeSystem.isChecked = true
            AppPreferences.ThemeMode.LIGHT -> binding.radioThemeLight.isChecked = true
            AppPreferences.ThemeMode.DARK -> binding.radioThemeDark.isChecked = true
        }

        binding.switchHaptic.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setHapticEnabled(this, checked)
        }

        binding.switchDailyAlerts.isChecked = AppPreferences.isDailyAlertsEnabled(this)
        binding.switchDailyAlerts.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setDailyAlertsEnabled(this, checked)
            if (checked) DailyAlertsWorker.schedule(this) else DailyAlertsWorker.cancel(this)
        }

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioThemeLight -> AppPreferences.ThemeMode.LIGHT
                R.id.radioThemeDark -> AppPreferences.ThemeMode.DARK
                else -> AppPreferences.ThemeMode.SYSTEM
            }
            AppPreferences.setThemeMode(this, mode)
        }

        setupAiKey()
        setupUsers()
    }

    /** The key is shared through Firestore so it's entered once; only the expert may change it,
     *  the manager's phone just picks it up. */
    private fun setupAiKey() {
        binding.etAiApiKey.setText(AppPreferences.getAiApiKey(this))
        val canEdit = Session.canEdit()
        binding.etAiApiKey.isEnabled = canEdit
        binding.btnSaveAiKey.visibility = if (canEdit) View.VISIBLE else View.GONE

        AnimUtils.applyPressFeedback(binding.btnSaveAiKey)
        binding.btnSaveAiKey.setOnClickListener {
            val key = binding.etAiApiKey.text?.toString().orEmpty()
            AppPreferences.setAiApiKey(this, key)
            Toast.makeText(this, getString(R.string.ai_key_saved), Toast.LENGTH_SHORT).show()
            lifecycleScope.launch { CloudSettings.pushAiApiKey(key) }
        }
        // If this device hasn't had a key yet, check whether one was already set elsewhere.
        if (AppPreferences.getAiApiKey(this).isBlank()) {
            lifecycleScope.launch {
                val cloudKey = CloudSettings.fetchAiApiKey()
                if (!cloudKey.isNullOrBlank()) {
                    AppPreferences.setAiApiKey(this@SettingsActivity, cloudKey)
                    binding.etAiApiKey.setText(cloudKey)
                }
            }
        }
    }

    private fun setupUsers() {
        val isExpert = Session.canEdit()
        binding.tvUsersHeader.visibility = if (isExpert) View.VISIBLE else View.GONE
        binding.cardUsers.visibility = if (isExpert) View.VISIBLE else View.GONE
        if (!isExpert) return

        AnimUtils.applyPressFeedback(binding.btnManageUsers)
        binding.btnManageUsers.setOnClickListener { showUsers() }
    }

    private fun showUsers() {
        binding.btnManageUsers.isEnabled = false
        lifecycleScope.launch {
            val result = authRepository.listUsers()
            binding.btnManageUsers.isEnabled = true
            result.onSuccess { users ->
                // The expert's own account isn't listed: demoting yourself would lock everyone out.
                val others = users.filter { it.uid != Session.currentUser?.uid }
                if (others.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, R.string.users_none, Toast.LENGTH_LONG).show()
                    return@onSuccess
                }
                val labels = others.map { "${it.name} — ${it.role.displayName}\n${it.email}" }.toTypedArray()
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle(R.string.settings_manage_users)
                    .setItems(labels) { _, which -> chooseRole(others[which]) }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }.onFailure { error ->
                Toast.makeText(this@SettingsActivity, error.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Only MANAGER (approve, read-only) or PENDING (revoke access) are offered — there is
     *  exactly one EXPERT, the account that set the project up. */
    private fun chooseRole(user: UserProfile) {
        val roles = listOf(UserRole.MANAGER, UserRole.PENDING)
        val labels = roles.map { it.displayName }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(user.name)
            .setSingleChoiceItems(labels, roles.indexOf(user.role)) { dialog, which ->
                dialog.dismiss()
                val role = roles[which]
                if (role == user.role) return@setSingleChoiceItems
                lifecycleScope.launch {
                    authRepository.setUserRole(user.uid, role)
                        .onSuccess {
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.users_role_changed, user.name, role.displayName),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .onFailure { error ->
                            Toast.makeText(this@SettingsActivity, error.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
