package com.family.base.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.model.CheckItem
import com.family.base.data.parser.CheckParser
import com.family.base.databinding.ActivityCheckPreviewBinding
import com.family.base.ui.adapter.CheckPreviewAdapter
import com.family.base.util.Logger
import com.family.base.util.PurchaseFolderHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class CheckPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckPreviewBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: CheckPreviewAdapter
    private val TAG = "CheckPreviewActivity"

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    // Дата покупки (можно изменить через date picker)
    private var purchaseDate: Long = System.currentTimeMillis()

    // Распознанные товары
    private var parsedItems: MutableList<CheckItem> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== onCreate START ===")

        try {
            binding = ActivityCheckPreviewBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        db = AppDatabase.getInstance(this)

        // ===== Получаем текст от сканера =====
        val recognizedText = intent.getStringExtra(CheckScannerActivity.EXTRA_RECOGNIZED_TEXT) ?: ""
        val imagePath = intent.getStringExtra(CheckScannerActivity.EXTRA_IMAGE_PATH)

        Logger.log(TAG, "Recognized text length: ${recognizedText.length}")
        Logger.log(TAG, "Image path: $imagePath")

        if (recognizedText.isBlank()) {
            Toast.makeText(this, "Нет распознанного текста", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // ===== Парсим текст =====
        parsedItems = CheckParser.parse(recognizedText).toMutableList()
        Logger.log(TAG, "Parsed ${parsedItems.size} items")

        // ===== Настраиваем UI =====
        setupAdapter()
        setupListeners()
        updateDateButton()
        displayItems()
    }

    private fun setupAdapter() {
        adapter = CheckPreviewAdapter(
            onItemToggled = { updateTotals() },
            onItemRemoved = { item, position ->
                parsedItems.removeAt(position)
                adapter.submitList(parsedItems)
                updateTotals()
                if (parsedItems.isEmpty()) {
                    showEmptyState()
                }
            }
        )
        binding.rvItems.layoutManager = LinearLayoutManager(this)
        binding.rvItems.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCancel.setOnClickListener { finish() }

        binding.btnDate.setOnClickListener { showDatePicker() }

        binding.btnSave.setOnClickListener { saveItems() }

        binding.btnRetry.setOnClickListener {
            // Возвращаемся к сканеру
            finish()
        }
    }

    private fun updateDateButton() {
        binding.btnDate.text = dateFormat.format(Date(purchaseDate))
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance().apply { timeInMillis = purchaseDate }

        DatePickerDialog(
            this,
            { _, year, month, day ->
                val cal = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                purchaseDate = cal.timeInMillis
                updateDateButton()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ============================================================
    // ОТОБРАЖЕНИЕ СПИСКА
    // ============================================================
    private fun displayItems() {
        if (parsedItems.isEmpty()) {
            showEmptyState()
            return
        }

        binding.emptyState.visibility = View.GONE
        binding.rvItems.visibility = View.VISIBLE
        binding.bottomPanel.visibility = View.VISIBLE

        adapter.submitList(parsedItems)
        updateTotals()
    }

    private fun showEmptyState() {
        binding.rvItems.visibility = View.GONE
        binding.bottomPanel.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE

        binding.tvEmptyText.text = "Не удалось распознать товары"
        binding.tvEmptySubtext.text = "Попробуйте сфотографировать чек ещё раз — весь целиком, ровно, при хорошем освещении"
    }

    private fun updateTotals() {
        val selected = parsedItems.filter { it.isSelected }
        val total = selected.mapNotNull { it.totalPrice }.sum()

        val totalText = buildString {
            append("Выбрано: ${selected.size} ${pluralize(selected.size)}")
            if (total > 0) {
                append(", сумма ${formatPrice(total)} ₽")
            }
        }
        binding.tvTotal.text = totalText

        binding.btnSave.isEnabled = selected.isNotEmpty()
        binding.btnSave.alpha = if (selected.isNotEmpty()) 1f else 0.5f
    }

    // ============================================================
    // СОХРАНЕНИЕ
    // ============================================================
    private fun saveItems() {
        val selected = parsedItems.filter { it.isSelected }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Выберите хотя бы один товар", Toast.LENGTH_SHORT).show()
            return
        }

        Logger.log(TAG, "Saving ${selected.size} items")

        showLoading(true)

        lifecycleScope.launch {
            try {
                // 1. Создаём/находим папку «🛒 Покупки» в корне
                val purchasesFolder = withContext(Dispatchers.IO) {
                    PurchaseFolderHelper.getOrCreatePurchasesFolder(db)
                }

                if (purchasesFolder == null) {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        Toast.makeText(
                            this@CheckPreviewActivity,
                            "Не удалось создать папку «Покупки»",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                // 2. Создаём папку «Покупка DD.MM.YYYY #N»
                val purchaseFolder = withContext(Dispatchers.IO) {
                    PurchaseFolderHelper.createPurchaseFolder(
                        db = db,
                        parentId = purchasesFolder.id,
                        date = purchaseDate
                    )
                }

                if (purchaseFolder == null) {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        Toast.makeText(
                            this@CheckPreviewActivity,
                            "Не удалось создать папку покупки",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                Logger.log(TAG, "Created folder: ${purchaseFolder.name} (id=${purchaseFolder.id})")

                // 3. Сохраняем товары в эту папку
                val entities = selected.map { checkItem ->
                    ItemEntity(
                        id = UUID.randomUUID().toString(),
                        name = checkItem.name,
                        parentId = purchaseFolder.id,
                        quantity = checkItem.quantity,
                        price = checkItem.price,
                        addedBy = "user",
                        itemType = "food"  // по умолчанию — еда
                    ).apply {
                        computeExpiryFields()
                    }
                }

                withContext(Dispatchers.IO) {
                    db.itemDao().insertItems(entities)
                }

                Logger.log(TAG, "Saved ${entities.size} items")

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(
                        this@CheckPreviewActivity,
                        "✅ Добавлено ${entities.size} ${pluralize(entities.size)} в «${purchaseFolder.name}»",
                        Toast.LENGTH_LONG
                    ).show()

                    // Возвращаемся в каталог с указанием перейти в новую папку
                    val resultIntent = android.content.Intent().apply {
                        putExtra("navigate_to_folder_id", purchaseFolder.id)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }

            } catch (e: Exception) {
                Logger.log(TAG, "Error saving items", e)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(
                        this@CheckPreviewActivity,
                        "Ошибка сохранения: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================
    private fun showLoading(show: Boolean) {
        binding.btnSave.isEnabled = !show
        binding.btnCancel.isEnabled = !show
        binding.btnDate.isEnabled = !show
        binding.rvItems.alpha = if (show) 0.5f else 1f

        binding.tvTotal.text = if (show) "Сохранение…" else binding.tvTotal.text
    }

    private fun formatPrice(price: Double): String {
        return if (price % 1.0 == 0.0) {
            price.toInt().toString()
        } else {
            String.format("%.2f", price)
        }
    }

    private fun pluralize(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "товар"
            mod10 in 2..4 && (mod100 < 12 || mod100 > 14) -> "товара"
            else -> "товаров"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
