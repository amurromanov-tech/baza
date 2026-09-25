package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.databinding.ActivityAuditBinding
import com.family.base.ui.adapter.AuditAdapter
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuditBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: AuditAdapter
    private val TAG = "AuditActivity"

    private var currentFilter: Filter = Filter.NONE
    private var currentSort: Sort = Sort.NAME_ASC
    private var currentItems: List<ItemEntity> = emptyList()

    private val LENT_LONG_DAYS = 30L

    private enum class Filter {
        NONE,
        ALL_PROBLEMS,
        NO_PRICE,
        NO_EXPIRY,
        NO_BARCODE,
        NO_DESCRIPTION,
        NO_TYPE,
        NO_PHOTO,
        DUPLICATES,
        LENT_LONG,
        EXPIRED
    }

    private enum class Sort(val label: String) {
        NAME_ASC("🔤 По имени (А→Я)"),
        ADDED_DESC("📅 По дате добавления (новые)"),
        EXPIRY_ASC("⏰ По сроку годности (ближайшие)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== AuditActivity onCreate START ===")

        try {
            binding = ActivityAuditBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        db = AppDatabase.getInstance(this)

        adapter = AuditAdapter(
            onItemClick = { item -> openItemInEditMode(item) },
            scope = lifecycleScope,
            dbProvider = { db }
        )

        binding.rvAudit.layoutManager = LinearLayoutManager(this)
        binding.rvAudit.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSort.setOnClickListener { showSortDialog() }

        setupChipListeners()
        loadSummaryAndCounts()

        showEmptyState("Выберите категорию", "Тапните по чипу сверху, чтобы увидеть список")
    }

    private fun openItemInEditMode(item: ItemEntity) {
        val intent = Intent(this, ItemDetailActivity::class.java)
        intent.putExtra("item_id", item.id)
        intent.putExtra("edit_mode", true)
        startActivity(intent)
    }

    private fun setupChipListeners() {
        binding.chipAllProblems.setOnClickListener { selectFilter(Filter.ALL_PROBLEMS) }
        binding.chipNoPrice.setOnClickListener { selectFilter(Filter.NO_PRICE) }
        binding.chipNoExpiry.setOnClickListener { selectFilter(Filter.NO_EXPIRY) }
        binding.chipNoBarcode.setOnClickListener { selectFilter(Filter.NO_BARCODE) }
        binding.chipNoDescription.setOnClickListener { selectFilter(Filter.NO_DESCRIPTION) }
        binding.chipNoType.setOnClickListener { selectFilter(Filter.NO_TYPE) }
        binding.chipNoPhoto.setOnClickListener { selectFilter(Filter.NO_PHOTO) }
        binding.chipDuplicates.setOnClickListener { selectFilter(Filter.DUPLICATES) }
        binding.chipLentLong.setOnClickListener { selectFilter(Filter.LENT_LONG) }
        binding.chipExpired.setOnClickListener { selectFilter(Filter.EXPIRED) }
    }

    // ============================================================
    // СВОДКА + СЧЁТЧИКИ
    // ============================================================
    private fun loadSummaryAndCounts() {
        lifecycleScope.launch {
            try {
                val thresholdDate = System.currentTimeMillis() - (LENT_LONG_DAYS * 24 * 60 * 60 * 1000)
                val now = System.currentTimeMillis()

                val noPrice = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutPrice() }
                val noExpiry = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutExpiry() }
                val noBarcode = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutBarcode() }
                val noDesc = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutDescription() }
                val noType = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutType() }
                val lentLong = withContext(Dispatchers.IO) { db.itemDao().countItemsLentLongAgo(thresholdDate) }
                val expired = withContext(Dispatchers.IO) { db.itemDao().countExpiredItems(now) }

                val duplicates = withContext(Dispatchers.IO) { loadDuplicateItems() }
                val noPhoto = withContext(Dispatchers.IO) { loadItemsWithoutPhoto() }
                val allProblems = withContext(Dispatchers.IO) { loadAllProblemItems() }

                withContext(Dispatchers.Main) {
                    val totalActive = withContext(Dispatchers.IO) { db.itemDao().getActiveItemsCount() }
                    binding.tvSummary.text = "📦 $totalActive ${pluralizeItem(totalActive)} • ⚠️ ${allProblems.size} требуют внимания"

                    setChipText(binding.chipAllProblems, "🚨 Все проблемы", allProblems.size)
                    setChipText(binding.chipNoPrice, "💰 Без цены", noPrice)
                    setChipText(binding.chipNoExpiry, "📅 Без срока", noExpiry)
                    setChipText(binding.chipNoBarcode, "🆔 Без кода", noBarcode)
                    setChipText(binding.chipNoDescription, "📝 Без описания", noDesc)
                    setChipText(binding.chipNoType, "📦 Без типа", noType)
                    setChipText(binding.chipNoPhoto, "📷 Без фото", noPhoto.size)
                    setChipText(binding.chipDuplicates, "👥 Дубликаты кода", duplicates.size)
                    setChipText(binding.chipLentLong, "🤝 Выданы давно", lentLong)
                    setChipText(binding.chipExpired, "⏰ Просрочены", expired)
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading counts", e)
            }
        }
    }

    private fun setChipText(chip: Chip, baseText: String, count: Int) {
        chip.text = if (count > 0) "$baseText ($count)" else baseText
        chip.isEnabled = count > 0
        chip.alpha = if (count > 0) 1f else 0.4f
    }

    // ============================================================
    // ВЫБОР ФИЛЬТРА
    // ============================================================
    private fun selectFilter(filter: Filter) {
        if (currentFilter == filter) {
            currentFilter = Filter.NONE
            currentItems = emptyList()
            adapter.submitList(emptyList())
            showEmptyState("Выберите категорию", "Тапните по чипу сверху, чтобы увидеть список")
            uncheckAllChips()
            return
        }

        currentFilter = filter
        uncheckAllChips()
        checkChip(filter)

        loadItems(filter)
    }

    private fun checkChip(filter: Filter) {
        when (filter) {
            Filter.ALL_PROBLEMS -> binding.chipAllProblems.isChecked = true
            Filter.NO_PRICE -> binding.chipNoPrice.isChecked = true
            Filter.NO_EXPIRY -> binding.chipNoExpiry.isChecked = true
            Filter.NO_BARCODE -> binding.chipNoBarcode.isChecked = true
            Filter.NO_DESCRIPTION -> binding.chipNoDescription.isChecked = true
            Filter.NO_TYPE -> binding.chipNoType.isChecked = true
            Filter.NO_PHOTO -> binding.chipNoPhoto.isChecked = true
            Filter.DUPLICATES -> binding.chipDuplicates.isChecked = true
            Filter.LENT_LONG -> binding.chipLentLong.isChecked = true
            Filter.EXPIRED -> binding.chipExpired.isChecked = true
            else -> {}
        }
    }

    private fun uncheckAllChips() {
        binding.chipAllProblems.isChecked = false
        binding.chipNoPrice.isChecked = false
        binding.chipNoExpiry.isChecked = false
        binding.chipNoBarcode.isChecked = false
        binding.chipNoDescription.isChecked = false
        binding.chipNoType.isChecked = false
        binding.chipNoPhoto.isChecked = false
        binding.chipDuplicates.isChecked = false
        binding.chipLentLong.isChecked = false
        binding.chipExpired.isChecked = false
    }

    // ============================================================
    // ЗАГРУЗКА СПИСКА ПО ФИЛЬТРУ
    // ============================================================
    private fun loadItems(filter: Filter) {
        lifecycleScope.launch {
            try {
                val thresholdDate = System.currentTimeMillis() - (LENT_LONG_DAYS * 24 * 60 * 60 * 1000)
                val now = System.currentTimeMillis()

                val items: List<ItemEntity> = withContext(Dispatchers.IO) {
                    when (filter) {
                        Filter.ALL_PROBLEMS -> loadAllProblemItems()
                        Filter.NO_PRICE -> db.itemDao().getItemsWithoutPrice()
                        Filter.NO_EXPIRY -> db.itemDao().getItemsWithoutExpiry()
                        Filter.NO_BARCODE -> db.itemDao().getItemsWithoutBarcode()
                        Filter.NO_DESCRIPTION -> db.itemDao().getItemsWithoutDescription()
                        Filter.NO_TYPE -> db.itemDao().getItemsWithoutType()
                        Filter.NO_PHOTO -> loadItemsWithoutPhoto()
                        Filter.DUPLICATES -> loadDuplicateItems()
                        Filter.LENT_LONG -> db.itemDao().getItemsLentLongAgo(thresholdDate)
                        Filter.EXPIRED -> db.itemDao().getExpiredItems(now)
                        Filter.NONE -> emptyList()
                    }
                }

                val sorted = applySort(items)

                withContext(Dispatchers.Main) {
                    currentItems = sorted
                    if (sorted.isEmpty()) {
                        adapter.submitList(emptyList())
                        val (title, sub) = getEmptyTexts(filter)
                        showEmptyState(title, sub)
                    } else {
                        binding.emptyState.visibility = View.GONE
                        binding.rvAudit.visibility = View.VISIBLE
                        adapter.submitList(sorted)
                        binding.tvCount.text = "${getFilterTitle(filter)}: ${sorted.size} ${pluralizeItem(sorted.size)} • Сортировка: ${currentSort.label}"
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading items", e)
                Toast.makeText(this@AuditActivity, "Ошибка загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // ЗАГРУЗКА «БЕЗ ФОТО»
    // ============================================================
    private suspend fun loadItemsWithoutPhoto(): List<ItemEntity> {
        val all = db.itemDao().getAllItems()
        return all.filter { item ->
            val f = ImageUtils.getLocalImageFile(this, item.id)
            f == null || !f.exists()
        }
    }

    // ============================================================
    // ЗАГРУЗКА ДУБЛИКАТОВ
    // ============================================================
    private suspend fun loadDuplicateItems(): List<ItemEntity> {
        val barcodes = db.itemDao().getDuplicateBarcodes()
        if (barcodes.isEmpty()) return emptyList()

        val result = mutableListOf<ItemEntity>()
        barcodes.forEach { code ->
            result.addAll(db.itemDao().getItemsBySameBarcode(code))
        }
        return result
    }

    // ============================================================
    // ЗАГРУЗКА «ВСЕ ПРОБЛЕМЫ»
    // ============================================================
    private suspend fun loadAllProblemItems(): List<ItemEntity> {
        val thresholdDate = System.currentTimeMillis() - (LENT_LONG_DAYS * 24 * 60 * 60 * 1000)
        val now = System.currentTimeMillis()

        val resultMap = mutableMapOf<String, ItemEntity>()

        db.itemDao().getItemsWithoutPrice().forEach { resultMap[it.id] = it }
        db.itemDao().getItemsWithoutExpiry().forEach { resultMap[it.id] = it }
        db.itemDao().getItemsWithoutBarcode().forEach { resultMap[it.id] = it }
        db.itemDao().getItemsWithoutDescription().forEach { resultMap[it.id] = it }
        db.itemDao().getItemsWithoutType().forEach { resultMap[it.id] = it }
        db.itemDao().getItemsLentLongAgo(thresholdDate).forEach { resultMap[it.id] = it }
        db.itemDao().getExpiredItems(now).forEach { resultMap[it.id] = it }
        loadItemsWithoutPhoto().forEach { resultMap[it.id] = it }
        loadDuplicateItems().forEach { resultMap[it.id] = it }

        return resultMap.values.toList()
    }

    // ============================================================
    // СОРТИРОВКА
    // ============================================================
    private fun applySort(items: List<ItemEntity>): List<ItemEntity> {
        return when (currentSort) {
            Sort.NAME_ASC -> items.sortedBy { it.name.lowercase() }

            Sort.ADDED_DESC -> items.sortedByDescending { it.addedDate }

            Sort.EXPIRY_ASC -> items.sortedWith(
                compareBy(
                    { it.expiryDate == null },
                    { it.expiryDate ?: Long.MAX_VALUE }
                )
            )
        }
    }

    private fun showSortDialog() {
        val options = Sort.values().map { it.label }.toTypedArray()
        val checkedItem = Sort.values().indexOf(currentSort)

        AlertDialog.Builder(this)
            .setTitle("Сортировка")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                currentSort = Sort.values()[which]
                dialog.dismiss()
                if (currentFilter != Filter.NONE) {
                    loadItems(currentFilter)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================
    private fun getFilterTitle(filter: Filter): String = when (filter) {
        Filter.ALL_PROBLEMS -> "🚨 Все проблемы"
        Filter.NO_PRICE -> "💰 Без цены"
        Filter.NO_EXPIRY -> "📅 Без срока годности"
        Filter.NO_BARCODE -> "🆔 Без штрих-кода"
        Filter.NO_DESCRIPTION -> "📝 Без описания"
        Filter.NO_TYPE -> "📦 Без типа"
        Filter.NO_PHOTO -> "📷 Без фото"
        Filter.DUPLICATES -> "👥 Дубликаты штрих-кода"
        Filter.LENT_LONG -> "🤝 Выданы давно"
        Filter.EXPIRED -> "⏰ Просрочены"
        Filter.NONE -> ""
    }

    private fun getEmptyTexts(filter: Filter): Pair<String, String> = when (filter) {
        Filter.ALL_PROBLEMS -> "🎉 Проблем нет" to "Все данные заполнены"
        Filter.NO_PRICE -> "🎉 У всех предметов указана цена" to "Ничего не нужно дополнять"
        Filter.NO_EXPIRY -> "🎉 У всех продуктов есть срок годности" to "Ничего не нужно дополнять"
        Filter.NO_BARCODE -> "🎉 У всех предметов есть штрих-код" to "Ничего не нужно дополнять"
        Filter.NO_DESCRIPTION -> "🎉 У всех предметов есть описание" to "Ничего не нужно дополнять"
        Filter.NO_TYPE -> "🎉 У всех предметов указан тип" to "Ничего не нужно дополнять"
        Filter.NO_PHOTO -> "🎉 У всех предметов есть фото" to "Ничего не нужно дополнять"
        Filter.DUPLICATES -> "🎉 Дубликатов нет" to "Все штрих-коды уникальны"
        Filter.LENT_LONG -> "🎉 Нет предметов, выданных давно" to "Все займы свежие"
        Filter.EXPIRED -> "🎉 Нет просроченных предметов в базе" to "Отличная работа!"
        Filter.NONE -> "Выберите категорию" to "Тапните по чипу сверху, чтобы увидеть список"
    }

    private fun showEmptyState(title: String, subtext: String) {
        binding.rvAudit.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.tvEmptyText.text = title
        binding.tvEmptySubtext.text = subtext
        binding.tvCount.text = ""
    }

    private fun pluralizeItem(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "предмет"
            mod10 in 2..4 && (mod100 < 12 || mod100 > 14) -> "предмета"
            else -> "предметов"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
