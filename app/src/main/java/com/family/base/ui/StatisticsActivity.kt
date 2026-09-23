package com.family.base.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.data.local.AppDatabase
import com.family.base.databinding.ActivityStatisticsBinding
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class StatisticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatisticsBinding
    private lateinit var db: AppDatabase
    private val TAG = "StatisticsActivity"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private val periods = arrayOf(
        "За всё время",
        "За сегодня",
        "За неделю",
        "За месяц",
        "За год"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== StatisticsActivity onCreate START ===")

        binding = ActivityStatisticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }

        // Спиннер периодов
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, periods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPeriod.adapter = adapter

        binding.spinnerPeriod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadStatistics(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        Logger.log(TAG, "=== StatisticsActivity onCreate FINISHED ===")
    }

    private fun loadStatistics(periodIndex: Int) {
        lifecycleScope.launch {
            try {
                val (startDate, endDate) = getPeriodRange(periodIndex)
                Logger.log(TAG, "Loading statistics for period: ${periods[periodIndex]}")

                // ===== АКТИВНЫЕ ПРЕДМЕТЫ =====
                val items = withContext(Dispatchers.IO) {
                    db.itemDao().getItemsByDateRange(startDate, endDate)
                }

                var foodSum = 0.0
                var medicineSum = 0.0
                var otherSum = 0.0
                var totalActiveSum = 0.0

                items.forEach { item ->
                    val price = item.price ?: 0.0
                    val sum = price * item.quantity

                    when (item.itemType) {
                        "food" -> foodSum += sum
                        "medicine" -> medicineSum += sum
                        else -> otherSum += sum
                    }
                    totalActiveSum += sum
                }

                // ===== АРХИВ =====
                val archivedTotal = withContext(Dispatchers.IO) {
                    db.itemDao().getTotalArchivedSum() ?: 0.0
                }
                val archivedCount = withContext(Dispatchers.IO) {
                    db.itemDao().getArchivedItemsCount()
                }

                // ===== ЗАЙМЫ =====
                val lentTotal = withContext(Dispatchers.IO) {
                    db.itemDao().getTotalLentSum() ?: 0.0
                }
                val lentCount = withContext(Dispatchers.IO) {
                    db.itemDao().getLentItemsCount()
                }

                Logger.log(TAG, "Active=$totalActiveSum, Lent=$lentTotal, Archived=$archivedTotal")

                // ===== ВЫВОД =====
                binding.tvFoodSum.text = formatMoney(foodSum)
                binding.tvMedicineSum.text = formatMoney(medicineSum)
                binding.tvOtherSum.text = formatMoney(otherSum)

                // ИТОГО В БАЗЕ (яркое)
                binding.tvActiveSum.text = formatMoney(totalActiveSum)

                // ВЫДАНО (оранжевое)
                binding.tvLentSum.text = formatMoney(lentTotal)

                // В АРХИВЕ (серое)
                binding.tvArchivedSum.text = formatMoney(archivedTotal)

                // ВСЕГО ПОТРАЧЕНО (текущие + архив)
                binding.tvGrandTotal.text = formatMoney(totalActiveSum + archivedTotal)

                // Количество
                binding.tvItemsCount.text = "Предметов в базе: ${items.size}"
                binding.tvLentCount.text = "Выдано предметов: $lentCount"
                binding.tvArchivedCount.text = "Предметов в архиве: $archivedCount"

            } catch (e: Exception) {
                Logger.log(TAG, "Error loading statistics", e)
            }
        }
    }

    private fun getPeriodRange(periodIndex: Int): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        val endDate = System.currentTimeMillis()

        val startDate = when (periodIndex) {
            0 -> 0L // За всё время
            1 -> { // За сегодня
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
            2 -> { // За неделю
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                calendar.timeInMillis
            }
            3 -> { // За месяц
                calendar.add(Calendar.MONTH, -1)
                calendar.timeInMillis
            }
            4 -> { // За год
                calendar.add(Calendar.YEAR, -1)
                calendar.timeInMillis
            }
            else -> 0L
        }

        return Pair(startDate, endDate)
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
