package com.family.base.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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

    private val filters = arrayOf(
        "Все",
        "🍽 Съедено",
        "🔧 Сломано",
        "🗑 Выброшено",
        "🎁 Подарено",
        "💰 Продано",
        "⏰ Истёк срок",
        "📦 Другое"
    )
    private val filterKeys = arrayOf(
        null,
        "eaten", "broken", "thrown", "gifted", "sold", "expired", "other"
    )

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

        // Спиннер фильтра
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filters)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFilter.adapter = spinnerAdapter

        binding.spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadArchive(filterKeys[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        Logger.log(TAG, "=== ArchiveActivity onCreate FINISHED ===")
    }

    private fun loadArchive(filterKey: String?) {
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    if (filterKey == null) {
                        db.itemDao().getArchivedItems()
                    } else {
                        db.itemDao().getArchivedItemsByReason(filterKey)
                    }
                }

                adapter.submitList(items)

                val totalSum = items.sumOf { (it.price ?: 0.0) * it.quantity }
                binding.tvArchiveTotal.text = "Итого в архиве: ${formatMoney(totalSum)}"
                binding.tvArchiveCount.text = "Предметов: ${items.size}"

                Logger.log(TAG, "Loaded ${items.size} archived items")
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading archive", e)
                Toast.makeText(this@ArchiveActivity, "Ошибка загрузки архива", Toast.LENGTH_SHORT).show()
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
                        loadArchive(null)
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
