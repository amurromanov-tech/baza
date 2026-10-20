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
import com.family.base.util.VoiceInputHelper
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
 *
 * 🆕 v20.2.1: голосовой ввод в диалоге быстрого добавления покупки.
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

    /**
     * 🆕 v20.2.1: поле диалога быстрого добавления, в которое вставим
     * распознанный голосом текст.
     */
    private var pendingVoiceField: EditText? = null

    /**
     * 🆕 v20.2.1: launcher для голосового ввода.
     */
    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = VoiceInputHelper.extractRecognizedText(result.data)
            val field = pendingVoiceField
            if (!text.isNullOrEmpty() && field != null) {
                VoiceInputHelper.insertRecognizedText(field, text)
            }
        }
        pendingVoiceField = null
    }

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

    /**
     * 🆕 v20.2.1: диалог быстрого добавления.
     *
     * Раньше был голый EditText. Теперь — TextInputLayout с OUTLINE
     * и кастомной end-иконкой микрофона (как в остальных полях БАЗЫ).
     */
    private fun showQuickAddDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad, pad, 0)
        }

        // TextInputLayout с кастомной end-иконкой
        val til = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = "Что купить?"
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(R.drawable.ic_mic)
            setEndIconContentDescription("Голосовой ввод")
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val editText = TextInputEditText(this).apply {
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        til.addView(editText)

        // 🆕 v20.2.1: клик по микрофону
        til.setEndIconOnClickListener {
            startVoiceFor(editText)
        }

        container.addView(til)

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
                    til.error = "Введите название"
                    return@setOnClickListener
                }
                dialog.dismiss()
                createQuickItem(name)
            }
        }

        dialog.show()
    }

    /**
     * 🆕 v20.2.1: запуск голосового ввода для поля быстрого добавления.
     */
    private fun startVoiceFor(field: EditText) {
        pendingVoiceField = field
        VoiceInputHelper.startVoiceInput(this, voiceLauncher, "Говорите…")
    }

    /**
     * 🆕 v20.2.1: обработка разрешения RECORD_AUDIO.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == VoiceInputHelper.REQUEST_CODE_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val field = pendingVoiceField
                if (field != null) {
                    VoiceInputHelper.startVoiceInput(this, voiceLauncher, "Говорите…")
                }
            } else {
                pendingVoiceField = null
                Toast.makeText(
                    this,
                    "Без разрешения на микрофон голосовой ввод недоступен",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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
