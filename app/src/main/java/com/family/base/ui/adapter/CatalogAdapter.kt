package com.family.base.ui.adapter

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.local.dao.FolderStatsRow
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.ui.FullscreenImageActivity
import com.family.base.util.ImageUtils
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class CatalogAdapter(
    private val onFolderClick: (FolderEntity) -> Unit,
    private val onItemClick: (ItemEntity) -> Unit,
    private val onFolderLongClick: (FolderEntity) -> Unit,
    private val onItemLongClick: (ItemEntity) -> Unit
) : RecyclerView.Adapter<CatalogAdapter.ViewHolder>() {

    companion object {
        /** 🆕 Ключ в SharedPreferences для порога «давно не было ревизии», в днях. */
        const val PREF_REVISION_WARNING_DAYS = "revision_warning_days"

        /** 🆕 Порог по умолчанию — 1 год. */
        const val DEFAULT_REVISION_WARNING_DAYS = 365

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }

    /**
     * 🆕 v16.0.14: счётчики для системных папок.
     * Один общий объект вместо одного Int — чтобы каждая системная папка
     * показывала СВОЁ число:
     *  - home_tasks     → активные задачи;
     *  - purchases      → предметы в «Покупках» рекурсивно (v17.0.9);
     *  - shopping_list  → предметы в корне «Списка покупок» («купить»).
     *
     * 🆕 v20.4.9: для «🖼️ Галерея» счётчик не нужен (фото на Диске,
     * не в БД). Поле galleryPhotos оставлено на будущее.
     *
     * 🆕 v21.1.0: для «Рецептов» счётчик берётся из recipes (source = "user" / "ai").
     * Сейчас не заполняется — покажем статичный текст «Открыть рецепты».
     */
    data class SystemFolderCounters(
        val homeTasksActive: Int = 0,
        val purchasesItems: Int = 0,
        val shoppingListItems: Int = 0,
        val galleryPhotos: Int = 0,
        val recipesAiCount: Int = 0,
        val recipesMyCount: Int = 0
    )

    private var items: List<Any> = emptyList()

    private var systemFolderCounters: SystemFolderCounters = SystemFolderCounters()

    private var folderStats: Map<String, FolderStatsRow> = emptyMap()

    private var purchasesStats: FolderStatsRow? = null

    /**
     * 🆕 Кеш порога ревизии, чтобы не читать SharedPreferences на каждый bind.
     * Заполняется лениво при первом bindItem и сбрасывается через
     * [invalidateRevisionThreshold] (если пользователь поменял настройку).
     */
    private var cachedRevisionWarningDays: Int? = null

    private val moneyFormat: DecimalFormat by lazy {
        val symbols = DecimalFormatSymbols(Locale.getDefault())
        symbols.groupingSeparator = ' '
        DecimalFormat("#,##0.##", symbols)
    }

    fun submitList(newItems: List<Any>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setSystemFolderCounters(counters: SystemFolderCounters) {
        if (systemFolderCounters == counters) return
        systemFolderCounters = counters
        notifyDataSetChanged()
    }

    fun setFolderStats(stats: Map<String, FolderStatsRow>) {
        if (folderStats == stats) return
        folderStats = stats
        notifyDataSetChanged()
    }

    fun setPurchasesStats(stats: FolderStatsRow?) {
        if (purchasesStats == stats) return
        purchasesStats = stats
        notifyDataSetChanged()
    }

    /**
     * 🆕 Сбросить кеш порога ревизии и перерисовать список.
     * Вызывается после изменения настройки в AppSettingsActivity
     * (если экран каталога уже открыт).
     */
    fun invalidateRevisionThreshold() {
        cachedRevisionWarningDays = null
        notifyDataSetChanged()
    }

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

        // 🆕 Папки ревизию не проходят — строка скрыта всегда.
        holder.revisionInfo.visibility = View.GONE

        if (entry.isSystem) {
            bindSystemFolder(holder, entry)
        } else {
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
            holder.info.text = buildFolderInfoText(entry.id)
            holder.expiryInfo.visibility = View.GONE
            holder.lentInfo.visibility = View.GONE

            holder.colorBar.setBackgroundColor(
                context.resources.getColor(R.color.colorNormal, context.theme)
            )
        }

        holder.itemView.setOnClickListener { onFolderClick(entry) }
        holder.itemView.setOnLongClickListener { onFolderLongClick(entry); true }
    }

    private fun buildFolderInfoText(folderId: String): String {
        val stats = folderStats[folderId] ?: return ""
        if (stats.itemCount <= 0) return ""

        val countPart = "📦 ${stats.itemCount} ${pluralItems(stats.itemCount)}"
        if (stats.totalPrice <= 0.0) return countPart

        return "$countPart · 💰 ${formatMoney(stats.totalPrice)} ₽"
    }

    /**
     * Отрисовка системной папки по её systemKey.
     *
     * 🆕 v20.4.9: ветка SYSTEM_KEY_GALLERY.
     * 🆕 v21.1.0: ветки SYSTEM_KEY_RECIPES / SYSTEM_KEY_RECIPES_AI / SYSTEM_KEY_RECIPES_MY.
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
                holder.icon.load(R.drawable.ic_purchases_receipt) {
                    placeholder(R.drawable.ic_purchases_receipt)
                    error(R.drawable.ic_purchases_receipt)
                }
                holder.name.text = entry.name

                val n = systemFolderCounters.purchasesItems
                val total = purchasesStats?.totalPrice ?: 0.0

                holder.info.text = when {
                    n <= 0 -> "🛒 Пусто"
                    total > 0.0 -> "🛒 $n ${pluralItems(n)} · 💰 ${formatMoney(total)} ₽"
                    else -> "🛒 $n ${pluralItems(n)}"
                }
            }

            FolderEntity.SYSTEM_KEY_SHOPPING_LIST -> {
                holder.icon.load(R.drawable.ic_shopping_list_cart) {
                    placeholder(R.drawable.ic_shopping_list_cart)
                    error(R.drawable.ic_shopping_list_cart)
                }
                holder.name.text = entry.name

                val n = systemFolderCounters.shoppingListItems
                holder.info.text = if (n > 0) {
                    "🛒 $n ${pluralItems(n)}"
                } else {
                    "🛒 Пусто"
                }
            }

            FolderEntity.SYSTEM_KEY_GALLERY -> {
                holder.icon.load(R.drawable.ic_gallery) {
                    placeholder(R.drawable.ic_gallery)
                    error(R.drawable.ic_gallery)
                }
                holder.name.text = entry.name
                holder.info.text = "📷 Открыть фотоархив"
            }

            FolderEntity.SYSTEM_KEY_RECIPES -> {
                holder.icon.load(R.drawable.ic_folder_default) {
                    placeholder(R.drawable.ic_folder_default)
                    error(R.drawable.ic_folder_default)
                }
                holder.name.text = entry.name

                val aiCount = systemFolderCounters.recipesAiCount
                val myCount = systemFolderCounters.recipesMyCount
                holder.info.text = when {
                    aiCount + myCount <= 0 -> "🍳 Открыть рецепты"
                    else -> "🍳 ИИ: $aiCount · Мои: $myCount"
                }
            }

            FolderEntity.SYSTEM_KEY_RECIPES_AI -> {
                holder.icon.load(R.drawable.ic_folder_default) {
                    placeholder(R.drawable.ic_folder_default)
                    error(R.drawable.ic_folder_default)
                }
                holder.name.text = entry.name

                val n = systemFolderCounters.recipesAiCount
                holder.info.text = if (n > 0) {
                    "🤖 $n ${pluralRecipes(n)}"
                } else {
                    "🤖 Спросить, что приготовить"
                }
            }

            FolderEntity.SYSTEM_KEY_RECIPES_MY -> {
                holder.icon.load(R.drawable.ic_folder_default) {
                    placeholder(R.drawable.ic_folder_default)
                    error(R.drawable.ic_folder_default)
                }
                holder.name.text = entry.name

                val n = systemFolderCounters.recipesMyCount
                holder.info.text = if (n > 0) {
                    "📝 $n ${pluralRecipes(n)}"
                } else {
                    "📝 Добавить свой рецепт"
                }
            }

            else -> {
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

    private fun pluralItems(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "предмет"
            mod10 in 2..4 && mod100 !in 12..14 -> "предмета"
            else -> "предметов"
        }
    }

    private fun pluralRecipes(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "рецепт"
            mod10 in 2..4 && mod100 !in 12..14 -> "рецепта"
            else -> "рецептов"
        }
    }

    private fun formatMoney(value: Double): String {
        return try {
            if (value == value.toLong().toDouble()) {
                moneyFormat.format(value.toLong())
            } else {
                moneyFormat.format(value)
            }
        } catch (e: Exception) {
            value.toString()
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

        val infoParts = mutableListOf<String>()
        if (entry.quantity > 1) {
            infoParts.add("×${entry.quantity}")
        }
        if (entry.price != null && entry.price != 0.0) {
            infoParts.add("${formatMoney(entry.price)} ₽")
        }
        holder.info.text = infoParts.joinToString("  •  ")

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

        // 🆕 Ревизия: показываем только проблему (🔴/🟡).
        bindRevision(holder, entry)

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

    /**
     * 🆕 Ревизия: показываем статус ТОЛЬКО если есть проблема.
     *
     *  - lastRevisionDate == null → «🔴 Ревизия не проводилась»
     *  - прошло > порога дней      → «🟡 Ревизия: N мес./г. назад»
     *  - иначе (всё ок)            → строка скрыта
     *
     * Порог читается из SharedPreferences (revision_warning_days),
     * по умолчанию 365 дней. Кешируется в cachedRevisionWarningDays.
     */
    private fun bindRevision(holder: ViewHolder, entry: ItemEntity) {
        val context = holder.itemView.context
        val thresholdDays = getRevisionWarningDays(context)
        val thresholdMs = thresholdDays.toLong() * DAY_MS

        val last = entry.lastRevisionDate
        if (last == null || last <= 0L) {
            holder.revisionInfo.visibility = View.VISIBLE
            holder.revisionInfo.text = "🔴 Ревизия не проводилась"
            holder.revisionInfo.setTextColor(
                context.resources.getColor(android.R.color.holo_red_dark, context.theme)
            )
            return
        }

        val ageMs = System.currentTimeMillis() - last
        if (ageMs <= thresholdMs) {
            // Ревизия была недавно — не шумим.
            holder.revisionInfo.visibility = View.GONE
            return
        }

        holder.revisionInfo.visibility = View.VISIBLE
        holder.revisionInfo.text = "🟡 Ревизия: ${formatRevisionAge(ageMs)} назад"
        holder.revisionInfo.setTextColor(
            context.resources.getColor(android.R.color.holo_orange_dark, context.theme)
        )
    }

    /**
     * 🆕 Прочитать порог ревизии из SharedPreferences (с кешем).
     */
    private fun getRevisionWarningDays(context: android.content.Context): Int {
        cachedRevisionWarningDays?.let { return it }
        val days = try {
            val prefs = context.getSharedPreferences(
                BaseApplication.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            )
            prefs.getInt(PREF_REVISION_WARNING_DAYS, DEFAULT_REVISION_WARNING_DAYS)
        } catch (e: Exception) {
            DEFAULT_REVISION_WARNING_DAYS
        }
        cachedRevisionWarningDays = days
        return days
    }

    /**
     * 🆕 Человекочитаемый возраст ревизии: «8 мес.», «1 г. 3 мес.», «2 г.».
     */
    private fun formatRevisionAge(ageMs: Long): String {
        val totalDays = (ageMs / DAY_MS).toInt().coerceAtLeast(0)
        val years = totalDays / 365
        val months = (totalDays % 365) / 30

        return when {
            years >= 1 && months >= 1 -> "$years г. $months мес."
            years >= 1 -> "$years г."
            months >= 1 -> "$months мес."
            else -> "$totalDays дн."
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val info: TextView = view.findViewById(R.id.info)
        val expiryInfo: TextView = view.findViewById(R.id.expiryInfo)
        val lentInfo: TextView = view.findViewById(R.id.lentInfo)
        val revisionInfo: TextView = view.findViewById(R.id.revisionInfo)
        val colorBar: View = view.findViewById(R.id.colorBar)
    }
}
