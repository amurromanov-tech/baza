package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.databinding.ActivityAuditBinding
import com.family.base.ui.adapter.AuditAdapter
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

    private enum class Filter {
        NONE,
        NO_PRICE,
        NO_EXPIRY,
        NO_BARCODE,
        NO_DESCRIPTION,
        NO_TYPE,
        LENT_LONG,
        EXPIRED
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
            onItemClick = { item ->
                val intent = Intent(this, ItemDetailActivity::class.java)
                intent.putExtra("item_id", item.id)
                startActivity(intent)
            },
            scope = lifecycleScope,
            dbProvider = { db }
        )

        binding.rvAudit.layoutManager = LinearLayoutManager(this)
        binding.rvAudit.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        setupChipListeners()
        loadCountsForAllChips()

        showEmptyState("Выберите категорию", "Тапните по чипу сверху, чтобы увидеть список")
    }

    private fun setupChipListeners() {
        binding.chipNoPrice.setOnClickListener { selectFilter(Filter.NO_PRICE) }
        binding.chipNoExpiry.setOnClickListener { selectFilter(Filter.NO_EXPIRY) }
        binding.chipNoBarcode.setOnClickListener { selectFilter(Filter.NO_BARCODE) }
        binding.chipNoDescription.setOnClickListener { selectFilter(Filter.NO_DESCRIPTION) }
        binding.chipNoType.setOnClickListener { selectFilter(Filter.NO_TYPE) }
        binding.chipLentLong.setOnClickListener { selectFilter(Filter.LENT_LONG) }
        binding.chipExpired.setOnClickListener { selectFilter(Filter.EXPIRED) }
    }

    // ============================================================
    // ЗАГРУЗКА СЧЁТЧИКОВ ДЛЯ ВСЕХ ЧИПОВ
    // ============================================================
    private fun loadCountsForAllChips() {
        lifecycleScope.launch {
            try {
                val thresholdDate = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000) // 30 дней назад
                val now = System.currentTimeMillis()

                val noPrice = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutPrice() }
                val noExpiry = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutExpiry() }
                val noBarcode = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutBarcode() }
                val noDesc = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutDescription() }
                val noType = withContext(Dispatchers.IO) { db.itemDao().countItemsWithoutType() }
                val lentLong = withContext(Dispatchers.IO) { db.itemDao().countItemsLentLongAgo(thresholdDate) }
                val expired = withContext(Dispatchers.IO) { db.itemDao().countExpiredItems(now) }

                withContext(Dispatchers.Main) {
                    setChipText(binding.chipNoPrice, "💰 Без цены", noPrice)
                    setChipText(binding.chipNoExpiry, "📅 Без срока", noExpiry)
                    setChipText(binding.chipNoBarcode, "🆔 Без кода", noBarcode)
                    setChipText(binding.chipNoDescription, "📝 Без описания", noDesc)
                    setChipText(binding.chipNoType, "📦 Без типа", noType)
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
        // Повторный тап по тому же фильтру — снимаем
        if (currentFilter == filter) {
            currentFilter = Filter.NONE
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
            Filter.NO_PRICE -> binding.chipNoPrice.isChecked = true
            Filter.NO_EXPIRY -> binding.chipNoExpiry.isChecked = true
            Filter.NO_BARCODE -> binding.chipNoBarcode.isChecked = true
            Filter.NO_DESCRIPTION -> binding.chipNoDescription.isChecked = true
            Filter.NO_TYPE -> binding.chipNoType.isChecked = true
            Filter.LENT_LONG -> binding.chipLentLong.isChecked = true
            Filter.EXPIRED -> binding.chipExpired.isChecked = true
            else -> {}
        }
    }

    private fun uncheckAllChips() {
        binding.chipNoPrice.isChecked = false
        binding.chipNoExpiry.isChecked = false
        binding.chipNoBarcode.isChecked = false
        binding.chipNoDescription.isChecked = false
        binding.chipNoType.isChecked = false
        binding.chipLentLong.isChecked = false
        binding.chipExpired.isChecked = false
    }

    // ============================================================
    // ЗАГРУЗКА СПИСКА ПО ФИЛЬТРУ
    // ============================================================
    private fun loadItems(filter: Filter) {
        lifecycleScope.launch {
            try {
                val thresholdDate = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                val now = System.currentTimeMillis()

                val items: List<ItemEntity> = withContext(Dispatchers.IO) {
                    when (filter) {
                        Filter.NO_PRICE -> db.itemDao().getItemsWithoutPrice()
                        Filter.NO_EXPIRY -> db.itemDao().getItemsWithoutExpiry()
                        Filter.NO_BARCODE -> db.itemDao().getItemsWithoutBarcode()
                        Filter.NO_DESCRIPTION -> db.itemDao().getItemsWithoutDescription()
                        Filter.NO_TYPE -> db.itemDao().getItemsWithoutType()
                        Filter.LENT_LONG -> db.itemDao().getItemsLentLongAgo(thresholdDate)
                        Filter.EXPIRED -> db.itemDao().getExpiredItems(now)
                        Filter.NONE -> emptyList()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (items.isEmpty()) {
                        adapter.submitList(emptyList())
                        val (title, sub) = getEmptyTexts(filter)
                        showEmptyState(title, sub)
                    } else {
                        binding.emptyState.visibility = View.GONE
                        binding.rvAudit.visibility = View.VISIBLE
                        adapter.submitList(items)
                        binding.tvCount.text = "${getFilterTitle(filter)}: ${items.size} ${pluralize(items.size)}"
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading items", e)
                Toast.makeText(this@AuditActivity, "Ошибка загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFilterTitle(filter: Filter): String = when (filter) {
        Filter.NO_PRICE -> "💰 Без цены"
        Filter.NO_EXPIRY -> "📅 Без срока годности"
        Filter.NO_BARCODE -> "🆔 Без штрих-кода"
        Filter.NO_DESCRIPTION -> "📝 Без описания"
        Filter.NO_TYPE -> "📦 Без типа"
        Filter.LENT_LONG -> "🤝 Выданы давно"
        Filter.EXPIRED -> "⏰ Просрочены"
        Filter.NONE -> ""
    }

    private fun getEmptyTexts(filter: Filter): Pair<String, String> = when (filter) {
        Filter.NO_PRICE -> "🎉 У всех предметов указана цена" to "Ничего не нужно дополнять"
        Filter.NO_EXPIRY -> "🎉 У всех продуктов есть срок годности" to "Ничего не нужно дополнять"
        Filter.NO_BARCODE -> "🎉 У всех предметов есть штрих-код" to "Ничего не нужно дополнять"
        Filter.NO_DESCRIPTION -> "🎉 У всех предметов есть описание" to "Ничего не нужно дополнять"
        Filter.NO_TYPE -> "🎉 У всех предметов указан тип" to "Ничего не нужно дополнять"
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

    private fun pluralize(count: Int): String {
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
