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
import com.family.base.databinding.ActivityLentItemsBinding
import com.family.base.ui.adapter.LentItemsAdapter
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class LentItemsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLentItemsBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: LentItemsAdapter
    private val TAG = "LentItemsActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private var persons: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== LentItemsActivity onCreate START ===")

        binding = ActivityLentItemsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)

        adapter = LentItemsAdapter(
            onItemClick = { item -> showItemDetails(item) },
            onReturnClick = { item -> returnItem(item) }
        )

        binding.rvLent.layoutManager = LinearLayoutManager(this)
        binding.rvLent.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        loadPersons()

        Logger.log(TAG, "=== LentItemsActivity onCreate FINISHED ===")
    }

    private fun loadPersons() {
        lifecycleScope.launch {
            try {
                persons = withContext(Dispatchers.IO) {
                    db.itemDao().getLentPersons()
                }

                val filters = mutableListOf("Все")
                filters.addAll(persons)

                val spinnerAdapter = ArrayAdapter(
                    this@LentItemsActivity,
                    android.R.layout.simple_spinner_item,
                    filters
                )
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerPerson.adapter = spinnerAdapter

                binding.spinnerPerson.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position == 0) loadLentItems(null)
                        else loadLentItems(persons[position - 1])
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading persons", e)
                Toast.makeText(this@LentItemsActivity, "Ошибка загрузки списка людей", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadLentItems(personName: String?) {
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    if (personName == null) {
                        db.itemDao().getLentItems()
                    } else {
                        db.itemDao().getLentItemsByPerson(personName)
                    }
                }

                adapter.submitList(items)

                val totalSum = items.sumOf { (it.price ?: 0.0) * it.quantity }
                binding.tvLentTotal.text = "Итого выдано: ${formatMoney(totalSum)}"
                binding.tvLentCount.text = "Предметов: ${items.size}"

                Logger.log(TAG, "Loaded ${items.size} lent items (person=$personName)")
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading lent items", e)
                Toast.makeText(this@LentItemsActivity, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showItemDetails(item: ItemEntity) {
        val dateText = item.lentDate?.let { dateFormat.format(Date(it)) } ?: "—"

        val message = buildString {
            append("Кому: ${item.lentTo ?: "—"}\n")
            append("Дата: $dateText\n")
            if (!item.lentNote.isNullOrEmpty()) {
                append("Заметка: ${item.lentNote}\n")
            }
            append("\nКоличество: ${item.quantity}")
            if (item.price != null && item.price != 0.0) {
                append("\nСумма: ${formatMoney(item.price * item.quantity)}")
            }
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

    private fun returnItem(item: ItemEntity) {
        AlertDialog.Builder(this)
            .setTitle("↩️ Вернуть предмет?")
            .setMessage("«${item.name}» вернётся в базу, займ будет отменён.")
            .setPositiveButton("Вернуть") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val updated = item.copy(
                            isLent = false,
                            lentTo = null,
                            lentDate = null,
                            lentNote = null,
                            returnDate = null,
                            updatedDate = System.currentTimeMillis()
                        )
                        withContext(Dispatchers.IO) {
                            db.itemDao().updateItem(updated)
                        }
                        Toast.makeText(this@LentItemsActivity, "Возвращён", Toast.LENGTH_SHORT).show()
                        // Перезагружаем список
                        loadPersons()
                    } catch (e: Exception) {
                        Logger.log(TAG, "Error returning item", e)
                        Toast.makeText(this@LentItemsActivity, "Ошибка возврата", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun formatMoney(amount: Double): String {
        return if (amount % 1.0 == 0.0) {
            "${amount.toInt()} ₽"
        } else {
            String.format("%.2f ₽", amount)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
