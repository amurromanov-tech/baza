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

class LentItemsAdapter(
    private val onItemClick: (ItemEntity) -> Unit,
    private val onReturnClick: (ItemEntity) -> Unit
) : RecyclerView.Adapter<LentItemsAdapter.ViewHolder>() {

    private var items: List<ItemEntity> = emptyList()

    fun submitList(newItems: List<ItemEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lent_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.name.text = item.name

        // ===== КОМУ ВЫДАН =====
        var personText = "🤝 У ${item.lentTo ?: "—"}"
        if (!item.lentNote.isNullOrEmpty()) {
            personText += " (${item.lentNote})"
        }
        holder.person.text = personText

        // ===== ИНФО =====
        val infoParts = mutableListOf<String>()
        if (item.quantity > 1) infoParts.add("×${item.quantity}")
        if (item.price != null && item.price != 0.0) {
            infoParts.add("${(item.price * item.quantity).toInt()} ₽")
        }
        holder.info.text = infoParts.joinToString(" | ")

        // ===== ИКОНКА =====
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

        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.btnReturn.setOnClickListener { onReturnClick(item) }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val person: TextView = view.findViewById(R.id.person)
        val info: TextView = view.findViewById(R.id.info)
        val btnReturn: ImageButton = view.findViewById(R.id.btnReturn)
    }
}
