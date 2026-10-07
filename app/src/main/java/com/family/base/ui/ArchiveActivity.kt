package com.family.base.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.databinding.ActivityArchiveBinding
import com.family.base.ui.adapter.ArchiveAdapter
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ArchiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArchiveBinding
    private lateinit var db: AppDatabase
    private lateinit var tokenStorage: TokenStorage
    private lateinit var adapter: ArchiveAdapter
    private val TAG = "ArchiveActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private enum class ArchiveFolder { THINGS, MEDICINE, FOOD }

    private enum class Sort(val label: String) {
        ARCHIVED_DESC("📅 По дате архивации: новые сначала"),
        ARCHIVED_ASC("📅 По дате архивации: старые сначала"),
        NAME_ASC("🔤 По имени: А→Я"),
        NAME_DESC("🔤 По имени: Я→А"),
        PRICE_DESC("💰 По цене: дорогие сначала"),
        PURCHASE_DESC("🛒 По дате покупки: новые сначала"),
        PURCHASE_ASC("🛒 По дате покупки: старые сначала")
    }

    private var currentFolder: ArchiveFolder = ArchiveFolder.THINGS
    private var currentReasonKey: String? = null
    private var currentSort: Sort = Sort.ARCHIVED_DESC

    private var allArchivedItems: List<ItemEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== ArchiveActivity onCreate START ===")

        binding = ActivityArchiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        tokenStorage = TokenStorage(this)

        adapter = ArchiveAdapter(
            isGuestMode = isGuestMode(),
            onItemClick = { item -> showItemDetails(item) },
            onRestoreClick = { item ->
                if (!isGuestMode()) restoreItem(item)
            }
        )

        binding.rvArchive.layoutManager = LinearLayoutManager(this)
        binding.rvArchive.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSort.setOnClickListener { showSortDialog() }

        setupFolderChips()
        setupReasonChips()

        // 🆕 Extra: стартовая папка архива
        applyStartFolderFromIntent()

        loadAllArchive()

        Logger.log(TAG, "=== ArchiveActivity onCreate FINISHED ===")
    }

    private fun isGuestMode(): Boolean = tokenStorage.isCurrentUserGuest()

    // ==================== СТАРТОВАЯ ПАПКА ИЗ INTENT ====================

    private fun applyStartFolderFromIntent() {
        val startFolder = intent.getStringExtra("start_folder") ?: return
        Logger.log(TAG, "Requested start_folder=$startFolder")

        when (startFolder) {
            "food" -> {
                currentFolder = ArchiveFolder.FOOD
                binding.chipGroupFolder.check(R.id.chipFolderFood)
            }
            "medicine" -> {
                currentFolder = ArchiveFolder.MEDICINE
                binding.chipGroupFolder.check(R.id.chipFolderMedicine)
            }
            "things" -> {
                currentFolder = ArchiveFolder.THINGS
                binding.chipGroupFolder.check(R.id.chipFolderThings)
            }
            else -> Logger.log(TAG, "Unknown start_folder: $startFolder")
        }
    }

    // ==================== ПАПКИ ====================

    private fun setupFolderChips() {
        binding.chipGroupFolder.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipFolderMedicine -> currentFolder = ArchiveFolder.MEDICINE
                R.id.chipFolderFood -> currentFolder = ArchiveFolder.FOOD
                else -> currentFolder = ArchiveFolder.THINGS
            }
            binding.chipGroupReason.check(R.id.chipReasonAll)
            currentReasonKey = null
            applyFilters()
        }
    }

    private fun setupReasonChips() {
        binding.chipGroupReason.setOnCheckedStateChangeListener { _, checkedIds ->
            currentReasonKey = when (checkedIds.firstOrNull()) {
                R.id.chipReasonUsedUp -> "used_up"
                R.id.chipReasonEaten -> "eaten"
                R.id.chipReasonBroken -> "broken"
                R.id.chipReasonThrown -> "thrown"
                R.id.chipReasonGifted -> "gifted"
                R.id.chipReasonSold -> "sold"
                R.id.chipReasonExpired -> "expired"
                R.id.chipReasonOther -> "other"
                else -> null
            }
            applyFilters()
        }
    }

    // ==================== ЗАГРУЗКА ====================

    private fun loadAllArchive() {
        lifecycleScope.launch {
            try {
                allArchivedItems = withContext(Dispatchers.IO) {
                    db.itemDao().getArchivedItems()
                }
                applyFilters()
                Logger.log(TAG, "Loaded ${allArchivedItems.size} archived items total")
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading archive", e)
                Toast.makeText(this@ArchiveActivity, "Ошибка загрузки архива", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFilters() {
        val byFolder = allArchivedItems.filter { matchesFolder(it, currentFolder) }
        val filtered = if (currentReasonKey == null) {
            byFolder
        } else {
            byFolder.filter { it.archivedReason == currentReasonKey }
        }

        val result = applySort(filtered)

        adapter.submitList(result)

        val totalSum = result.sumOf { (it.price ?: 0.0) * it.quantity }
        binding.tvArchiveTotal.text = "Итого в архиве: ${formatMoney(totalSum)}"
        binding.tvArchiveCount.text = "Предметов: ${result.size}"
    }

    // ==================== СОРТИРОВКА ====================

    private fun applySort(items: List<ItemEntity>): List<ItemEntity> {
        return when (currentSort) {
            Sort.ARCHIVED_DESC -> items.sortedByDescending { it.archivedDate ?: 0L }

            Sort.ARCHIVED_ASC -> items.sortedBy { it.archivedDate ?: 0L }

            Sort.NAME_ASC -> items.sortedBy { it.name.lowercase() }

            Sort.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }

            Sort.PRICE_DESC -> items.sortedByDescending { (it.price ?: 0.0) * it.quantity }

            Sort.PURCHASE_DESC -> items.sortedByDescending { it.purchaseDate ?: it.addedDate }

            Sort.PURCHASE_ASC -> items.sortedBy { it.purchaseDate ?: it.addedDate }
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
                applyFilters()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun matchesFolder(item: ItemEntity, folder: ArchiveFolder): Boolean {
        return when (folder) {
            ArchiveFolder.FOOD -> item.itemType == "food"
            ArchiveFolder.MEDICINE -> item.itemType == "medicine"
            ArchiveFolder.THINGS -> {
                val t = item.itemType
                t == null || t.isEmpty() || t == "thing" || t == "other"
            }
        }
    }

    // ==================== ДЕТАЛИ / ВОССТАНОВЛЕНИЕ ====================

    private fun showItemDetails(item: ItemEntity) {
        val reasonText = getReasonText(item.archivedReason)
        val dateText = item.archivedDate?.let { dateFormat.format(Date(it)) } ?: "—"

        val message = buildString {
            append("Причина: $reasonText\n")
            append("Дата: $dateText\n")
            if (!item.archivedNote.isNullOrEmpty()) {
                append("Заметка: ${item.archivedNote}\n")
            }
            append("\nЦена: ${formatMoney((item.price ?: 0.0) * item.quantity)}")
            append("\nКоличество: ${item.quantity}")
            if (!item.description.isNullOrEmpty()) {
                append("\n\nОписание: ${item.description}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun restoreItem(item: ItemEntity) {
        AlertDialog.Builder(this)
            .setTitle("Вернуть из архива?")
            .setMessage("Предмет «${item.name}» вернётся в базу.")
            .setPositiveButton("Вернуть") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            db.itemDao().unarchiveItem(item.id, System.currentTimeMillis())
                        }
                        Toast.makeText(this@ArchiveActivity, "Предмет возвращён в базу", Toast.LENGTH_SHORT).show()
                        loadAllArchive()
                    } catch (e: Exception) {
                        Logger.log(TAG, "Error restoring item", e)
                        Toast.makeText(this@ArchiveActivity, "Ошибка возврата", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ==================== ХЕЛПЕРЫ ====================

    private fun getReasonText(reason: String?): String {
        return when (reason) {
            "used_up" -> "🧴 Израсходовано"
            "eaten" -> "🍽 Съедено"
            "broken" -> "🔧 Сломано"
            "thrown" -> "🗑 Выброшено"
            "gifted" -> "🎁 Подарено"
            "sold" -> "💰 Продано"
            "expired" -> "⏰ Истёк срок"
            "other" -> "📦 Другое"
            else -> "—"
        }
    }

    private fun formatMoney(amount: Double): String {
        return if (amount % 1.0 == 0.0) "${amount.toInt()} ₽" else String.format("%.2f ₽", amount)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
