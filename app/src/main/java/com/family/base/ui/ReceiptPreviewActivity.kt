package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxLogger
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.repository.ReceiptRepository
import com.family.base.data.TokenStorage
import com.family.base.databinding.ActivityReceiptPreviewBinding
import com.family.base.ui.adapter.ReceiptItemsAdapter
import com.family.base.util.ItemHistoryHelper
import com.family.base.util.Logger
import com.family.base.util.PurchaseFolderHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🆕 Экран предпросмотра чека, полученного через proverkacheka.com.
 *
 * Логика:
 *  - получает qrRaw в EXTRA_QR_RAW, отправляет в ReceiptRepository;
 *  - показывает шапку (магазин, адрес, дата, сумма) и список позиций;
 *  - для каждой позиции ищет «эталон» в истории (ItemHistoryHelper.findMatches):
 *      • 1 совпадение → автоподстановка полей (A);
 *      • >1 совпадений → диалог выбора по очереди (B);
 *      • 0 → создаём как раньше (пустые поля);
 *  - кнопка «Добавить в Покупки» — создаёт папку покупки
 *    «Покупка DD.MM.YYYY, <магазин> #N» в системной «🛒 Покупки»
 *    и кладёт туда все позиции чека. Существующие активные товары
 *    НЕ трогаем, quantity НЕ увеличиваем — всегда новые ItemEntity
 *    (перестраховка от «инфляции цены»);
 *  - после успеха возвращает RESULT_OK с navigate_to_folder_id;
 *  - кнопка «Отмена» — закрывает экран, ничего не сохраняя.
 */
class ReceiptPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiptPreviewBinding
    private val TAG = "ReceiptPreviewActivity"

    private lateinit var db: AppDatabase
    private lateinit var tokenStorage: TokenStorage
    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var adapter: ReceiptItemsAdapter

    private var receiptData: ReceiptRepository.ReceiptData? = null
    private var isLoading = false
    private var isSaving = false

    /**
     * 🆕 Для каждой позиции чека — список найденных эталонов.
     * Индекс совпадает с индексом в receiptData.items.
     */
    private var matchesByPosition: List<List<ItemEntity>> = emptyList()

    companion object {
        const val EXTRA_QR_RAW = "extra_qr_raw"
    }

    private val currentUser: String
        get() = tokenStorage.getCurrentUser()
            ?: tokenStorage.getUserDisplayName()
            ?: "Пользователь"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiptPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            db = AppDatabase.getInstance(this)
            tokenStorage = TokenStorage(this)
            receiptRepository = ReceiptRepository()
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: failed to init: ${e.message}", e)
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ReceiptItemsAdapter()
        binding.rvItems.layoutManager = LinearLayoutManager(this)
        binding.rvItems.adapter = adapter

        binding.btnAddToShopping.setOnClickListener { onAddToShoppingClicked() }
        binding.btnCancel.setOnClickListener { finish() }

        val qrRaw = intent.getStringExtra(EXTRA_QR_RAW)
        if (qrRaw.isNullOrBlank()) {
            Logger.log(TAG, "EXTRA_QR_RAW is empty — finishing")
            Toast.makeText(this, "Нет данных QR-кода", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadReceipt(qrRaw)
    }

    // ============================================================
    // ЗАГРУЗКА ЧЕКА
    // ============================================================

    private fun loadReceipt(qrRaw: String) {
        if (isLoading) return
        isLoading = true
        showLoading(true, "Запрашиваю чек…")

        lifecycleScope.launch {
            val result = receiptRepository.fetchReceipt(qrRaw)
            isLoading = false

            when (result) {
                is ReceiptRepository.Result.Success -> {
                    receiptData = result.receipt
                    showReceipt(result.receipt)
                    loadHistoryMatches(result.receipt)
                }
                is ReceiptRepository.Result.Error -> {
                    showLoading(false)
                    binding.layoutContent.visibility = View.GONE
                    binding.layoutError.visibility = View.VISIBLE
                    binding.tvError.text = "Не удалось получить чек:\n${result.message}"
                    Logger.log(TAG, "loadReceipt error code=${result.code}: ${result.message}")
                }
            }
        }
    }

    private fun showReceipt(receipt: ReceiptRepository.ReceiptData) {
        binding.layoutError.visibility = View.GONE
        binding.layoutContent.visibility = View.VISIBLE

        binding.tvRetailPlace.text = receipt.retailPlace.ifBlank { "Магазин не указан" }
        binding.tvAddress.text = receipt.retailPlaceAddress.ifBlank { "—" }
        binding.tvDateTime.text = formatDateTime(receipt.dateTime)
        binding.tvTotalSum.text = formatRubles(receipt.totalSumKopecks)

        // Показываем без бейджей — бейджи появятся после поиска истории
        val uiList = receipt.items.map { ReceiptItemsAdapter.ReceiptPositionUi(item = it) }
        adapter.submitList(uiList)

        binding.tvItemsCount.text = "Позиций: ${receipt.items.size}"
        binding.btnAddToShopping.isEnabled = false // включаем после поиска истории
    }

    /**
     * 🆕 Поиск эталонов в истории для каждой позиции чека.
     * После завершения — обновляем адаптер с бейджами и включаем кнопку.
     */
    private fun loadHistoryMatches(receipt: ReceiptRepository.ReceiptData) {
        showLoading(true, "Ищу совпадения в истории…")

        lifecycleScope.launch {
            try {
                val matches = withContext(Dispatchers.IO) {
                    receipt.items.map { receiptItem ->
                        ItemHistoryHelper.findMatches(
                            db = db,
                            name = receiptItem.name,
                            barcode = null // в чеке штрих-кода нет, только имя
                        )
                    }
                }

                matchesByPosition = matches

                val uiList = receipt.items.mapIndexed { index, receiptItem ->
                    val positionMatches = matches.getOrNull(index).orEmpty()
                    ReceiptItemsAdapter.ReceiptPositionUi(
                        item = receiptItem,
                        hasMatch = positionMatches.isNotEmpty(),
                        matchCount = positionMatches.size
                    )
                }
                adapter.submitList(uiList)

                val withMatches = matches.count { it.isNotEmpty() }
                Logger.log(
                    TAG,
                    "loadHistoryMatches: позиций=${receipt.items.size}, с совпадениями=$withMatches"
                )

                showLoading(false)
                binding.btnAddToShopping.isEnabled = receipt.items.isNotEmpty()

            } catch (e: Exception) {
                Logger.log(TAG, "loadHistoryMatches error: ${e.message}", e)
                showLoading(false)
                binding.btnAddToShopping.isEnabled = receipt.items.isNotEmpty()
            }
        }
    }

    // ============================================================
    // ДОБАВЛЕНИЕ В ПОКУПКИ (A + B)
    // ============================================================

    private fun onAddToShoppingClicked() {
        val receipt = receiptData ?: return
        if (receipt.items.isEmpty()) {
            Toast.makeText(this, "В чеке нет позиций", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSaving) return

        val merchantLine = receipt.retailPlace.ifBlank { "магазин не указан" }
        val withMatches = matchesByPosition.count { it.isNotEmpty() }

        val message = buildString {
            append("Чек от: $merchantLine\n")
            append("Позиций: ${receipt.items.size}\n")
            if (withMatches > 0) {
                append("Совпадений в истории: $withMatches\n")
                append("\nДля позиций с совпадением поля будут заполнены автоматически. ")
                append("Если вариантов несколько — спрошу по очереди.\n")
            }
            append("\nБудет создана папка покупки в «🛒 Покупки».")
        }

        AlertDialog.Builder(this)
            .setTitle("Добавить в Покупки?")
            .setMessage(message)
            .setPositiveButton("Добавить") { _, _ -> startProcessing(receipt) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * 🆕 Обработка позиций по очереди:
     *  - 0 совпадений → создаём ItemEntity как есть;
     *  - 1 совпадение → применяем шаблон (A);
     *  - >1 → показываем диалог выбора (B), ждём ответа, идём к следующей.
     *
     * После обработки всех позиций — создаём папку покупки и сохраняем.
     */
    private fun startProcessing(receipt: ReceiptRepository.ReceiptData) {
        isSaving = true
        showLoading(true, "Обрабатываю позиции…")

        val now = System.currentTimeMillis()
        val user = currentUser

        // Шаблоны, выбранные пользователем (или авто)
        val templates = mutableListOf<ItemEntity?>() // размер = items.size

        // Рекурсивная обработка по индексу
        processPosition(
            receipt = receipt,
            index = 0,
            templates = templates,
            now = now,
            user = user
        )
    }

    private fun processPosition(
        receipt: ReceiptRepository.ReceiptData,
        index: Int,
        templates: MutableList<ItemEntity?>,
        now: Long,
        user: String
    ) {
        if (index >= receipt.items.size) {
            // Все позиции обработаны — сохраняем
            saveReceiptWithTemplates(receipt, templates, now, user)
            return
        }

        val receiptItem = receipt.items[index]
        val positionMatches = matchesByPosition.getOrNull(index).orEmpty()

        when {
            positionMatches.isEmpty() -> {
                // Нет совпадений — шаблон не нужен
                templates.add(null)
                processPosition(receipt, index + 1, templates, now, user)
            }
            positionMatches.size == 1 -> {
                // A: одно совпадение — применяем автоматически
                templates.add(positionMatches.first())
                Logger.log(
                    TAG,
                    "processPosition[$index] '${receiptItem.name}': auto-template '${positionMatches.first().name}'"
                )
                processPosition(receipt, index + 1, templates, now, user)
            }
            else -> {
                // B: несколько — диалог выбора
                showMatchDialog(receipt, index, receiptItem, positionMatches, templates, now, user)
            }
        }
    }

    /**
     * 🆕 B: диалог выбора эталона для позиции.
     * Показываем список «name (статус, дата)» + пункт «Создать новый».
     */
    private fun showMatchDialog(
        receipt: ReceiptRepository.ReceiptData,
        index: Int,
        receiptItem: ReceiptRepository.ReceiptItem,
        matches: List<ItemEntity>,
        templates: MutableList<ItemEntity?>,
        now: Long,
        user: String
    ) {
        showLoading(false)

        val labels = matches.map { ItemHistoryHelper.describe(it) }.toMutableList()
        labels.add("➕ Создать новый (без шаблона)")

        AlertDialog.Builder(this)
            .setTitle("«${receiptItem.name}»")
            .setMessage("Найдено в истории: ${matches.size}. Выберите эталон:")
            .setItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                val chosen: ItemEntity? = if (which < matches.size) matches[which] else null
                templates.add(chosen)
                Logger.log(
                    TAG,
                    "processPosition[$index] '${receiptItem.name}': user chose " +
                        (chosen?.let { "'${it.name}'" } ?: "new")
                )
                showLoading(true, "Обрабатываю позиции…")
                processPosition(receipt, index + 1, templates, now, user)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 🆕 Финальный шаг: создаём папку покупки, для каждой позиции —
     * новый ItemEntity (с применённым шаблоном, если был).
     */
    private fun saveReceiptWithTemplates(
        receipt: ReceiptRepository.ReceiptData,
        templates: List<ItemEntity?>,
        now: Long,
        user: String
    ) {
        showLoading(true, "Создаю папку покупки…")

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // 1. Папка покупки
                    val purchaseFolder = PurchaseFolderHelper.createPurchaseFolder(
                        db = db,
                        parentId = FolderEntity.SYSTEM_ID_PURCHASES,
                        date = now,
                        changedBy = user,
                        merchantName = receipt.retailPlace.ifBlank { null }
                    ) ?: return@withContext null

                    // 2. Позиции
                    var count = 0
                    receipt.items.forEachIndexed { index, receiptItem ->
                        val template = templates.getOrNull(index)

                        val baseItem = ItemEntity(
                            name = receiptItem.name,
                            parentId = purchaseFolder.id,
                            parentItemId = null,
                            quantity = receiptItem.quantity.toInt().coerceAtLeast(1),
                            price = receiptItem.priceKopecks / 100.0,
                            addedDate = now,
                            addedBy = user,
                            updatedDate = now,
                            updatedBy = user,
                            itemType = "other",
                            itemSubtype = "other_other",
                            purchaseDate = now
                        )

                        // A/B: применяем шаблон (если есть)
                        val finalItem = if (template != null) {
                            ItemHistoryHelper.applyTemplate(baseItem, template)
                        } else {
                            baseItem
                        }

                        db.itemDao().insertItem(finalItem)
                        OutboxLogger.itemChanged(
                            db = db,
                            item = finalItem,
                            action = OutboxEntity.ACTION_CREATE,
                            changedBy = user
                        )
                        count++
                    }

                    Pair(purchaseFolder, count)
                }

                if (result == null) {
                    showLoading(false)
                    isSaving = false
                    Toast.makeText(
                        this@ReceiptPreviewActivity,
                        "Не удалось создать папку покупки",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val (folder, count) = result
                showLoading(false)
                Logger.log(
                    TAG,
                    "saveReceiptWithTemplates: '${folder.name}' (id=${folder.id}), позиций=$count"
                )
                Toast.makeText(
                    this@ReceiptPreviewActivity,
                    "Добавлено в «${folder.name}»: $count",
                    Toast.LENGTH_SHORT
                ).show()

                val resultIntent = Intent().apply {
                    putExtra("navigate_to_folder_id", folder.id)
                }
                setResult(RESULT_OK, resultIntent)
                finish()

            } catch (e: Exception) {
                showLoading(false)
                isSaving = false
                Logger.log(TAG, "saveReceiptWithTemplates error: ${e.message}", e)
                Toast.makeText(
                    this@ReceiptPreviewActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ============================================================
    // ФОРМАТИРОВАНИЕ
    // ============================================================

    private fun formatRubles(kopecks: Long): String {
        val rubles = kopecks / 100.0
        return String.format(Locale.getDefault(), "%.2f ₽", rubles)
    }

    private fun formatDateTime(raw: String): String {
        if (raw.isBlank()) return "—"
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = parser.parse(raw) ?: return raw
            val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            formatter.format(date)
        } catch (e: Exception) {
            raw
        }
    }

    // ============================================================
    // UI
    // ============================================================

    private fun showLoading(show: Boolean, text: String = "") {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show && text.isNotEmpty()) {
            binding.tvLoadingText.text = text
        }
        binding.btnAddToShopping.isEnabled = !show &&
            !isSaving &&
            receiptData?.items?.isNotEmpty() == true
        binding.btnCancel.isEnabled = !show && !isSaving
    }
}
