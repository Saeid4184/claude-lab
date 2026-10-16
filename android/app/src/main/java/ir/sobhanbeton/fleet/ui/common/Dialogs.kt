package ir.sobhanbeton.fleet.ui.common

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.FleetRepository

/** Dialog/toast plumbing shared by the fleet screens. */
object Dialogs {

    /** Reports a write's outcome the way the security app did: success while offline says the
     *  record is on the phone and will upload by itself. */
    fun <T> Activity.reportSave(result: Result<T>, onSuccess: (T) -> Unit = {}) {
        result.onSuccess {
            val online = FleetRepository.get(this).isOnline()
            Toast.makeText(this, if (online) R.string.saved else R.string.saved_offline, Toast.LENGTH_SHORT).show()
            onSuccess(it)
        }.onFailure {
            Toast.makeText(this, it.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
        }
    }

    fun Activity.confirm(title: Int, message: CharSequence, positive: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** One-line optional text prompt (e.g. a reason for a status change). */
    fun Activity.promptText(title: CharSequence, hint: Int, initial: String?, onDone: (String?) -> Unit) {
        val layout = TextInputLayout(this).apply {
            this.hint = getString(hint)
            val pad = resources.getDimensionPixelSize(R.dimen.dim_20)
            setPadding(pad, pad / 2, pad, 0)
        }
        val field = TextInputEditText(layout.context).apply { setText(initial) }
        layout.addView(field)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(R.string.btn_ok) { _, _ -> onDone(field.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
