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

    /**
     * 🆕 v16.0.14: счётчики для системных папок.
     * Один общий объект вместо одного Int — чтобы каждая системная папка
     * показывала СВОЁ число:
     *  - home_tasks     → активные задачи;
     *  - purchases      → предметы в «Покупках» рекурсивно (v17.0.9);
     *  - shopping_list  → предметы в корне «Списка покупок» («купить»).
     */
    data class SystemFolderCounters(
        val homeTasksActive: Int = 0,
        val purchasesItems: Int = 0,
        val shoppingListItems: Int = 0
    )

    private var items: List<Any> = emptyList()

    private var systemFolderCounters: SystemFolderCounters = SystemFolderCounters()

    /**
     * 🆕 v20.1.1: агрегаты по обычным папкам — количество + сумма.
     * Ключ — folderId. Заполняется из MainActivity одним batch-запросом
     * (getFolderStatsShallow), чтобы не делать N+1 при отрисовке.
     *
     * Если для папки нет записи — info у неё пустой (как раньше).
     */
    private var folderStats: Map<String, FolderStatsRow> = emptyMap()

    /**
     * 🆕 v20.1.1: агрегат для системной «🛒 Покупки» — рекурсивно.
     * Сюда же кладётся itemCount, чтобы не дублировать purchasesItems
     * из systemFolderCounters (там только число). Сумма — только здесь.
     */
    private var purchasesStats: FolderStatsRow? = null

    /**
     * 🆕 v20.1.1: единый формат денег для карточек.
     * Целые — без копеек; дробные — с двумя знаками, запятая.
     * Разряды — пробел (4 560).
     */
    private val moneyFormat: DecimalFormat by lazy {
        val symbols = DecimalFormatSymbols(Locale.getDefault())
        symbols.groupingSeparator = ' '
        DecimalFormat("#,##0.##", symbols)
    }

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
     * 🆕 v20.1.1: обновить статистику обычных папок (batch).
     * Если ничего не изменилось — не перерисовываем.
     */
    fun setFolderStats(stats: Map<String, FolderStatsRow>) {
        if (folderStats == stats) return
        folderStats = stats
        notifyDataSetChanged()
    }

    /**
     * 🆕 v20.1.1: обновить статистику системной «Покупок».
     */
    fun setPurchasesStats(stats: FolderStatsRow?) {
        if (purchasesStats == stats) return
        purchasesStats = stats
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

            // 🆕 v20.1.1: «📦 N · 💰 X ₽» мелким текстом под названием.
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

    /**
     * 🆕 v20.1.1: текст «📦 N · 💰 X ₽» для обычной папки.
     *
     *  - нет статистики (папку только что создали / список обновляется) → "" ;
     *  - N = 0 → "" (пустая папка — как раньше, без шума) ;
     *  - N > 0, total = 0 → "📦 N предмет(а/ов)" (цены нет ни у одного) ;
     *  - N > 0, total > 0 → "📦 N · 💰 X ₽" .
     */
    private fun buildFolderInfoText(folderId: String): String {
        val stats = folderStats[folderId] ?: return ""
        if (stats.itemCount <= 0) return ""

        val countPart = "📦 ${stats.itemCount} ${pluralItems(stats.itemCount)}"
        if (stats.totalPrice <= 0.0) return countPart

        return "$countPart · 💰 ${formatMoney(stats.totalPrice)} ₽"
    }

    /**
     * 🆕 v16.0.14: отрисовка системной папки по её systemKey.
     *  - home_tasks     → 🏠 + «📋 N активных»
     *  - purchases      → 🧾 + «🛒 N предметов · 💰 X ₽»
     *  - shopping_list  → 🛒 + «🛒 N купить»
     *
     * 🆕 v17.2.8: иконки системных папок:
     *  - home_tasks     → ic_home (как было);
     *  - purchases      → ic_purchases_receipt (чек);
     *  - shopping_list  → ic_shopping_list_cart (тележка).
     *
     * 🆕 v20.1.1: для «Покупок» добавлена сумма по всем подпапкам.
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

                // 🆕 v20.1.1: количество берём из systemFolderCounters (как раньше),
                // сумму — из purchasesStats. Если stats ещё не пришли — показываем
                // только количество, без «💰 0 ₽» (иначе выглядит как ошибка).
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

    /**
     * 🆕 v20.1.1: единый формат денег.
     *  - целые → «4 560»;
     *  - дробные → «4 560,5» (без хвостовых нулей, запятая как десятичный
     *    разделитель в русской локали — DecimalFormat сам подставит).
     *
     * ВАЖНО: сумму в карточке папки показываем БЕЗ копеек, если они нулевые,
     * чтобы «💰 4 560 ₽» не превращалось в «💰 4 560,00 ₽».
     */
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
            // 🆕 v20.1.1: используем общий formatMoney — единый вид
            // и здесь, и в папках.
            infoParts.add("${formatMoney(entry.price)} ₽")
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
