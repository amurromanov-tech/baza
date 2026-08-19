package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.util.ImageUtils

class CatalogAdapter(
    private val onFolderClick: (FolderEntity) -> Unit,
    private val onItemClick: (ItemEntity) -> Unit,
    private val onFolderLongClick: (FolderEntity) -> Unit,
    private val onItemLongClick: (ItemEntity) -> Unit
) : RecyclerView.Adapter<CatalogAdapter.ViewHolder>() {

    private var items: List<Any> = emptyList()

    fun submitList(newItems: List<Any>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_catalog_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val context = holder.itemView.context

        when (entry) {
            is FolderEntity -> {
                holder.icon.load(null)

                val iconFile = ImageUtils.getLocalImageFile(context, "folder_${entry.id}")
                if (iconFile != null && iconFile.exists()) {
                    holder.icon.load(iconFile) {
                        crossfade(true)
                        placeholder(R.drawable.ic_folder_48)
                        error(R.drawable.ic_folder_48)
                    }
                } else {
                    holder.icon.load(R.drawable.ic_folder_48) {
                        crossfade(false)
                    }
                }

                holder.name.text = entry.name
                holder.info.text = ""
                holder.colorBar.setBackgroundColor(
                    context.resources.getColor(R.color.colorNormal, context.theme)
                )
                holder.itemView.setOnClickListener { onFolderClick(entry) }
                holder.itemView.setOnLongClickListener { onFolderLongClick(entry); true }
            }
            is ItemEntity -> {
                holder.icon.load(null)

                val localFile = ImageUtils.getLocalImageFile(context, entry.id)
                if (localFile != null && localFile.exists()) {
                    holder.icon.load(localFile) {
                        crossfade(true)
                        placeholder(R.drawable.ic_item_default_48)
                        error(R.drawable.ic_item_default_48)
                    }
                } else {
                    holder.icon.load(R.drawable.ic_item_default_48) {
                        crossfade(false)
                    }
                }

                holder.name.text = entry.name
                val infoText = buildInfoText(entry)
                holder.info.text = infoText

                val colorRes = when {
                    entry.isExpired -> R.color.colorExpired
                    entry.daysUntilExpiry in 0..3 -> R.color.colorWarning
                    else -> R.color.colorNormal
                }
                holder.colorBar.setBackgroundColor(
                    context.resources.getColor(colorRes, context.theme)
                )

                holder.itemView.setOnClickListener { onItemClick(entry) }
                holder.itemView.setOnLongClickListener { onItemLongClick(entry); true }
            }
        }
    }

    private fun buildInfoText(item: ItemEntity): String {
        val parts = mutableListOf<String>()

        if (item.quantity > 1) {
            parts.add("×${item.quantity}")
        }

        when {
            item.isExpired -> parts.add("Просрочен")
            item.daysUntilExpiry != Int.MAX_VALUE && item.daysUntilExpiry <= 3 -> {
                parts.add("Скоро просрочка (${item.daysUntilExpiry} дн.)")
            }
        }

        if (item.price != null && item.price != 0.0) {
            val priceStr = if (item.price % 1.0 == 0.0) {
                item.price.toInt().toString()
            } else {
                String.format("%.2f", item.price)
            }
            parts.add("${priceStr} ₽")
        }

        return parts.joinToString(" | ")
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val info: TextView = view.findViewById(R.id.info)
        val colorBar: View = view.findViewById(R.id.colorBar)
    }
}
