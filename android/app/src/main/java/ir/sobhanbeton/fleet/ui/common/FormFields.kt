package ir.sobhanbeton.fleet.ui.common

import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.util.normalizeDigitsForParsing

/** Small helpers shared by the vehicle and driver forms. */
object FormFields {

    /** Trimmed text, or null when blank (so optional fields are stored as null, not ""). */
    fun EditText.textOrNull(): String? = text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** Parses a whole number typed with Persian, Arabic or Latin digits; null when blank.
     *  Throws [NumberFormatException] for anything else so the form can flag it. */
    fun EditText.longOrNull(): Long? = textOrNull()?.normalizeDigitsForParsing()?.replace(",", "")?.replace("٬", "")?.toLong()

    /** A fixed-choice dropdown. Returns a getter for the selected index. */
    fun bindDropdown(field: AutoCompleteTextView, labels: List<String>, selectedIndex: Int): () -> Int {
        field.setAdapter(ArrayAdapter(field.context, R.layout.item_dropdown_option, labels))
        field.setText(labels[selectedIndex], false)
        return { labels.indexOf(field.text.toString()).coerceAtLeast(0) }
    }

    /** Free text with suggestions (e.g. existing group labels). */
    fun bindSuggestions(field: AutoCompleteTextView, suggestions: List<String>) {
        field.setAdapter(ArrayAdapter(field.context, R.layout.item_dropdown_option, suggestions.distinct().sorted()))
    }

    /**
     * A tap-to-pick Jalali date field. Holds its value itself; read it with the returned getter.
     */
    fun bindDateField(layout: TextInputLayout, field: EditText, initial: Long?): () -> Long? {
        var value = initial
        fun render() = field.setText(value?.let { FleetFormat.date(it) } ?: "")
        render()
        val open = {
            JalaliDatePickerDialog.show(
                field.context,
                layout.hint ?: "",
                value,
                onPicked = { value = it; render() },
                onCleared = { value = null; render() }
            )
        }
        field.setOnClickListener { open() }
        layout.setEndIconOnClickListener { open() }
        return { value }
    }
}
