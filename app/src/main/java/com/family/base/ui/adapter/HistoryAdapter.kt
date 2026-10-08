package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.model.SubtypeCatalog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Адаптер истории изменений.
 *
 * Поддерживает два типа строк:
 *  - HEADER — заголовок даты («Сегодня», «Вчера», «01.10.2026»)
 *  - ENTRY  — запись истории
 *
 * Группировка по дате (changedAt) в порядке убывания (новые сверху).
 *
 * 🆕 v12: косметика отображения:
 *  - "unknown_user" / "user" / "Пользователь" → «❓ Без автора»
 *  - сырые коды типов ("thing", "food", "medicine", "other") в тексте
 *    oldValue/newValue заменяются на человекочитаемые через SubtypeCatalog
 */
class HistoryAdapter(
    private val onItemClick: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items: MutableList<Row> = mutableListOf()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1

        private const val ANON_USER_LABEL = "❓ Без автора"

        // Карта сырых кодов типов → человекочитаемые названия
        private val TYPE_MAP = mapOf(
            "thing" to "📦 Предмет",
            "food" to "🍎 Еда",
            "medicine" to "💊 Лекарство",
            "other" to "🗂 Другое"
        )
    }

    sealed class Row {
        data class Header(val title: String, val dateKey: String) : Row()
        data class Entry(val entry: HistoryEntry) : Row()
    }

    // ============================================================
    // ПУБЛИЧНОЕ API
    // ============================================================

    fun submitList(entries: List<HistoryEntry>) {
        items.clear()

        var lastDateKey: String? = null

        entries.forEach { entry ->
            val dateKey = dateKeyOf(entry.changedAt)
            if (dateKey != lastDateKey) {
                items.add(Row.Header(title = headerTitleOf(entry.changedAt), dateKey = dateKey))
                lastDateKey = dateKey
            }
            items.add(Row.Entry(entry))
        }

        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    // ============================================================
    // КЛЮЧИ ДАТ
    // ============================================================

    private fun dateKeyOf(timestamp: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    private fun headerTitleOf(timestamp: Long): String {
        val today = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }

        val isSameDay = today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (isSameDay) return "Сегодня"

        val yesterday = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return "Вчера"

        return dateFormat.format(Date(timestamp))
    }

    // ============================================================
    // RECYCLER.ADAPTER
    // ============================================================

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Entry -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_history_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_history_entry, parent, false)
                EntryViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row.title)
            is Row.Entry -> (holder as EntryViewHolder).bind(row.entry)
        }
    }

    override fun getItemCount(): Int = items.size

    // ============================================================
    // HEADER
    // ============================================================

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvHeader: TextView = view.findViewById(R.id.tvHeaderDate)

        fun bind(title: String) {
            tvHeader.text = title
        }
    }

    // ============================================================
    // ENTRY
    // ============================================================

    inner class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvIcon: TextView = view.findViewById(R.id.tvActionIcon)
        private val tvItemName: TextView = view.findViewById(R.id.tvItemName)
        private val tvChange: TextView = view.findViewById(R.id.tvChange)
        private val tvUser: TextView = view.findViewById(R.id.tvUser)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)

        fun bind(entry: HistoryEntry) {
            tvIcon.text = iconForAction(entry.action)

            val displayName = entry.itemName?.takeIf { it.isNotBlank() } ?: "—"
            tvItemName.text = displayName

            tvChange.text = buildChangeText(entry)

            // 🆕 v12: косметика пользователя
            tvUser.text = "👤 ${displayUser(entry.changedBy)}"
            tvTime.text = timeFormat.format(Date(entry.changedAt))

            itemView.setOnClickListener { onItemClick(entry) }
        }

        /**
         * 🆕 v12: анонимизация старых пользователей.
         */
        private fun displayUser(raw: String?): String {
            if (raw.isNullOrBlank()) return ANON_USER_LABEL
            return when (raw.lowercase()) {
                "unknown_user", "user", "пользователь", "—" -> ANON_USER_LABEL
                else -> raw
            }
        }

        private fun buildChangeText(entry: HistoryEntry): String {
            val action = entry.action

            // 🆕 v12: для старых записей подменяем сырые коды
            val oldValue = entry.oldValue?.let { replaceRawCodes(it) }
            val newValue = entry.newValue?.let { replaceRawCodes(it) }

            return when {
                action == "create" -> {
                    "Создан${if (!newValue.isNullOrEmpty()) ": $newValue" else ""}"
                }
                action == "update" -> {
                    if (!newValue.isNullOrEmpty()) {
                        newValue
                    } else if (!oldValue.isNullOrEmpty()) {
                        "$oldValue → ${newValue ?: "—"}"
                    } else {
                        "Изменён"
                    }
                }
                action == "delete" -> {
                    "Удалён${if (!oldValue.isNullOrEmpty()) " ($oldValue)" else ""}"
                }
                action == "archive" -> {
                    "📦 В архив${if (!newValue.isNullOrEmpty()) ": $newValue" else ""}"
                }
                action == "unarchive" -> {
                    "↩️ Из архива${if (!newValue.isNullOrEmpty()) ": $newValue" else ""}"
                }
                action == "unarchive_part" -> {
                    "↩️ Возврат части: ${oldValue ?: ""} → ${newValue ?: ""}"
                }
                action == "write_off" -> {
                    "🧴 Списано${if (!newValue.isNullOrEmpty()) ": $newValue" else ""}"
                }
                action == "write_off_part" -> {
                    "🧴 Частичное списание: ${oldValue ?: ""} → ${newValue ?: ""}"
                }
                action == "quantity_change" -> {
                    "📦 Количество: ${oldValue ?: "?"} → ${newValue ?: "?"}"
                }
                action == "revision" -> {
                    "🔍 Ревизия: ${newValue ?: ""}"
                }
                action == "lend" -> {
                    "🤝 Выдан${if (!newValue.isNullOrEmpty()) ": $newValue" else ""}"
                }
                action == "return" -> {
                    "✅ Возвращён"
                }
                action == "move" -> {
                    "📁 Перемещён${if (!newValue.isNullOrEmpty()) ": $newValue" else ""}"
                }
                action == "move_folder" -> {
                    "📁 Папка перемещена${if (!newValue.isNullOrEmpty()) ": $newValue" else ""}"
                }
                action == "split_in" -> {
                    "✂️ Отделено: ${newValue ?: ""}"
                }
                action == "split_out" -> {
                    "✂️ Отдано: ${newValue ?: ""}"
                }
                action == "create_folder" -> {
                    "📁 Создана папка${if (!newValue.isNullOrEmpty()) ": $newValue" else ""}"
                }
                action == "rename_folder" -> {
                    "📝 Папка: «${oldValue ?: ""}» → «${newValue ?: ""}»"
                }
                action == "delete_folder" -> {
                    "🗑 Папка удалена${if (!oldValue.isNullOrEmpty()) " ($oldValue)" else ""}"
                }
                action == "add_nested_folder" -> {
                    "📁 Вложенная папка: ${newValue ?: ""}"
                }
                action == "detach_children" -> {
                    "🔗 Отвязано детей: ${newValue ?: "0"}"
                }
                else -> {
                    buildString {
                        if (!oldValue.isNullOrEmpty()) append("$oldValue → ")
                        append(newValue ?: action)
                    }
                }
            }
        }

        /**
         * 🆕 v12: заменяет сырые коды в тексте на человекочитаемые.
         * Работает и для типов ("thing" → "📦 Предмет"), и для подтипов
         * ("furniture" → "🪑 Мебель") через SubtypeCatalog.
         */
        private fun replaceRawCodes(text: String): String {
            var result = text

            // --- Типы ---
            TYPE_MAP.forEach { (code, display) ->
                result = replaceWholeWord(result, code, display)
            }

            // --- Подтипы (из SubtypeCatalog) ---
            try {
                for (type in listOf("food", "medicine", "thing", "other")) {
                    val subtypes = SubtypeCatalog.getSubtypes(type)
                    subtypes.forEach { subtype ->
                        if (subtype.key.isNotEmpty()) {
                            result = replaceWholeWord(result, subtype.key, subtype.displayName)
                        }
                    }
                }
            } catch (e: Exception) {
                // тихо игнорируем — некритично
            }

            return result
        }

        private fun replaceWholeWord(text: String, word: String, replacement: String): String {
            if (word.isEmpty()) return text
            return try {
                text.replace(Regex("\\b${Regex.escape(word)}\\b"), replacement)
            } catch (e: Exception) {
                text
            }
        }

        private fun iconForAction(action: String): String = when (action) {
            "create" -> "➕"
            "update" -> "✏️"
            "quantity_change" -> "📦"
            "delete" -> "🗑"
            "archive" -> "📦"
            "unarchive", "unarchive_part" -> "↩️"
            "write_off", "write_off_part" -> "🧴"
            "revision" -> "🔍"
            "lend" -> "🤝"
            "return" -> "✅"
            "move" -> "📁"
            "move_folder" -> "📁"
            "split_in", "split_out" -> "✂️"
            "create_folder" -> "📁"
            "rename_folder" -> "📝"
            "delete_folder" -> "🗑"
            "add_nested_folder" -> "📁"
            "detach_children" -> "🔗"
            else -> "•"
        }
    }
}
