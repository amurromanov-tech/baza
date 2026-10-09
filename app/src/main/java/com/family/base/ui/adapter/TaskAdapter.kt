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
import com.family.base.data.local.entity.TaskEntity
import com.google.android.material.checkbox.MaterialCheckBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Адаптер списка задач «🏠 Домашние дела».
 *
 * Особенности:
 *  - выполненные задачи зачёркнуты и бледные (alpha 0.4), но читаемые;
 *  - тап по чекбоксу → markDone / markUndone;
 *  - тап по карточке → редактирование;
 *  - меню (⋮) → редактировать / удалить.
 */
class TaskAdapter(
    private val onToggleDone: (TaskEntity) -> Unit,
    private val onEdit: (TaskEntity) -> Unit,
    private val onDelete: (TaskEntity) -> Unit
) : ListAdapter<TaskEntity, TaskAdapter.TaskViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TaskEntity>() {
            override fun areItemsTheSame(oldItem: TaskEntity, newItem: TaskEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TaskEntity, newItem: TaskEntity): Boolean =
                oldItem == newItem
        }

        private val DATE_FMT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val checkDone: MaterialCheckBox = itemView.findViewById(R.id.checkDone)
        private val textPriority: TextView = itemView.findViewById(R.id.textPriority)
        private val textTitle: TextView = itemView.findViewById(R.id.textTitle)
        private val textMeta: TextView = itemView.findViewById(R.id.textMeta)
        private val textNote: TextView = itemView.findViewById(R.id.textNote)
        private val buttonMenu: ImageButton = itemView.findViewById(R.id.buttonMenu)

        fun bind(task: TaskEntity) {
            // Приоритет
            textPriority.text = when (task.priority) {
                TaskEntity.PRIORITY_URGENT -> "🔴"
                TaskEntity.PRIORITY_HIGH -> "🟠"
                TaskEntity.PRIORITY_NORMAL -> "🟡"
                TaskEntity.PRIORITY_LOW -> "🟢"
                else -> "🟡"
            }

            // Название
            textTitle.text = task.title

            // Мета: дедлайн + повтор
            val metaParts = mutableListOf<String>()

            task.dueDate?.let { due ->
                val dateStr = DATE_FMT.format(Date(due))
                val overdue = !task.isDone && due < System.currentTimeMillis()
                metaParts.add(if (overdue) "⏰ $dateStr (просрочено)" else "⏰ $dateStr")
            }

            when (task.recurrence) {
                TaskEntity.RECURRENCE_DAILY -> metaParts.add("🔁 ежедневно")
                TaskEntity.RECURRENCE_WEEKLY -> metaParts.add("🔁 еженедельно")
                TaskEntity.RECURRENCE_MONTHLY -> metaParts.add("🔁 ежемесячно")
            }

            if (task.isDone && task.doneBy != null) {
                metaParts.add("✓ ${task.doneBy}")
            }

            if (metaParts.isEmpty()) {
                textMeta.visibility = View.GONE
            } else {
                textMeta.visibility = View.VISIBLE
                textMeta.text = metaParts.joinToString(" · ")
            }

            // Примечание
            if (task.note.isNullOrEmpty()) {
                textNote.visibility = View.GONE
            } else {
                textNote.visibility = View.VISIBLE
                textNote.text = task.note
            }

            // Чекбокс
            checkDone.setOnCheckedChangeListener(null)
            checkDone.isChecked = task.isDone
            checkDone.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != task.isDone) {
                    onToggleDone(task)
                }
            }

            // Зачёркивание + бледность для выполненных
            applyDoneState(task.isDone)

            // Тап по карточке → редактирование
            itemView.setOnClickListener { onEdit(task) }

            // Меню (⋮)
            buttonMenu.setOnClickListener { anchor ->
                val popup = PopupMenu(itemView.context, anchor)
                popup.menu.add(0, 1, 0, "✏️ Редактировать")
                popup.menu.add(0, 2, 1, "🗑 Удалить")
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { onEdit(task); true }
                        2 -> { onDelete(task); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }

        private fun applyDoneState(isDone: Boolean) {
            val alpha = if (isDone) 0.4f else 1.0f
            textTitle.alpha = alpha
            textMeta.alpha = alpha
            textNote.alpha = alpha
            textPriority.alpha = alpha

            if (isDone) {
                textTitle.paintFlags = textTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                textTitle.paintFlags = textTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
        }
    }
}
