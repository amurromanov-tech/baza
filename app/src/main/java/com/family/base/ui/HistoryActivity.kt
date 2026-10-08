package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.databinding.ActivityHistoryBinding
import com.family.base.ui.adapter.HistoryAdapter
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var db: AppDatabase

    private val TAG = "HistoryActivity"

    // Текущее состояние фильтров
    private var selectedUser: String? = null     // null = Все
    private var selectedAction: String? = null   // null = Все
    private var periodStart: Long = 0L           // 0 = Всё время

    // Справочники (заполняются один раз)
    private var allUsers: List<String> = emptyList()
    private var allActions: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== HistoryActivity onCreate START ===")

        try {
            binding = ActivityHistoryBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        db = AppDatabase.getInstance(this)

        adapter = HistoryAdapter { entry -> onEntryClick(entry) }

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        setupPeriodChips()

        binding.progressBar.visibility = View.VISIBLE

        loadReferenceData()

        Logger.log(TAG, "=== HistoryActivity onCreate FINISHED ===")
    }

    override fun onResume() {
        super.onResume()
        // Перезагружаем — на случай, если что-то изменилось в другом экране
        if (::adapter.isInitialized) {
            loadHistory()
        }
    }

    // ============================================================
    // СПРАВОЧНИКИ: пользователи и действия
    // ============================================================

    private fun loadReferenceData() {
        lifecycleScope.launch {
            try {
                allUsers = withContext(Dispatchers.IO) { db.historyDao().getDistinctUsers() }
                allActions = withContext(Dispatchers.IO) { db.historyDao().getDistinctActions() }

                Logger.log(TAG, "Reference loaded: users=${allUsers.size}, actions=${allActions.size}")

                withContext(Dispatchers.Main) {
                    populateUserDropdown()
                    populateActionDropdown()
                    loadHistory()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading reference data", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@HistoryActivity, "Ошибка загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun populateUserDropdown() {
        val items = mutableListOf<String>()
        items.add("Все пользователи")
        items.addAll(allUsers)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            items
        )
        binding.actvUserFilter.setAdapter(adapter)
        binding.actvUserFilter.setText("Все пользователи", false)
        binding.actvUserFilter.setOnItemClickListener { _, _, position, _ ->
            selectedUser = if (position == 0) null else allUsers[position - 1]
            Logger.log(TAG, "User filter: $selectedUser")
            loadHistory()
        }
    }

    private fun populateActionDropdown() {
        val items = mutableListOf<String>()
        items.add("Все действия")
        items.addAll(allActions.map { actionLabel(it) })

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            items
        )
        binding.actvActionFilter.setAdapter(adapter)
        binding.actvActionFilter.setText("Все действия", false)
        binding.actvActionFilter.setOnItemClickListener { _, _, position, _ ->
            selectedAction = if (position == 0) null else allActions[position - 1]
            Logger.log(TAG, "Action filter: $selectedAction")
            loadHistory()
        }
    }

    /**
     * Человекочитаемая подпись для action-кода.
     * Должна совпадать с порядком в allActions (массив из distinct-запроса).
     */
    private fun actionLabel(action: String): String = when (action) {
        "create" -> "➕ Создание"
        "update" -> "✏️ Изменение"
        "quantity_change" -> "📦 Изменение количества"
        "delete" -> "🗑 Удаление"
        "archive" -> "📦 Архивация"
        "unarchive" -> "↩️ Из архива"
        "unarchive_part" -> "↩️ Возврат части"
        "write_off" -> "🧴 Списание"
        "write_off_part" -> "🧴 Частичное списание"
        "revision" -> "🔍 Ревизия"
        "lend" -> "🤝 Выдача"
        "return" -> "✅ Возврат"
        "move" -> "📁 Перемещение"
        "move_folder" -> "📁 Перемещение папки"
        "split_in" -> "✂️ Отделение (новая часть)"
        "split_out" -> "✂️ Отделение (оригинал)"
        "create_folder" -> "📁 Создание папки"
        "rename_folder" -> "📝 Переименование папки"
        "delete_folder" -> "🗑 Удаление папки"
        "add_nested_folder" -> "📁 Вложенная папка"
        "detach_children" -> "🔗 Отвязка детей"
        else -> action
    }

    // ============================================================
    // ПЕРИОД (chips)
    // ============================================================

    private fun setupPeriodChips() {
        binding.chipAll.isChecked = true
        periodStart = 0L

        binding.chipGroupPeriod.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            periodStart = when (id) {
                binding.chipToday.id -> startOfToday()
                binding.chipWeek.id -> startOfDaysAgo(7)
                binding.chipMonth.id -> startOfDaysAgo(30)
                else -> 0L
            }
            Logger.log(TAG, "Period changed: start=$periodStart")
            loadHistory()
        }
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startOfDaysAgo(days: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ============================================================
    // ЗАГРУЗКА ИСТОРИИ
    // ============================================================

    private fun loadHistory() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val entries: List<HistoryEntry> = withContext(Dispatchers.IO) {
                    queryHistory()
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE

                    adapter.submitList(entries)
                    binding.tvCount.text = entries.size.toString()

                    if (entries.isEmpty()) {
                        binding.emptyView.visibility = View.VISIBLE
                        binding.rvHistory.visibility = View.GONE

                        // Подсказка зависит от фильтров
                        binding.tvEmptyHint.text = if (hasActiveFilters()) {
                            "Ничего не найдено по выбранным фильтрам"
                        } else {
                            "Здесь появятся все изменения: кто, что и когда"
                        }
                    } else {
                        binding.emptyView.visibility = View.GONE
                        binding.rvHistory.visibility = View.VISIBLE
                    }

                    Logger.log(TAG, "History loaded: ${entries.size} entries")
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading history", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@HistoryActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hasActiveFilters(): Boolean =
        selectedUser != null || selectedAction != null || periodStart > 0L

    /**
     * Выбор правильного DAO-запроса в зависимости от комбинации фильтров.
     */
    private suspend fun queryHistory(): List<HistoryEntry> {
        val user = selectedUser
        val action = selectedAction
        val since = periodStart

        return when {
            user != null && action != null && since > 0L ->
                db.historyDao().getEntriesByUserAndActionSince(user, action, since)

            user != null && action != null ->
                db.historyDao().getEntriesByUserAndAction(user, action)

            user != null && since > 0L ->
                db.historyDao().getEntriesByUserSince(user, since)

            action != null && since > 0L ->
                db.historyDao().getEntriesByActionSince(action, since)

            user != null ->
                db.historyDao().getEntriesByUser(user)

            action != null ->
                db.historyDao().getEntriesByAction(action)

            since > 0L ->
                db.historyDao().getEntriesSince(since)

            else ->
                db.historyDao().getAllEntries()
        }
    }

    // ============================================================
    // КЛИК ПО ЗАПИСИ
    // ============================================================

    private fun onEntryClick(entry: HistoryEntry) {
        Logger.log(TAG, "Entry clicked: itemId=${entry.itemId}, action=${entry.action}")

        // Проверяем, существует ли ещё предмет (или папка)
        lifecycleScope.launch {
            try {
                val itemExists = withContext(Dispatchers.IO) {
                    db.itemDao().getItemById(entry.itemId) != null
                }

                if (itemExists) {
                    val intent = Intent(this@HistoryActivity, ItemDetailActivity::class.java).apply {
                        putExtra("item_id", entry.itemId)
                    }
                    startActivity(intent)
                } else {
                    // Проверяем — может это папка?
                    val folderExists = withContext(Dispatchers.IO) {
                        db.folderDao().getFolderById(entry.itemId) != null
                    }

                    if (folderExists) {
                        Toast.makeText(
                            this@HistoryActivity,
                            "Это папка. Открытие папок из истории пока не поддерживается",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@HistoryActivity,
                            "Предмет удалён",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error on entry click", e)
                Toast.makeText(this@HistoryActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
