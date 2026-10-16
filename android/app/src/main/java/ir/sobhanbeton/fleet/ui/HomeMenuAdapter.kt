package ir.sobhanbeton.fleet.ui

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.databinding.ItemHomeMenuBinding
import ir.sobhanbeton.fleet.util.AnimUtils
import ir.sobhanbeton.fleet.util.GlassCard
import ir.sobhanbeton.fleet.util.toPersianDigits

/** The sections reachable from the home menu, in roadmap order. */
enum class HomeDestination {
    VEHICLES, DRIVERS, FUEL, REPAIRS, SERVICES, DAILY_WORK, INCIDENTS, REPORTS
}

data class HomeMenuItem(
    val destination: HomeDestination,
    val titleRes: Int,
    val subtitleRes: Int,
    val iconRes: Int,
    val accentColorRes: Int,
    val accentBgColorRes: Int,
    /** Roadmap phase this section ships in, while it's still unbuilt — the card is shown
     *  dimmed with a "coming in phase N" note so the menu already reflects the full plan.
     *  Null once the section exists. */
    val comingInPhase: Int? = null
)

/** Simple single-column list of tappable cards — the home screen's "menu". */
class HomeMenuAdapter(
    private val items: List<HomeMenuItem>,
    private val onClick: (HomeMenuItem) -> Unit
) : RecyclerView.Adapter<HomeMenuAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHomeMenuBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], onClick)

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemHomeMenuBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeMenuItem, onClick: (HomeMenuItem) -> Unit) {
            val context = binding.root.context
            binding.tvTitle.setText(item.titleRes)
            binding.tvSubtitle.text = item.comingInPhase?.let { phase ->
                context.getString(R.string.home_coming_in_phase, phase.toPersianDigits(minWidth = 1))
            } ?: context.getString(item.subtitleRes)
            binding.cardRoot.alpha = if (item.comingInPhase != null) 0.55f else 1f
            binding.ivIcon.setImageResource(item.iconRes)

            val accent = ContextCompat.getColor(context, item.accentColorRes)
            val background = ContextCompat.getColor(context, item.accentBgColorRes)
            binding.ivIcon.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
            binding.iconChip.background?.mutate()?.setTint(background)
            binding.colorStripe.setBackgroundColor(accent)
            GlassCard.applyColor(binding.cardRoot, accent)

            AnimUtils.applyPressFeedback(binding.cardRoot)
            binding.cardRoot.setOnClickListener { onClick(item) }
        }
    }
}
