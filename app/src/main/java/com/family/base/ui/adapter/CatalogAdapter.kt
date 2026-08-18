package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.databinding.ItemCatalogEntryBinding
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
        val binding = ItemCatalogEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val context = holder.itemView.context

        when (entry) {
            is FolderEntity -> {
                // ===== ПАПКИ: сбрасываем предыдущую загрузку =====
                holder.binding.icon.load(null)

                // ===== ИСПРАВЛЕНИЕ: проверяем наличие локальной иконки =====
                val iconFile = ImageUtils.getLocalImageFile(context, "folder_${entry.id}")
                if (iconFile != null && iconFile.exists()) {
                    holder.binding.icon.load(iconFile) {
                        crossfade(true)
                        placeholder(R.drawable.ic_folder_48)
                        error(R.drawable.ic_folder_48)
                    }
                } else {
                    holder.binding.icon.load(R.drawable.ic_folder_48) {
                        crossfade(false)
                    }
                }

                holder.binding.name.text = entry.name
                holder.binding.info.text = ""
                holder.binding.colorBar.setBackgroundColor(
                    context.resources.getColor(R.color.colorNormal, context.theme)
                )
                holder.itemView.setOnClickListener { onFolderClick(entry) }
                holder.itemView.setOnLongClickListener { onFolderLongClick(entry); true }
            }
            is ItemEntity -> {
                // ===== ПРЕДМЕТЫ: очищаем перед загрузкой =====
                holder.binding.icon.load(null)
                holder.binding.icon.setImageDrawable(null)

                val localFile = ImageUtils.getLocalImageFile(context, entry.id)
                if (localFile != null && localFile.exists()) {
                    holder.binding.icon.load(localFile) {
                        crossfade(true)
                        placeholder(R.drawable.ic_item_default_48)
                        error(R.drawable.ic_item_default_48)
                    }
                } else {
                    holder.binding.icon.setImageResource(R.drawable.ic_item_default_48)
                }

                holder.binding.name.text = entry.name
                val infoText = buildInfoText(entry)
                holder.binding.info.text = infoText

                val colorRes = when {
                    entry.isExpired -> R.color.colorExpired
                    entry.daysUntilExpiry in 0..3 -> R.color.colorWarning
                    else -> R.color.colorNormal
                }
                holder.binding.colorBar.setBackgroundColor(
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

    inner class ViewHolder(val binding: ItemCatalogEntryBinding) :
        RecyclerView.ViewHolder(binding.root)
}
