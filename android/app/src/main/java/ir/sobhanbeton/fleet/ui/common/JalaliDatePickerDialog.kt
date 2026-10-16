package ir.sobhanbeton.fleet.ui.common

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.util.JalaliCalendar
import ir.sobhanbeton.fleet.util.toPersianDigits

/**
 * Single-date Jalali picker (year / month / day wheels) for expiry and event dates. The range
 * picker carried over from the security app ([ir.sobhanbeton.fleet.ui.JalaliDateRangePickerDialog])
 * is for report ranges; picking one date years ahead (an insurance expiry) is much faster on
 * wheels than paging a month grid.
 *
 * [onCleared] (when given) adds a «پاک کردن» button for optional fields.
 */
object JalaliDatePickerDialog {

    fun show(
        context: Context,
        title: CharSequence,
        initialMillis: Long?,
        onPicked: (millis: Long) -> Unit,
        onCleared: (() -> Unit)? = null
    ) {
        val start = JalaliCalendar.toJalali(initialMillis ?: System.currentTimeMillis())
        val thisYear = JalaliCalendar.toJalali(System.currentTimeMillis()).year

        val yearPicker = NumberPicker(context).apply {
            minValue = thisYear - 30
            maxValue = thisYear + 15
            value = start.year.coerceIn(minValue, maxValue)
            setFormatter { it.toPersianDigits(minWidth = 4) }
            wrapSelectorWheel = false
        }
        val monthPicker = NumberPicker(context).apply {
            minValue = 1
            maxValue = 12
            displayedValues = JalaliCalendar.monthNames.toTypedArray()
            value = start.month
        }
        val dayPicker = NumberPicker(context).apply {
            minValue = 1
            maxValue = JalaliCalendar.daysInJalaliMonth(start.year, start.month)
            value = start.day.coerceAtMost(maxValue)
            setFormatter { it.toPersianDigits(minWidth = 1) }
        }
        fun refreshDays() {
            val max = JalaliCalendar.daysInJalaliMonth(yearPicker.value, monthPicker.value)
            if (dayPicker.value > max) dayPicker.value = max
            dayPicker.maxValue = max
        }
        yearPicker.setOnValueChangedListener { _, _, _ -> refreshDays() }
        monthPicker.setOnValueChangedListener { _, _, _ -> refreshDays() }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(dayPicker, params)
            addView(monthPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
            addView(yearPicker, params)
            setPadding(24, 24, 24, 0)
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(row)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                onPicked(JalaliCalendar.jalaliToMillis(yearPicker.value, monthPicker.value, dayPicker.value))
            }
            .setNegativeButton(R.string.btn_cancel, null)
        if (onCleared != null) builder.setNeutralButton(R.string.btn_clear) { _, _ -> onCleared() }
        builder.show()
    }
}
