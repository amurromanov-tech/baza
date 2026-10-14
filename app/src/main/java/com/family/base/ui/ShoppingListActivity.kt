package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.ui.adapter.ShoppingListAdapter
import com.family.base.ui.viewmodel.ShoppingListViewModel
import com.family.base.util.Logger
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран «🛒 Список покупок».
 *
 * UX-клон «🏠 Домашних дел»:
 *  - Купить   — предметы в корне системной папки «🛒 Список покупок»;
 *  - Куплено  — предметы внутри подпапок «Покупок» (зачёркнуты и бледные);
 *  - Все      — оба списка.
 *
 * Чекбокс:
 *  - в «Купить»   → отметить куплено (переместить в /Покупки/Покупка дата #N
 *                   + открыть карточку в edit-режиме);
 *  - в «Куплено»  → снять отметку (вернуть в корень «Списка покупок»).
 *
 * Тап по строке → открыть карточку в edit-режиме.
 * Меню (⋮)     → редактировать / удалить.
 * FAB «+»      → AddItemActivity с parent_id = «🛒 Список покупок».
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

    private val addItemLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        loadItems()
    }

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

        fabAddItem.setOnClickListener {
            val intent = Intent(this, AddItemActivity::class.java)
            intent.putExtra("parent_id", FolderEntity.SYSTEM_ID_SHOPPING_LIST)
            addItemLauncher.launch(intent)
        }

        loadItems()
    }

    override fun onResume() {
        super.onResume()
        loadItems()
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
                adapter.setBoughtIds(result.boughtIds)
                adapter.submitList(result.items)

                if (result.items.isEmpty()) {
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

    /**
     * Чекбокс:
     *  - если предмет был «Купить» → пометить куплено + открыть карточку в edit.
     *  - если предмет был «Куплено» → снять отметку (вернуть в корень).
     */
    private fun onToggleBought(item: ItemEntity) {
        val wasBought = item.parentId != FolderEntity.SYSTEM_ID_SHOPPING_LIST

        lifecycleScope.launch {
            try {
                if (wasBought) {
                    // Снять «куплено» → вернуть в корень «Списка покупок».
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
                    // Пометить куплено + открыть карточку в edit-режиме.
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
                        // Открываем карточку в edit-режиме,
                        // чтобы пользователь сразу заполнил цену / срок / фото.
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

    /**
     * Открыть карточку предмета в edit-режиме (как в делах: тап по строке = редактирование).
     */
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
