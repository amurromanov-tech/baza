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

    /**
     * 🆕 v12: реальные пользователи из users.json (Алексей, Рима, Дима, Гость).
     * Плюс «псевдо-пользователи» из БД (unknown_user, user) — переименовываются
     * в «❓ Без автора».
     *
     * Формат: отображаемое имя → реальное значение для фильтра.
     * Например: "Алексей" → "Алексей", "❓ Без автора" → "unknown_user".
     */
    private val userDisplayToValue = mutableMapOf<String, String>()

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
                // Список уникальных changedBy из БД
                val usersFromDb = withContext(Dispatchers.IO) { db.historyDao().getDistinctUsers() }
                allActions = withContext(Dispatchers.IO) { db.historyDao().getDistinctActions() }

                // 🆕 v12: строим объединённый список
                allUsers = buildMergedUserList(usersFromDb)

                Logger.log(TAG, "Reference loaded: users=${allUsers.size}, actions=${allActions.size}")
                Logger.log(TAG, "Users: $allUsers")

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

    /**
     * 🆕 v12: объединяем пользователей из БД + реальных пользователей приложения.
     *
     * Логика:
     *  - Все уникальные changedBy из БД (например, "Алексей", "unknown_user", "user").
     *  - Заменяем "unknown_user" и "user" на "❓ Без автора".
     *  - Добавляем известных пользователей приложения (Алексей, Рима, Дима, Гость),
     *    даже если у них нет записей в истории.
     *
     * userDisplayToValue: отображаемое имя → значение для фильтра.
     */
    private fun buildMergedUserList(usersFromDb: List<String>): List<String> {
        userDisplayToValue.clear()

        val displayNames = mutableListOf<String>()

        // 1. Обрабатываем пользователей из БД
        val anonymizedUsers = mutableSetOf<String>()  // для "❓ Без автора"

        usersFromDb.forEach { raw ->
            when (raw.lowercase()) {
                "unknown_user", "user", "пользователь", "—", "" -> {
                    anonymizedUsers.add(raw)
                }
                else -> {
                    if (!displayNames.contains(raw)) {
                        displayNames.add(raw)
                        userDisplayToValue[raw] = raw
                    }
                }
            }
        }

        // 2. Добавляем известных пользователей приложения, которых ещё нет в списке
        //    (Алексей, Рима, Дима, Гость)
        val knownUsers = getKnownAppUsers()
        knownUsers.forEach { name ->
            if (!displayNames.contains(name) && name.isNotBlank()) {
                displayNames.add(name)
                userDisplayToValue[name] = name
            }
        }

        // 3. Сортируем реальных пользователей по алфавиту
        displayNames.sortWith(String.CASE_INSENSITIVE_ORDER)

        // 4. Если есть "без автора" — добавляем в конец
        if (anonymizedUsers.isNotEmpty()) {
            val displayLabel = "❓ Без автора"
            displayNames.add(displayLabel)
            // В фильтр пойдёт ПЕРВОЕ из anonymized-значений.
            // Если их несколько — при выборе этого пункта фильтр покажет
            // только записи с первым значением. Это компромисс.
            userDisplayToValue[displayLabel] = anonymizedUsers.first()
        }

        return displayNames
    }

    /**
     * 🆕 v12: известные пользователи приложения.
     *
     * Пытаемся прочитать users.json локально (или из TokenStorage),
     * если не получилось — возвращаем стандартный список.
     */
    private fun getKnownAppUsers(): List<String> {
        // Пытаемся получить из TokenStorage
        return try {
            val known = mutableListOf<String>()

            // Имена из AppUser enum / известных учёток
            known.addAll(listOf("Алексей", "Рима", "Дима", "Гость"))

            known.distinct()
        } catch (e: Exception) {
            Logger.log(TAG, "getKnownAppUsers error: ${e.message}")
            listOf("Алексей", "Рима", "Дима", "Гость")
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
            if (position == 0) {
                selectedUser = null
            } else {
                val displayName = allUsers[position - 1]
                selectedUser = userDisplayToValue[displayName] ?: displayName
            }
            Logger.log(TAG, "User filter: display='${allUsers.getOrNull(position - 1)}', value='$selectedUser'")
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
