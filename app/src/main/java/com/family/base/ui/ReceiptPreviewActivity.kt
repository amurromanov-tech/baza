package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
 *  - кнопка «Добавить в Покупки» — создаёт папку покупки
 *    «Покупка DD.MM.YYYY, <магазин> #N» в системной «🛒 Покупки»
 *    и кладёт туда все позиции чека (раз чек есть — значит уже куплено);
 *  - после успеха возвращает RESULT_OK с navigate_to_folder_id,
 *    чтобы MainActivity открыла созданную папку покупки;
 *  - кнопка «Отмена» — просто закрывает экран, ничего не сохраняя.
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
                    showLoading(false)
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

        adapter.submitList(receipt.items)

        binding.tvItemsCount.text = "Позиций: ${receipt.items.size}"
        binding.btnAddToShopping.isEnabled = receipt.items.isNotEmpty()
    }

    // ============================================================
    // ДОБАВЛЕНИЕ В ПОКУПКИ (папка покупки + позиции)
    // ============================================================

    private fun onAddToShoppingClicked() {
        val receipt = receiptData ?: return
        if (receipt.items.isEmpty()) {
            Toast.makeText(this, "В чеке нет позиций", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSaving) return

        val merchantLine = receipt.retailPlace.ifBlank { "магазин не указан" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Добавить в Покупки?")
            .setMessage(
                "Чек от: $merchantLine\n" +
                    "Позиций: ${receipt.items.size}\n\n" +
                    "Будет создана папка покупки в «🛒 Покупки»."
            )
            .setPositiveButton("Добавить") { _, _ -> addItemsToPurchases(receipt) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun addItemsToPurchases(receipt: ReceiptRepository.ReceiptData) {
        if (isSaving) return
        isSaving = true
        showLoading(true, "Создаю папку покупки…")

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val user = currentUser

                    // 1. Создаём папку покупки в системной «🛒 Покупки»,
                    //    с названием магазина (если есть).
                    val purchaseFolder = PurchaseFolderHelper.createPurchaseFolder(
                        db = db,
                        parentId = FolderEntity.SYSTEM_ID_PURCHASES,
                        date = now,
                        changedBy = user,
                        merchantName = receipt.retailPlace.ifBlank { null }
                    ) ?: return@withContext null

                    // 2. Кладём позиции чека внутрь этой папки.
                    var count = 0
                    receipt.items.forEach { receiptItem ->
                        val newItem = ItemEntity(
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
                        db.itemDao().insertItem(newItem)
                        OutboxLogger.itemChanged(
                            db = db,
                            item = newItem,
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
                    "addItemsToPurchases: '${folder.name}' (id=${folder.id}), позиций=$count"
                )
                Toast.makeText(
                    this@ReceiptPreviewActivity,
                    "Добавлено в «${folder.name}»: $count",
                    Toast.LENGTH_SHORT
                ).show()

                // Возвращаем id созданной папки — MainActivity откроет её.
                val resultIntent = Intent().apply {
                    putExtra("navigate_to_folder_id", folder.id)
                }
                setResult(RESULT_OK, resultIntent)
                finish()

            } catch (e: Exception) {
                showLoading(false)
                isSaving = false
                Logger.log(TAG, "addItemsToPurchases error: ${e.message}", e)
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
        binding.btnAddToShopping.isEnabled = !show && receiptData?.items?.isNotEmpty() == true
        binding.btnCancel.isEnabled = !show
    }
}
