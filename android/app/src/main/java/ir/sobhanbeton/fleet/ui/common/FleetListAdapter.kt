package ir.sobhanbeton.fleet.ui.common

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.databinding.ItemFleetEntryBinding

/** A row of the shared list screen: a section header or an entry card. */
sealed class FleetRow {
    abstract val key: String

    data class Header(val title: String) : FleetRow() {
        override val key get() = "h:$title"
    }

    data class Entry(
        val id: String,
        val title: CharSequence,
        val subtitle: CharSequence,
        @DrawableRes val iconRes: Int,
        @ColorRes val accentRes: Int,
        @ColorRes val accentBgRes: Int,
        val badgeText: String? = null,
        @ColorRes val badgeColorRes: Int = R.color.concrete_300,
        val warning: String? = null
    ) : FleetRow() {
        override val key get() = "e:$id"
    }
}

/** Grouped card list used by the vehicles, drivers and expiring-documents screens (same look
 *  as the security app's roster: icon chip, bold title, muted subtitle, status badge). */
class FleetListAdapter(
    private val onClick: (FleetRow.Entry) -> Unit
) : ListAdapter<FleetRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is FleetRow.Header -> TYPE_HEADER
        is FleetRow.Entry -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_section_header, parent, false) as TextView)
        } else {
            EntryVH(ItemFleetEntryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is FleetRow.Header -> (holder as HeaderVH).view.text = row.title
            is FleetRow.Entry -> (holder as EntryVH).bind(row, onClick)
        }
    }

    class HeaderVH(val view: TextView) : RecyclerView.ViewHolder(view)

    class EntryVH(private val binding: ItemFleetEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: FleetRow.Entry, onClick: (FleetRow.Entry) -> Unit) {
            val context = binding.root.context
            binding.tvTitle.text = row.title
            binding.tvSubtitle.text = row.subtitle
            binding.ivIcon.setImageResource(row.iconRes)
            binding.ivIcon.setColorFilter(ContextCompat.getColor(context, row.accentRes), PorterDuff.Mode.SRC_IN)
            binding.iconChip.background?.mutate()?.setTint(ContextCompat.getColor(context, row.accentBgRes))

            binding.tvBadge.visibility = if (row.badgeText != null) View.VISIBLE else View.GONE
            binding.tvBadge.text = row.badgeText
            binding.tvBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, row.badgeColorRes))

            binding.tvWarning.visibility = if (row.warning != null) View.VISIBLE else View.GONE
            binding.tvWarning.text = row.warning

            binding.cardRoot.setOnClickListener { onClick(row) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1

        val DIFF = object : DiffUtil.ItemCallback<FleetRow>() {
            override fun areItemsTheSame(oldItem: FleetRow, newItem: FleetRow) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: FleetRow, newItem: FleetRow) = oldItem == newItem
        }
    }
}
