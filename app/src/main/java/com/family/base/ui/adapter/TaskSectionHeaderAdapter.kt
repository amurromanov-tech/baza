package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R

/**
 * Адаптер заголовка секции в списке «🏠 Домашние дела».
 *
 * Используется вместе с TaskAdapter через ConcatAdapter:
 *   header-1, tasks-1, header-2, tasks-2, …
 *
 * Заголовок — простая строка (например, «🔴 ПРОСРОЧЕНО») + счётчик задач.
 * Сам по себе кликов не обрабатывает: список собирается в TaskListActivity,
 * а этот адаптер только рисует заголовки.
 *
 * 🆕 v20.0.0 (шаг 3): переработка окна «Домашние дела».
 */
class TaskSectionHeaderAdapter : RecyclerView.Adapter<TaskSectionHeaderAdapter.HeaderViewHolder>() {

    /**
     * Один заголовок секции. Храним только текст и счётчик —
     * сами задачи лежат в соответствующем TaskAdapter.
     */
    data class Header(
        val title: String,
        val count: Int,
        /** Стабильный id для DiffUtil / ConcatAdapter. */
        val stableId: String
    )

    private val items = mutableListOf<Header>()

    fun submit(newItems: List<Header>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task_section_header, parent, false)
        return HeaderViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val textTitle: TextView = itemView.findViewById(R.id.textSectionTitle)
        private val textCount: TextView = itemView.findViewById(R.id.textSectionCount)

        fun bind(header: Header) {
            textTitle.text = header.title
            if (header.count > 0) {
                textCount.visibility = View.VISIBLE
                textCount.text = header.count.toString()
            } else {
                textCount.visibility = View.GONE
            }
        }
    }
}
