package ir.sobhanbeton.fleet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.FactoryApp
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.AuthRepository
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.UserProfile
import ir.sobhanbeton.fleet.data.UserRole
import ir.sobhanbeton.fleet.databinding.ActivityLoginBinding
import ir.sobhanbeton.fleet.util.AnimUtils
import kotlinx.coroutines.launch

/**
 * The app's true entry point (see AndroidManifest — this holds the LAUNCHER intent-filter, not
 * MainActivity). Signs the user in, or creates a new account, then loads their role from
 * Firestore before handing off to MainActivity. A PENDING account (registered but not yet
 * approved by the expert) is told so and kept here.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authRepository: AuthRepository
    private var isSignUpMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authRepository = AuthRepository(this)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!FactoryApp.firebaseConfigured) {
            // Built without google-services.json — nothing below can work, so say exactly why.
            binding.btnPrimary.isEnabled = false
            binding.btnToggleMode.isEnabled = false
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.firebase_missing_title)
                .setMessage(R.string.firebase_missing_message)
                .setCancelable(false)
                .setPositiveButton(R.string.btn_ok) { _, _ -> finish() }
                .show()
            return
        }

        binding.btnPrimary.setOnClickListener { if (isSignUpMode) attemptSignUp() else attemptSignIn() }
        binding.btnToggleMode.setOnClickListener { toggleMode() }
        AnimUtils.startCoinSpin(binding.ivLoginLogo)
        AnimUtils.applyPressFeedback(binding.btnPrimary)

        // If Firebase already remembers a signed-in user (app reopened), skip straight past login.
        if (authRepository.isSignedIn()) {
            setLoading(true)
            lifecycleScope.launch {
                val result = authRepository.restoreSessionIfSignedIn()
                setLoading(false)
                result?.getOrNull()?.let { onSignedIn(it) }
                // If it still failed (e.g. profile doc genuinely missing, or offline on a
                // device that's never loaded this profile before to cache it), stay on the
                // login screen normally -- see AuthRepository.restoreSessionIfSignedIn for the
                // offline fallback this already tried first.
            }
        }
    }

    private fun toggleMode() {
        isSignUpMode = !isSignUpMode
        binding.tilName.visibility = if (isSignUpMode) View.VISIBLE else View.GONE
        binding.btnPrimary.text = getString(if (isSignUpMode) R.string.login_btn_signup else R.string.login_btn_signin)
        binding.btnToggleMode.text = getString(if (isSignUpMode) R.string.login_toggle_to_signin else R.string.login_toggle_to_signup)
    }

    private fun attemptSignIn() {
        val email = binding.etEmail.text?.toString().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        if (!validateEmailPassword(email, password)) return

        setLoading(true)
        lifecycleScope.launch {
            val result = authRepository.signIn(email, password)
            setLoading(false)
            result.onSuccess { profile ->
                onSignedIn(profile)
            }.onFailure { error ->
                toast(error.message ?: getString(R.string.error_generic))
            }
        }
    }

    private fun attemptSignUp() {
        val name = binding.etName.text?.toString().orEmpty()
        val email = binding.etEmail.text?.toString().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (name.isBlank()) {
            binding.tilName.error = getString(R.string.error_name_empty)
            return
        }
        binding.tilName.error = null
        if (!validateEmailPassword(email, password)) return

        setLoading(true)
        lifecycleScope.launch {
            val result = authRepository.signUp(email, password, name)
            setLoading(false)
            result.onSuccess { profile ->
                toast(getString(R.string.login_signup_success, profile.role.displayName))
                onSignedIn(profile)
            }.onFailure { error ->
                toast(error.message ?: getString(R.string.error_generic))
            }
        }
    }

    private fun validateEmailPassword(email: String, password: String): Boolean {
        var ok = true
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = getString(R.string.login_error_email)
            ok = false
        } else {
            binding.tilEmail.error = null
        }
        if (password.length < 6) {
            binding.tilPassword.error = getString(R.string.login_error_password)
            ok = false
        } else {
            binding.tilPassword.error = null
        }
        return ok
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnPrimary.isEnabled = !loading
        binding.btnToggleMode.isEnabled = !loading
    }

    /** Approved accounts go straight in; a PENDING one is signed back out with an explanation,
     *  so the next attempt (after the expert approves it) re-reads the role fresh. */
    private fun onSignedIn(profile: UserProfile) {
        if (profile.role == UserRole.PENDING) {
            authRepository.signOut()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.login_pending_title)
                .setMessage(R.string.login_pending_message)
                .setPositiveButton(R.string.btn_ok, null)
                .show()
            return
        }
        Session.set(profile)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
