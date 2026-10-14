package com.family.base.ui.adapter

import android.content.Intent
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
import com.family.base.ui.FullscreenImageActivity
import com.family.base.util.ImageUtils

class CatalogAdapter(
    private val onFolderClick: (FolderEntity) -> Unit,
    private val onItemClick: (ItemEntity) -> Unit,
    private val onFolderLongClick: (FolderEntity) -> Unit,
    private val onItemLongClick: (ItemEntity) -> Unit
) : RecyclerView.Adapter<CatalogAdapter.ViewHolder>() {

    /**
     * 🆕 v16.0.14: счётчики для системных папок.
     * Один общий объект вместо одного Int — чтобы каждая системная папка
     * показывала СВОЁ число:
     *  - home_tasks     → активные задачи;
     *  - purchases      → предметы в корне «Покупок» (обычно 0);
     *  - shopping_list  → предметы в корне «Списка покупок» («купить»).
     */
    data class SystemFolderCounters(
        val homeTasksActive: Int = 0,
        val purchasesItems: Int = 0,
        val shoppingListItems: Int = 0
    )

    private var items: List<Any> = emptyList()

    private var systemFolderCounters: SystemFolderCounters = SystemFolderCounters()

    fun submitList(newItems: List<Any>) {
        items = newItems
        notifyDataSetChanged()
    }

    /**
     * 🆕 v16.0.14: обновить счётчики системных папок.
     * Если ничего не изменилось — не перерисовываем.
     */
    fun setSystemFolderCounters(counters: SystemFolderCounters) {
        if (systemFolderCounters == counters) return
        systemFolderCounters = counters
        notifyDataSetChanged()
    }

    /**
     * Устаревший метод — оставлен для совместимости.
     * Обновляет только счётчик задач (home_tasks), остальные сохраняет.
     */
    @Deprecated("Используй setSystemFolderCounters")
    fun setSystemFolderTaskCount(count: Int) {
        setSystemFolderCounters(systemFolderCounters.copy(homeTasksActive = count))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_catalog_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val context = holder.itemView.context

        when (entry) {
            is FolderEntity -> bindFolder(holder, entry)
            is ItemEntity -> bindItem(holder, entry)
        }
    }

    // ============================================================
    // ПАПКА
    // ============================================================

    private fun bindFolder(holder: ViewHolder, entry: FolderEntity) {
        val context = holder.itemView.context

        holder.icon.load(null)
        holder.icon.setOnClickListener(null)

        if (entry.isSystem) {
            bindSystemFolder(holder, entry)
        } else {
            // ===== ОБЫЧНАЯ ПАПКА =====
            val iconFile = ImageUtils.getLocalImageFile(context, "folder_${entry.id}")
            if (iconFile != null && iconFile.exists()) {
                holder.icon.load(iconFile) {
                    crossfade(true)
                    placeholder(R.drawable.ic_folder_default)
                    error(R.drawable.ic_folder_default)
                }
            } else {
                holder.icon.load(R.drawable.ic_folder_default)
            }

            holder.name.text = entry.name
            holder.info.text = ""
            holder.expiryInfo.visibility = View.GONE
            holder.lentInfo.visibility = View.GONE

            holder.colorBar.setBackgroundColor(
                context.resources.getColor(R.color.colorNormal, context.theme)
            )
        }

        holder.itemView.setOnClickListener { onFolderClick(entry) }
        holder.itemView.setOnLongClickListener { onFolderLongClick(entry); true }
    }

    /**
     * 🆕 v16.0.14: отрисовка системной папки по её systemKey.
     *  - home_tasks     → 🏠 + «📋 N активных»
     *  - purchases      → 🛒 + «🛒 N покупок»
     *  - shopping_list  → 🛒 + «🛒 N купить»
     */
    private fun bindSystemFolder(holder: ViewHolder, entry: FolderEntity) {
        val context = holder.itemView.context

        when (entry.systemKey) {
            FolderEntity.SYSTEM_KEY_HOME_TASKS -> {
                holder.icon.load(R.drawable.ic_home) {
                    placeholder(R.drawable.ic_home)
                    error(R.drawable.ic_home)
                }
                holder.name.text = entry.name

                val n = systemFolderCounters.homeTasksActive
                holder.info.text = if (n > 0) {
                    "📋 $n активных"
                } else {
                    "📋 Нет активных задач"
                }
            }

            FolderEntity.SYSTEM_KEY_PURCHASES -> {
                holder.icon.load(R.drawable.ic_folder_default) {
                    placeholder(R.drawable.ic_folder_default)
                    error(R.drawable.ic_folder_default)
                }
                holder.name.text = entry.name

                val n = systemFolderCounters.purchasesItems
                holder.info.text = if (n > 0) {
                    "🛒 $n ${pluralItems(n)}"
                } else {
                    "🛒 Пусто"
                }
            }

            FolderEntity.SYSTEM_KEY_SHOPPING_LIST -> {
                holder.icon.load(R.drawable.ic_folder_default) {
                    placeholder(R.drawable.ic_folder_default)
                    error(R.drawable.ic_folder_default)
                }
                holder.name.text = entry.name

                val n = systemFolderCounters.shoppingListItems
                holder.info.text = if (n > 0) {
                    "🛒 $n ${pluralItems(n)}"
                } else {
                    "🛒 Пусто"
                }
            }

            else -> {
                // Неизвестная системная папка — общий вид
                holder.icon.load(R.drawable.ic_home) {
                    placeholder(R.drawable.ic_home)
                    error(R.drawable.ic_home)
                }
                holder.name.text = entry.name
                holder.info.text = ""
            }
        }

        holder.expiryInfo.visibility = View.GONE
        holder.lentInfo.visibility = View.GONE

        holder.colorBar.setBackgroundColor(
            context.resources.getColor(R.color.colorPrimary, context.theme)
        )
    }

    /**
     * «1 предмет», «2 предмета», «5 предметов».
     */
    private fun pluralItems(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "предмет"
            mod10 in 2..4 && mod100 !in 12..14 -> "предмета"
            else -> "предметов"
        }
    }

    // ============================================================
    // ПРЕДМЕТ
    // ============================================================

    private fun bindItem(holder: ViewHolder, entry: ItemEntity) {
        val context = holder.itemView.context

        holder.icon.load(null)

        val localFile = ImageUtils.getLocalImageFile(context, entry.id)
        if (localFile != null && localFile.exists()) {
            holder.icon.load(localFile) {
                crossfade(true)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }

            // ===== КЛИК ПО ИКОНКЕ → ПОЛНОЭКРАННЫЙ ПРОСМОТР =====
            holder.icon.setOnClickListener {
                val intent = Intent(context, FullscreenImageActivity::class.java).apply {
                    putExtra(FullscreenImageActivity.EXTRA_IMAGE_PATH, localFile.absolutePath)
                    putExtra(FullscreenImageActivity.EXTRA_TITLE, entry.name)
                }
                context.startActivity(intent)
            }
        } else {
            holder.icon.load(R.drawable.ic_item_default)
            holder.icon.setOnClickListener(null)
        }

        holder.name.text = entry.name

        // ===== ИНФО: количество + цена =====
        val infoParts = mutableListOf<String>()
        if (entry.quantity > 1) {
            infoParts.add("×${entry.quantity}")
        }
        if (entry.price != null && entry.price != 0.0) {
            val priceStr = if (entry.price % 1.0 == 0.0) {
                entry.price.toInt().toString()
            } else {
                String.format("%.2f", entry.price)
            }
            infoParts.add("${priceStr} ₽")
        }
        holder.info.text = infoParts.joinToString("  •  ")

        // ===== СРОК ГОДНОСТИ =====
        if (entry.isExpired) {
            holder.expiryInfo.visibility = View.VISIBLE
            holder.expiryInfo.text = "⚠️ Просрочен"
            holder.expiryInfo.setTextColor(
                context.resources.getColor(android.R.color.holo_red_dark, context.theme)
            )
        } else if (entry.daysUntilExpiry != Int.MAX_VALUE && entry.daysUntilExpiry <= 7) {
            holder.expiryInfo.visibility = View.VISIBLE
            holder.expiryInfo.text = "⏰ Осталось ${entry.daysUntilExpiry} дн."
            holder.expiryInfo.setTextColor(
                context.resources.getColor(android.R.color.holo_orange_dark, context.theme)
            )
        } else {
            holder.expiryInfo.visibility = View.GONE
        }

        // ===== ЗАЙМ =====
        if (entry.isLent && !entry.lentTo.isNullOrEmpty()) {
            holder.lentInfo.visibility = View.VISIBLE
            var text = "🤝 У ${entry.lentTo}"
            if (!entry.lentNote.isNullOrEmpty()) {
                text += " (${entry.lentNote})"
            }
            holder.lentInfo.text = text
        } else {
            holder.lentInfo.visibility = View.GONE
        }

        // ===== ЦВЕТНАЯ ПОЛОСА =====
        val colorRes = when {
            entry.isLent -> android.R.color.holo_orange_light
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

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val info: TextView = view.findViewById(R.id.info)
        val expiryInfo: TextView = view.findViewById(R.id.expiryInfo)
        val lentInfo: TextView = view.findViewById(R.id.lentInfo)
        val colorBar: View = view.findViewById(R.id.colorBar)
    }
}
