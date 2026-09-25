package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.model.CheckItem

class CheckPreviewAdapter(
    private val onItemToggled: (CheckItem) -> Unit,
    private val onItemRemoved: (CheckItem, Int) -> Unit
) : RecyclerView.Adapter<CheckPreviewAdapter.ViewHolder>() {

    private val items = mutableListOf<CheckItem>()

    fun submitList(newItems: List<CheckItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<CheckItem> = items.toList()

    fun getSelectedItems(): List<CheckItem> = items.filter { it.isSelected }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_check_preview, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // Название
        holder.tvName.text = item.name

        // Количество + цена
        val details = buildString {
            if (item.price != null) {
                val priceStr = formatPrice(item.price!!)
                if (item.quantity > 1) {
                    append("${item.quantity} × $priceStr ₽")
                    item.totalPrice?.let { total ->
                        append(" = ${formatPrice(total)} ₽")
                    }
                } else {
                    append("$priceStr ₽")
                }
            } else {
                if (item.quantity > 1) {
                    append("${item.quantity} шт • цена не распознана")
                } else {
                    append("цена не распознана")
                }
            }
        }
        holder.tvDetails.text = details

        // CheckBox
        holder.cbSelected.setOnCheckedChangeListener(null)
        holder.cbSelected.isChecked = item.isSelected
        holder.cbSelected.setOnCheckedChangeListener { _, isChecked ->
            item.isSelected = isChecked
            onItemToggled(item)
        }

        // Клик по всей строке — переключает галочку
        holder.itemView.setOnClickListener {
            item.isSelected = !item.isSelected
            holder.cbSelected.isChecked = item.isSelected
            onItemToggled(item)
        }

        // Удалить
        holder.btnRemove.setOnClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                onItemRemoved(item, currentPosition)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatPrice(price: Double): String {
        return if (price % 1.0 == 0.0) {
            price.toInt().toString()
        } else {
            String.format("%.2f", price)
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelected: CheckBox = view.findViewById(R.id.cbSelected)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
    }
}
