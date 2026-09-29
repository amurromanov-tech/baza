package com.family.base.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.R
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
    private lateinit var adapter: ArchiveAdapter
    private val TAG = "ArchiveActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private enum class ArchiveFolder { THINGS, MEDICINE, FOOD }

    private var currentFolder: ArchiveFolder = ArchiveFolder.THINGS
    private var currentReasonKey: String? = null

    private var allArchivedItems: List<ItemEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== ArchiveActivity onCreate START ===")

        binding = ActivityArchiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)

        adapter = ArchiveAdapter(
            onItemClick = { item -> showItemDetails(item) },
            onRestoreClick = { item -> restoreItem(item) }
        )

        binding.rvArchive.layoutManager = LinearLayoutManager(this)
        binding.rvArchive.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        setupFolderChips()
        setupReasonChips()

        loadAllArchive()

        Logger.log(TAG, "=== ArchiveActivity onCreate FINISHED ===")
    }

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
        val result = if (currentReasonKey == null) {
            byFolder
        } else {
            byFolder.filter { it.archivedReason == currentReasonKey }
        }

        adapter.submitList(result)

        val totalSum = result.sumOf { (it.price ?: 0.0) * it.quantity }
        binding.tvArchiveTotal.text = "Итого в архиве: ${formatMoney(totalSum)}"
        binding.tvArchiveCount.text = "Предметов: ${result.size}"
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
