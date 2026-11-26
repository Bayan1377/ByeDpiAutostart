package ru.dsh.byediautostart

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.dsh.byediautostart.databinding.ItemAlertBinding
import java.text.DateFormat
import java.util.Date

class AlertsAdapter(
    private val onClick: (AlertLog.Entry) -> Unit = {},
) : RecyclerView.Adapter<AlertsAdapter.Holder>() {

    private var items: List<AlertLog.Entry> = emptyList()

    fun submit(entries: List<AlertLog.Entry>) {
        items = entries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemAlertBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: AlertLog.Entry) {
            binding.tvAlertTitle.text = entry.title
            binding.tvAlertText.text = entry.text
            binding.tvAlertTime.text = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
            ).format(Date(entry.time))
            binding.root.isClickable = true
            binding.root.setOnClickListener { onClick(entry) }
        }
    }
}
