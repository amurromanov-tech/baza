package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxLogger
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.ui.adapter.ShoppingListAdapter
import com.family.base.ui.viewmodel.ShoppingListViewModel
import com.family.base.util.Logger
import com.family.base.util.ShoppingListSortPreferences
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Экран «🛒 Список покупок».
 *
 * UX-клон «🏠 Домашних дел»:
 *  - Купить   — предметы в корне системной папки «🛒 Список покупок»;
 *  - Куплено  — предметы внутри подпапок «Покупок» (зачёркнуты и бледные);
 *  - Все      — оба списка.
 *
 * 🆕 v17.0.8: сортировка в тулбаре.
 *  - кнопка в тулбаре → PopupMenu с 4 режимами + сброс;
 *  - режим хранится в ShoppingListSortPreferences;
 *  - DEFAULT — порядок как отдаёт ViewModel.
 */
class ShoppingListActivity : AppCompatActivity() {

    private val TAG = "ShoppingListActivity"

    private lateinit var db: AppDatabase
    private lateinit var tokenStorage: TokenStorage
    private lateinit var viewModel: ShoppingListViewModel
    private lateinit var adapter: ShoppingListAdapter

    private lateinit var rvItems: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var textEmpty: TextView
    private lateinit var textCounter: TextView
    private lateinit var fabAddItem: FloatingActionButton
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var chipToBuy: Chip
    private lateinit var chipBought: Chip
    private lateinit var chipAll: Chip

    private var currentFilter: ShoppingListViewModel.Filter =
        ShoppingListViewModel.Filter.TO_BUY

    private val currentUser: String
        get() = tokenStorage.getCurrentUser()
            ?: tokenStorage.getUserDisplayName()
            ?: "Пользователь"

    // ============================================================
    // LAUNCHERS
    // ============================================================

    private val itemDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val folderId = result.data?.getStringExtra("navigate_to_folder_id")
            if (!folderId.isNullOrEmpty()) {
                setResult(RESULT_OK, Intent().apply {
                    putExtra("navigate_to_folder_id", folderId)
                })
                finish()
                return@registerForActivityResult
            }
        }
        loadItems()
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shopping_list)

        try {
            db = AppDatabase.getInstance(this)
            tokenStorage = TokenStorage(this)
            viewModel = ShoppingListViewModel(db)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: failed to init: ${e.message}", e)
            finish()
            return
        }

        rvItems = findViewById(R.id.rvItems)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        textEmpty = findViewById(R.id.textEmpty)
        textCounter = findViewById(R.id.textCounter)
        fabAddItem = findViewById(R.id.fabAddItem)
        chipGroupFilter = findViewById(R.id.chipGroupFilter)
        chipToBuy = findViewById(R.id.chipToBuy)
        chipBought = findViewById(R.id.chipBought)
        chipAll = findViewById(R.id.chipAll)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.setNavigationOnClickListener { finish() }
        toolbar?.inflateMenu(R.menu.menu_shopping_list)
        toolbar?.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> {
                    showSortDialog()
                    true
                }
                else -> false
            }
        }

        adapter = ShoppingListAdapter(
            onToggleBought = { item -> onToggleBought(item) },
            onEdit = { item -> openItemDetailEdit(item) },
            onDelete = { item -> confirmDelete(item) }
        )

        rvItems.layoutManager = LinearLayoutManager(this)
        rvItems.adapter = adapter

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipBought) -> ShoppingListViewModel.Filter.BOUGHT
                checkedIds.contains(R.id.chipAll) -> ShoppingListViewModel.Filter.ALL
                else -> ShoppingListViewModel.Filter.TO_BUY
            }
            loadItems()
        }

        fabAddItem.setOnClickListener { showQuickAddDialog() }

        loadItems()
    }

    override fun onResume() {
        super.onResume()
        loadItems()
    }

    // ============================================================
    // СОРТИРОВКА
    // ============================================================

    private fun showSortDialog() {
        val current = ShoppingListSortPreferences.getMode(this)

        val labels = arrayOf(
            "📅 Дата ↓ (сначала новые)",
            "📅 Дата ↑ (сначала старые)",
            "🔤 Имя ↑ (А → Я)",
            "🔤 Имя ↓ (Я → А)",
            "↩️ По умолчанию"
        )

        val checkedIndex = when (current) {
            ShoppingListSortPreferences.Mode.DEFAULT   -> 4
            ShoppingListSortPreferences.Mode.DATE_DESC -> 0
            ShoppingListSortPreferences.Mode.DATE_ASC  -> 1
            ShoppingListSortPreferences.Mode.NAME_ASC  -> 2
            ShoppingListSortPreferences.Mode.NAME_DESC -> 3
        }

        AlertDialog.Builder(this)
            .setTitle("Сортировка")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val mode = when (which) {
                    0 -> ShoppingListSortPreferences.Mode.DATE_DESC
                    1 -> ShoppingListSortPreferences.Mode.DATE_ASC
                    2 -> ShoppingListSortPreferences.Mode.NAME_ASC
                    3 -> ShoppingListSortPreferences.Mode.NAME_DESC
                    else -> ShoppingListSortPreferences.Mode.DEFAULT
                }
                ShoppingListSortPreferences.setMode(this, mode)
                dialog.dismiss()
                loadItems()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Применяет выбранный режим сортировки к списку.
     * DEFAULT — оставляем как отдал ViewModel (по name COLLATE NOCASE ASC,
     *            внутри фильтра — toBuyItems + boughtItems).
     *
     * Сортировка по дате — по addedDate (когда добавили покупку).
     * Сортировка по имени — регистронезависимая.
     */
    private fun applySort(items: List<ItemEntity>): List<ItemEntity> {
        return when (ShoppingListSortPreferences.getMode(this)) {
            ShoppingListSortPreferences.Mode.DEFAULT ->
                items

            ShoppingListSortPreferences.Mode.DATE_DESC ->
                items.sortedByDescending { it.addedDate }

            ShoppingListSortPreferences.Mode.DATE_ASC ->
                items.sortedBy { it.addedDate }

            ShoppingListSortPreferences.Mode.NAME_ASC ->
                items.sortedBy { it.name.lowercase(Locale.getDefault()) }

            ShoppingListSortPreferences.Mode.NAME_DESC ->
                items.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
        }
    }

    // ============================================================
    // БЫСТРОЕ ДОБАВЛЕНИЕ
    // ============================================================

    private fun showQuickAddDialog() {
        val container = android.widget.FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val editText = EditText(this).apply {
            hint = "Что купить?"
            isSingleLine = true
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(editText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Новая покупка")
            .setView(container)
            .setPositiveButton("Добавить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            editText.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    editText.error = "Введите название"
                    return@setOnClickListener
                }
                dialog.dismiss()
                createQuickItem(name)
            }
        }

        dialog.show()
    }

    private fun createQuickItem(name: String) {
        lifecycleScope.launch {
            try {
                val item = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val newItem = ItemEntity(
                        name = name,
                        parentId = FolderEntity.SYSTEM_ID_SHOPPING_LIST,
                        parentItemId = null,
                        quantity = 1,
                        addedDate = now,
                        addedBy = currentUser,
                        updatedDate = now,
                        updatedBy = currentUser,
                        itemType = "other",
                        itemSubtype = "other_other"
                    )
                    db.itemDao().insertItem(newItem)
                    OutboxLogger.itemChanged(
                        db = db,
                        item = newItem,
                        action = OutboxEntity.ACTION_CREATE,
                        changedBy = currentUser
                    )
                    newItem
                }

                Logger.log(TAG, "createQuickItem: '${item.name}' (id=${item.id})")
                Toast.makeText(this@ShoppingListActivity, "Добавлено: $name", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                loadItems()

            } catch (e: Exception) {
                Logger.log(TAG, "createQuickItem error: ${e.message}", e)
                Toast.makeText(this@ShoppingListActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // ЗАГРУЗКА
    // ============================================================

    private fun loadItems() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    viewModel.load(currentFilter)
                }
                val sorted = applySort(result.items)

                adapter.setBoughtIds(result.boughtIds)
                adapter.submitList(sorted)

                if (sorted.isEmpty()) {
                    layoutEmpty.visibility = View.VISIBLE
                    rvItems.visibility = View.GONE
                    textEmpty.text = when (currentFilter) {
                        ShoppingListViewModel.Filter.TO_BUY ->
                            "Список покупок пуст.\nНажмите + чтобы добавить."
                        ShoppingListViewModel.Filter.BOUGHT ->
                            "Пока ничего не куплено."
                        ShoppingListViewModel.Filter.ALL ->
                            "Пусто.\nНажмите + чтобы добавить в список покупок."
                    }
                } else {
                    layoutEmpty.visibility = View.GONE
                    rvItems.visibility = View.VISIBLE
                }

                textCounter.text = when (currentFilter) {
                    ShoppingListViewModel.Filter.TO_BUY -> "Купить: ${result.toBuyCount}"
                    ShoppingListViewModel.Filter.BOUGHT -> "Куплено: ${result.boughtCount}"
                    ShoppingListViewModel.Filter.ALL ->
                        "Купить: ${result.toBuyCount} · Куплено: ${result.boughtCount}"
                }
            } catch (e: Exception) {
                Logger.log(TAG, "loadItems error: ${e.message}", e)
            }
        }
    }

    // ============================================================
    // ДЕЙСТВИЯ
    // ============================================================

    private fun onToggleBought(item: ItemEntity) {
        val wasBought = item.parentId != FolderEntity.SYSTEM_ID_SHOPPING_LIST

        lifecycleScope.launch {
            try {
                if (wasBought) {
                    val ok = withContext(Dispatchers.IO) {
                        viewModel.unmarkBought(item.id, currentUser)
                    }
                    if (ok) {
                        Toast.makeText(
                            this@ShoppingListActivity,
                            "Вернули в список: ${item.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                        setResult(RESULT_OK)
                        loadItems()
                    } else {
                        Toast.makeText(
                            this@ShoppingListActivity,
                            "Не удалось вернуть в список",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadItems()
                    }
                } else {
                    val purchaseFolder = withContext(Dispatchers.IO) {
                        viewModel.markBought(item.id, currentUser)
                    }
                    if (purchaseFolder != null) {
                        Toast.makeText(
                            this@ShoppingListActivity,
                            "Куплено: ${item.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                        setResult(RESULT_OK)
                        openItemDetailEdit(item)
                    } else {
                        Toast.makeText(
                            this@ShoppingListActivity,
                            "Не удалось отметить как купленное",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadItems()
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "onToggleBought error: ${e.message}", e)
                Toast.makeText(this@ShoppingListActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                loadItems()
            }
        }
    }

    private fun openItemDetailEdit(item: ItemEntity) {
        val intent = Intent(this, ItemDetailActivity::class.java)
        intent.putExtra("item_id", item.id)
        intent.putExtra("edit_mode", true)
        itemDetailLauncher.launch(intent)
    }

    private fun confirmDelete(item: ItemEntity) {
        AlertDialog.Builder(this)
            .setTitle("Удалить покупку?")
            .setMessage("«${item.name}»")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            db.itemDao().deleteItemById(item.id)
                        }
                        Toast.makeText(this@ShoppingListActivity, "Удалено", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        loadItems()
                    } catch (e: Exception) {
                        Logger.log(TAG, "delete error: ${e.message}", e)
                        Toast.makeText(this@ShoppingListActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
