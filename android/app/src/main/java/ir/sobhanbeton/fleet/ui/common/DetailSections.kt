package ir.sobhanbeton.fleet.ui.common

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.databinding.ItemDetailRowBinding

/** Builds the label/value cards of the shared detail screen into [container], reusing the
 *  Settings screen's section-header and card styles so it matches the rest of the app. */
class DetailSections(private val container: LinearLayout) {

    private val inflater = LayoutInflater.from(container.context)
    private var currentCard: LinearLayout? = null

    fun clear() {
        container.removeAllViews()
        currentCard = null
    }

    fun section(title: String) {
        // The style's layout_* items only apply when inflated from XML, so mirror them here.
        val res = container.resources
        val header = TextView(container.context, null, 0, R.style.SettingsSectionHeader).apply { text = title }
        container.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = res.getDimensionPixelSize(R.dimen.dim_20)
            marginEnd = res.getDimensionPixelSize(R.dimen.dim_20)
            topMargin = res.getDimensionPixelSize(R.dimen.dim_20)
            bottomMargin = res.getDimensionPixelSize(R.dimen.dim_8)
        })
        val card = CardView(container.context, null, 0).apply {
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorSurface))
            radius = resources.getDimension(R.dimen.dim_16)
            cardElevation = 0f
        }
        val inner = LinearLayout(container.context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(inner)
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            val margin = container.resources.getDimensionPixelSize(R.dimen.dim_16)
            marginStart = margin
            marginEnd = margin
        }
        container.addView(card, params)
        currentCard = inner
    }

    fun row(label: String, value: CharSequence, @ColorRes valueColor: Int? = null, onClick: (() -> Unit)? = null) {
        val card = checkNotNull(currentCard) { "section() first" }
        val row = ItemDetailRowBinding.inflate(inflater, card, false)
        row.tvLabel.text = label
        row.tvValue.text = value
        valueColor?.let { row.tvValue.setTextColor(ContextCompat.getColor(container.context, it)) }
        if (onClick != null) {
            row.tvValue.setTextIsSelectable(false)
            row.root.setOnClickListener { onClick() }
            row.root.isClickable = true
            row.root.setBackgroundResource(android.R.drawable.list_selector_background)
        }
        card.addView(row.root)
    }

    /** A muted single line, e.g. "no history yet". */
    fun note(text: String) {
        val card = checkNotNull(currentCard) { "section() first" }
        val pad = container.resources.getDimensionPixelSize(R.dimen.dim_16)
        card.addView(TextView(container.context).apply {
            this.text = text
            setPadding(pad, pad / 2, pad, pad / 2)
            setTextColor(ContextCompat.getColor(context, R.color.concrete_500))
            textSize = 13f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
    }
}
