package com.family.base.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.util.ItemHistoryHelper
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🆕 C: адаптер подсказок для автокомплита названия в AddItemActivity.
 *
 * Работает в паре с MaterialAutoCompleteTextView:
 *  - при вводе текста (с debounce в AddItemActivity) вызывается
 *    setSuggestions() со списком найденных совпадений;
 *  - в выпадающем списке показываем «name (статус, дата)»,
 *    чтобы пользователь видел, откуда шаблон;
 *  - по клику — onSuggestionSelected(template) → AddItemActivity
 *    применяет поля шаблона.
 *
 * Не использует Filter (фильтрация уже сделана в AddItemActivity
 * через ItemHistoryHelper.findMatches). Filterable реализован
 * формально — чтобы MaterialAutoCompleteTextView не жаловался.
 *
 * Использование:
 *   val adapter = ItemSuggestionAdapter(context, scope, db) { template ->
 *       applyTemplateFromHistory(template)
 *   }
 *   autoComplete.setAdapter(adapter)
 *   // при вводе:
 *   adapter.setSuggestions(suggestions)
 */
class ItemSuggestionAdapter(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val db: AppDatabase,
    private val onSuggestionSelected: (ItemEntity) -> Unit
) : BaseAdapter(), Filterable {

    private val TAG = "ItemSuggestionAdapter"

    /** Текущий список подсказок (уже отфильтрованный). */
    private var suggestions: List<ItemEntity> = emptyList()

    /** Для хранения полного списка (нужно для Filterable). */
    private var allSuggestions: List<ItemEntity> = emptyList()

    // ============================================================
    // УПРАВЛЕНИЕ СПИСКОМ
    // ============================================================

    /**
     * Установить список подсказок (вызывается из AddItemActivity
     * после поиска через ItemHistoryHelper.findMatches).
     */
    fun setSuggestions(newSuggestions: List<ItemEntity>) {
        allSuggestions = newSuggestions
        suggestions = newSuggestions
        notifyDataSetChanged()
        Logger.log(TAG, "setSuggestions: ${newSuggestions.size}")
    }

    /**
     * Очистить подсказки (например, при вводе < 2 символов).
     */
    fun clearSuggestions() {
        allSuggestions = emptyList()
        suggestions = emptyList()
        notifyDataSetChanged()
    }

    /**
     * Получить ItemEntity по позиции (для onItemClick).
     */
    fun getItemAt(position: Int): ItemEntity? {
        return suggestions.getOrNull(position)
    }

    // ============================================================
    // BASEADAPTER
    // ============================================================

    override fun getCount(): Int = suggestions.size

    override fun getItem(position: Int): Any = suggestions[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_suggestion, parent, false)

        val item = suggestions[position]
        val tv = view.findViewById<TextView>(R.id.tvSuggestion)
        tv.text = ItemHistoryHelper.describe(item)

        return view
    }

    // ============================================================
    // FILTERABLE (формально — фильтрация уже сделана в Activity)
    // ============================================================

    /**
     * Filterable нужен, чтобы MaterialAutoCompleteTextView мог
     * вызывать getFilter().filter(). Но у нас фильтрация уже
     * выполнена в AddItemActivity (ItemHistoryHelper.findMatches),
     * поэтому здесь просто возвращаем текущий список.
     *
     * Если понадобится фильтрация «на лету» без Activity — можно
     * перенести логику сюда.
     */
    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                // Ничего не делаем: фильтрация — в Activity.
                val results = FilterResults()
                results.values = allSuggestions
                results.count = allSuggestions.size
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                suggestions = (results?.values as? List<ItemEntity>).orEmpty()
                notifyDataSetChanged()
            }
        }
    }

    // ============================================================
    // КЛИК ПО ПОДСКАЗКЕ (вызывается из AddItemActivity)
    // ============================================================

    /**
     * Обработать выбор подсказки.
     * Вызывается из AddItemActivity в OnItemClickListener.
     */
    fun onItemSelected(position: Int) {
        val item = suggestions.getOrNull(position) ?: return
        Logger.log(TAG, "onItemSelected: ${item.name} (id=${item.id})")
        onSuggestionSelected(item)
    }
}
