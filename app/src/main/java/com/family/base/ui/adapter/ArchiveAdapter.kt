package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.ItemEntity
import com.family.base.util.ImageUtils

class ArchiveAdapter(
    private val onItemClick: (ItemEntity) -> Unit,
    private val onRestoreClick: (ItemEntity) -> Unit
) : RecyclerView.Adapter<ArchiveAdapter.ViewHolder>() {

    private var items: List<ItemEntity> = emptyList()

    fun submitList(newItems: List<ItemEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_archive_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // Название: если это «часть» от списания → пометка
        val nameText = if (!item.originalId.isNullOrEmpty()) {
            "↩ ${item.name}"
        } else {
            item.name
        }
        holder.name.text = nameText

        holder.info.text = buildInfoText(item)
        holder.reason.text = getReasonText(item.archivedReason)

        holder.icon.visibility = View.VISIBLE
        holder.icon.load(null)

        val localFile = ImageUtils.getLocalImageFile(context, item.id)
        if (localFile != null && localFile.exists()) {
            holder.icon.load(localFile) {
                crossfade(true)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }
        } else {
            holder.icon.load(R.drawable.ic_item_default)
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }

        holder.btnRestore.setOnClickListener {
            onRestoreClick(item)
        }
    }

    /**
     * Информационная строка под названием:
     * «×N | 300 ₽»
     * Если количество = 1 → только цена (если есть).
     */
    private fun buildInfoText(item: ItemEntity): String {
        val parts = mutableListOf<String>()

        // Всегда показываем количество, если > 1
        if (item.quantity > 1) {
            parts.add("×${item.quantity}")
        }

        // Цена за всё количество (если задана)
        if (item.price != null && item.price != 0.0) {
            val totalPrice = item.price * item.quantity
            val priceStr = if (totalPrice % 1.0 == 0.0) {
                "${totalPrice.toInt()} ₽"
            } else {
                String.format("%.2f ₽", totalPrice)
            }
            parts.add(priceStr)
        }

        return parts.joinToString(" | ")
    }

    /**
     * Текст причины архивации (правый чип).
     */
    private fun getReasonText(reason: String?): String {
        return when (reason) {
            "used_up" -> "🧴 Израсходовано"
            "eaten" -> "🍽 Съедено"
            "broken" -> "🔧 Сломано"
            "thrown" -> "🗑 Выброшено"
            "gifted" -> "🎁 Подарено"
            "sold" -> "💰 Продано"
            "expired" -> "⏰ Истёк срок"
            "other" -> "📦 Другое"
            else -> ""
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val info: TextView = view.findViewById(R.id.info)
        val reason: TextView = view.findViewById(R.id.reason)
        val btnRestore: ImageButton = view.findViewById(R.id.btnRestore)
    }
}
