package ru.dsh.byediautostart

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.dsh.byediautostart.databinding.ItemAppBinding

class AppListAdapter(
    private val selected: MutableSet<String>,
    private val onToggle: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    var onCountChanged: ((Int) -> Unit)? = null

    private var all: List<InstalledApp> = emptyList()
    private var visible: List<InstalledApp> = emptyList()

    fun submit(items: List<InstalledApp>) {
        all = items
        applyFilter("")
    }

    fun filter(query: String) {
        applyFilter(query)
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        visible = if (q.isEmpty()) {
            all
        } else {
            all.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
        }
        notifyDataSetChanged()
        onCountChanged?.invoke(visible.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(visible[position])
    }

    override fun getItemCount(): Int = visible.size

    inner class Holder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener { toggle(bindingAdapterPosition) }
        }

        fun bind(item: InstalledApp) {
            binding.tvLabel.text = item.label
            binding.ivIcon.setImageDrawable(item.icon)
            binding.cbSelected.isChecked = item.packageName in selected
        }
    }

    private fun toggle(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val item = visible.getOrNull(position) ?: return
        val nowSelected = item.packageName !in selected
        if (nowSelected) selected += item.packageName else selected -= item.packageName
        notifyItemChanged(position)
        onToggle(item.packageName, nowSelected)
    }
}
