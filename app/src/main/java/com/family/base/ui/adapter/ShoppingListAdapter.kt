package com.family.base.ui.adapter

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.local.entity.ItemEntity
import com.google.android.material.checkbox.MaterialCheckBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Адаптер списка «🛒 Список покупок».
 *
 * Полный UX-клон TaskAdapter из «🏠 Домашних дел»:
 *  - выполненные (купленные) покупки зачёркнуты и бледные;
 *  - тап по чекбоксу → onToggleBought (переместить в /Покупки/ + открыть карточку);
 *  - тап по карточке → onEdit (открыть ItemDetailActivity в edit-режиме);
 *  - меню (⋮) → редактировать / удалить.
 *
 * Мета-строка: «➕ Кто добавил · 📅 дд.мм.гггг».
 * Третья строка: description (примечание), если не пусто.
 *
 * Приоритет и количество в строке НЕ показываются.
 */
class ShoppingListAdapter(
    private val onToggleBought: (ItemEntity) -> Unit,
    private val onEdit: (ItemEntity) -> Unit,
    private val onDelete: (ItemEntity) -> Unit
) : ListAdapter<ItemEntity, ShoppingListAdapter.ShoppingViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ItemEntity>() {
            override fun areItemsTheSame(oldItem: ItemEntity, newItem: ItemEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ItemEntity, newItem: ItemEntity): Boolean =
                oldItem == newItem
        }

        private val DATE_FMT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    }

    /**
     * 🆕 Кэш «купленных» id для отрисовки зачёркивания.
     * Устанавливается из ShoppingListActivity после загрузки.
     * Нужен потому, что у ItemEntity нет поля isDone.
     */
    private var boughtIds: Set<String> = emptySet()

    fun setBoughtIds(ids: Set<String>) {
        boughtIds = ids
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShoppingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shopping_list, parent, false)
        return ShoppingViewHolder(view)
    }

    override fun onBindViewHolder(holder: ShoppingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ShoppingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val checkDone: MaterialCheckBox = itemView.findViewById(R.id.checkDone)
        private val textTitle: TextView = itemView.findViewById(R.id.textTitle)
        private val textMeta: TextView = itemView.findViewById(R.id.textMeta)
        private val textNote: TextView = itemView.findViewById(R.id.textNote)
        private val buttonMenu: ImageButton = itemView.findViewById(R.id.buttonMenu)

        fun bind(item: ItemEntity) {
            val isBought = boughtIds.contains(item.id)

            // Название
            textTitle.text = item.name

            // ============================================================
            // Мета: «➕ Кто добавил · 📅 дд.мм.гггг»
            // ============================================================
            val metaParts = mutableListOf<String>()
            val addedDateStr = DATE_FMT.format(Date(item.addedDate))
            if (item.addedBy.isNotBlank()) {
                metaParts.add("➕ ${item.addedBy} · 📅 $addedDateStr")
            } else {
                metaParts.add("📅 $addedDateStr")
            }
            textMeta.text = metaParts.joinToString(" · ")
            textMeta.visibility = View.VISIBLE

            // Примечание (description)
            if (item.description.isNullOrEmpty()) {
                textNote.visibility = View.GONE
            } else {
                textNote.visibility = View.VISIBLE
                textNote.text = item.description
            }

            // Чекбокс
            checkDone.setOnCheckedChangeListener(null)
            checkDone.isChecked = isBought
            checkDone.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != isBought) {
                    onToggleBought(item)
                }
            }

            // Зачёркивание + бледность для купленных
            applyBoughtState(isBought)

            // Тап по карточке → редактирование
            itemView.setOnClickListener { onEdit(item) }

            // Меню (⋮)
            buttonMenu.setOnClickListener { anchor ->
                val popup = PopupMenu(itemView.context, anchor)
                popup.menu.add(0, 1, 0, "✏️ Редактировать")
                popup.menu.add(0, 2, 1, "🗑 Удалить")
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> { onEdit(item); true }
                        2 -> { onDelete(item); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }

        private fun applyBoughtState(isBought: Boolean) {
            val alpha = if (isBought) 0.4f else 1.0f
            textTitle.alpha = alpha
            textMeta.alpha = alpha
            textNote.alpha = alpha

            if (isBought) {
                textTitle.paintFlags = textTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                textTitle.paintFlags = textTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
        }
    }
}
