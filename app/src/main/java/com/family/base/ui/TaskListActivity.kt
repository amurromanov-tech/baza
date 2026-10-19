package com.family.base.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.TaskEntity
import com.family.base.data.repository.TaskRepository
import com.family.base.ui.adapter.TaskAdapter
import com.family.base.ui.adapter.TaskSection
import com.family.base.ui.adapter.TaskSectionGrouper
import com.family.base.ui.adapter.TaskSectionHeaderAdapter
import com.family.base.util.Logger
import com.family.base.util.TaskReminderPreferences
import com.family.base.util.TaskReminderScheduler
import com.family.base.util.TaskSortPreferences
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Экран «Домашние дела».
 *
 * 🆕 v20.0.0 (шаг 3): переработка окна.
 *  - Список группируется по времени: Просрочено / Сегодня / Завтра /
 *    На этой неделе / Потом / Без напоминаний. Пустые секции не показываются.
 *  - Сборка через ConcatAdapter: TaskSectionHeaderAdapter (заголовки)
 *    + TaskAdapter (карточки). TaskAdapter и item_task.xml не менялись.
 *  - Фильтры:
 *      · ACTIVE — группировка по времени (без выполненных);
 *      · DONE   — плоский список выполненных (все в одной секции «✅ Выполненные»);
 *      · ALL    — группировка активных + отдельная секция «✅ Выполненные» в конце.
 *  - Сортировка внутри секций — по-прежнему TaskSortPreferences.
 *
 * Остальное — как раньше:
 *  - создание/редактирование через диалог (см. showEditDialog);
 *  - напоминания AlarmManager: schedule/cancel;
 *  - удаление — tombstone (softDelete с deletedBy).
 */
class TaskListActivity : AppCompatActivity() {

    private val TAG = "TaskListActivity"
    private val DATE_FMT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val DATETIME_FMT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    private lateinit var db: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var tokenStorage: TokenStorage

    // 🆕 v20.0.0 (шаг 3): два адаптера + ConcatAdapter
    private lateinit var taskAdapter: TaskAdapter
    private lateinit var headerAdapter: TaskSectionHeaderAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private lateinit var rvTasks: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var textCounter: TextView
    private lateinit var fabAddTask: FloatingActionButton
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var chipActive: Chip
    private lateinit var chipDone: Chip
    private lateinit var chipAll: Chip

    private enum class Filter { ACTIVE, DONE, ALL }
    private var currentFilter: Filter = Filter.ACTIVE

    private val currentUser: String
        get() = tokenStorage.getCurrentUser()
            ?: tokenStorage.getUserDisplayName()
            ?: "Пользователь"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_list)

        db = AppDatabase.getInstance(this)
        repository = TaskRepository(applicationContext, db)
        tokenStorage = TokenStorage(this)

        rvTasks = findViewById(R.id.rvTasks)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        textCounter = findViewById(R.id.textCounter)
        fabAddTask = findViewById(R.id.fabAddTask)
        chipGroupFilter = findViewById(R.id.chipGroupFilter)
        chipActive = findViewById(R.id.chipActive)
        chipDone = findViewById(R.id.chipDone)
        chipAll = findViewById(R.id.chipAll)

        // Toolbar: навигация «назад» + меню сортировки
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.setNavigationOnClickListener { finish() }
        toolbar?.inflateMenu(R.menu.menu_task_list)
        toolbar?.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> {
                    showSortDialog()
                    true
                }
                else -> false
            }
        }

        // 🆕 v20.0.0 (шаг 3): карточки задач
        taskAdapter = TaskAdapter(
            onToggleDone = { task -> toggleDone(task) },
            onEdit = { task -> showEditDialog(task) },
            onDelete = { task -> confirmDelete(task) }
        )

        // 🆕 v20.0.0 (шаг 3): заголовки секций
        headerAdapter = TaskSectionHeaderAdapter()

        // 🆕 v20.0.0 (шаг 3): ConcatAdapter — заголовки + задачи.
        // Порядок в ConcatAdapter: [header1][tasks1][header2][tasks2]…
        // Пересобирается в submitSections() при каждой загрузке.
        concatAdapter = ConcatAdapter()

        rvTasks.layoutManager = LinearLayoutManager(this)
        rvTasks.adapter = concatAdapter

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipActive) -> Filter.ACTIVE
                checkedIds.contains(R.id.chipDone) -> Filter.DONE
                else -> Filter.ALL
            }
            loadTasks()
        }

        fabAddTask.setOnClickListener { showEditDialog(null) }

        loadTasks()
    }

    override fun onResume() {
        super.onResume()
        loadTasks()
    }

    // ============================================================
    // СОРТИРОВКА
    // ============================================================

    private fun showSortDialog() {
        val current = TaskSortPreferences.getMode(this)

        val labels = arrayOf(
            "📅 Дата ↓ (сначала новые)",
            "📅 Дата ↑ (сначала старые)",
            "🔤 Имя ↑ (А → Я)",
            "🔤 Имя ↓ (Я → А)",
            "↩️ По умолчанию"
        )

        val checkedIndex = when (current) {
            TaskSortPreferences.Mode.DEFAULT   -> 4
            TaskSortPreferences.Mode.DATE_DESC -> 0
            TaskSortPreferences.Mode.DATE_ASC  -> 1
            TaskSortPreferences.Mode.NAME_ASC  -> 2
            TaskSortPreferences.Mode.NAME_DESC -> 3
        }

        AlertDialog.Builder(this)
            .setTitle("Сортировка")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val mode = when (which) {
                    0 -> TaskSortPreferences.Mode.DATE_DESC
                    1 -> TaskSortPreferences.Mode.DATE_ASC
                    2 -> TaskSortPreferences.Mode.NAME_ASC
                    3 -> TaskSortPreferences.Mode.NAME_DESC
                    else -> TaskSortPreferences.Mode.DEFAULT
                }
                TaskSortPreferences.setMode(this, mode)
                dialog.dismiss()
                loadTasks()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applySort(tasks: List<TaskEntity>): List<TaskEntity> {
        return when (TaskSortPreferences.getMode(this)) {
            TaskSortPreferences.Mode.DEFAULT ->
                tasks

            TaskSortPreferences.Mode.DATE_DESC ->
                tasks.sortedByDescending { it.createdAt }

            TaskSortPreferences.Mode.DATE_ASC ->
                tasks.sortedBy { it.createdAt }

            TaskSortPreferences.Mode.NAME_ASC ->
                tasks.sortedBy { it.title.lowercase(Locale.getDefault()) }

            TaskSortPreferences.Mode.NAME_DESC ->
                tasks.sortedByDescending { it.title.lowercase(Locale.getDefault()) }
        }
    }

    // ============================================================
    // ЗАГРУЗКА
    // ============================================================
    private fun loadTasks() {
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                when (currentFilter) {
                    Filter.ACTIVE -> repository.getActiveSorted()
                    Filter.DONE -> repository.getDoneSorted()
                    Filter.ALL -> repository.getAllSorted()
                }
            }
            val tasks = applySort(raw)

            // 🆕 v20.0.0 (шаг 3): группировка по времени.
            val sections: List<TaskSection> = when (currentFilter) {
                Filter.ACTIVE -> TaskSectionGrouper.groupActive(tasks)
                Filter.DONE -> TaskSectionGrouper.groupDone(tasks)
                Filter.ALL -> {
                    val active = tasks.filter { !it.isDone }
                    val done = tasks.filter { it.isDone }
                    TaskSectionGrouper.groupActive(active) +
                        TaskSectionGrouper.groupDone(done)
                }
            }

            submitSections(sections)

            val isEmpty = sections.all { it.isEmpty }
            if (isEmpty) {
                layoutEmpty.visibility = View.VISIBLE
                rvTasks.visibility = View.GONE
            } else {
                layoutEmpty.visibility = View.GONE
                rvTasks.visibility = View.VISIBLE
            }

            val activeCount = withContext(Dispatchers.IO) { repository.getActiveCount() }
            textCounter.text = when (currentFilter) {
                Filter.ACTIVE -> "Активных: $activeCount"
                Filter.DONE -> "Выполненных: ${tasks.size}"
                Filter.ALL -> "Всего: ${tasks.size} · Активных: $activeCount"
            }
        }
    }

    /**
     * 🆕 v20.0.0 (шаг 3): собрать ConcatAdapter из секций.
     *
     * Для каждой секции добавляем два «под-адаптера»:
     *  1) headerAdapter с одним заголовком (нужен локальный экземпляр,
     *     потому что ConcatAdapter хранит список адаптеров, а не данных);
     *  2) TaskAdapter с карточками этой секции.
     *
     * ⚠️ ConcatAdapter нельзя «переиспользовать» с разными наборами
     * данных — проще пересоздать набор и передать его в concatAdapter.
     * Это дёшево: список задач и так перечитывается целиком.
     */
    private fun submitSections(sections: List<TaskSection>) {
        val newAdapters = mutableListOf<RecyclerView.Adapter<*>>()

        // Заголовки и карточки идут парами. Для заголовка используем
        // локальный экземпляр TaskSectionHeaderAdapter, чтобы у каждой
        // секции был свой набор данных (иначе все заголовки показывали бы
        // последний набор).
        for (section in sections) {
            val header = TaskSectionHeaderAdapter()
            header.submit(
                listOf(
                    TaskSectionHeaderAdapter.Header(
                        title = section.title,
                        count = section.tasks.size,
                        stableId = section.type.name
                    )
                )
            )
            newAdapters.add(header)

            // Каждая секция — свой TaskAdapter с колбэками.
            // Так проще: не нужно пересобирать один адаптер с «дырками».
            val cards = TaskAdapter(
                onToggleDone = { task -> toggleDone(task) },
                onEdit = { task -> showEditDialog(task) },
                onDelete = { task -> confirmDelete(task) }
            )
            cards.submitList(section.tasks)
            newAdapters.add(cards)
        }

        // Полная пересборка ConcatAdapter
        concatAdapter.removeAdapter(taskAdapter)
        concatAdapter.removeAdapter(headerAdapter)

        // Проще: создать новый ConcatAdapter и передать в RecyclerView.
        // (RecyclerView пересоздаст ViewHolder'ы, но список маленький.)
        val fresh = ConcatAdapter(newAdapters)
        rvTasks.adapter = fresh
    }

    // ============================================================
    // ДЕЙСТВИЯ
    // ============================================================

    private fun toggleDone(task: TaskEntity) {
        lifecycleScope.launch {
            try {
                if (task.isDone) {
                    withContext(Dispatchers.IO) { repository.markUndone(task.id) }
                    Logger.log(TAG, "toggleDone: undone ${task.id}")
                    val refreshed = withContext(Dispatchers.IO) { repository.getById(task.id) }
                    if (refreshed != null) {
                        TaskReminderScheduler.schedule(this@TaskListActivity, refreshed)
                    }
                } else {
                    val result = withContext(Dispatchers.IO) {
                        repository.markDone(task.id, currentUser)
                    }
                    Logger.log(TAG, "toggleDone: done ${task.id}, clone=${result?.second?.id}")

                    TaskReminderScheduler.cancel(this@TaskListActivity, task.id)

                    result?.second?.let { clone ->
                        TaskReminderScheduler.schedule(this@TaskListActivity, clone)
                        Toast.makeText(
                            this@TaskListActivity,
                            "Создана следующая: ${clone.title}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                loadTasks()
            } catch (e: Exception) {
                Logger.log(TAG, "toggleDone error: ${e.message}")
                Toast.makeText(this@TaskListActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(task: TaskEntity) {
        AlertDialog.Builder(this)
            .setTitle("Удалить задачу?")
            .setMessage("«${task.title}»")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        repository.softDelete(task.id, currentUser)
                    }
                    TaskReminderScheduler.cancel(this@TaskListActivity, task.id)
                    Toast.makeText(this@TaskListActivity, "Удалено", Toast.LENGTH_SHORT).show()
                    loadTasks()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // ДИАЛОГ СОЗДАНИЯ / РЕДАКТИРОВАНИЯ
    // ============================================================

    private fun showEditDialog(task: TaskEntity?) {
        val view = layoutInflater.inflate(R.layout.dialog_task_edit, null)

        val etTitle = view.findViewById<TextInputEditText>(R.id.etTitle)
        val etNote = view.findViewById<TextInputEditText>(R.id.etNote)
        val chipGroupRecurrence = view.findViewById<ChipGroup>(R.id.chipGroupRecurrence)
        val chipUrgent = view.findViewById<Chip>(R.id.chipUrgent)
        val chipHigh = view.findViewById<Chip>(R.id.chipHigh)
        val chipNormal = view.findViewById<Chip>(R.id.chipNormal)
        val chipLow = view.findViewById<Chip>(R.id.chipLow)
        val chipRecNone = view.findViewById<Chip>(R.id.chipRecNone)
        val chipRecDaily = view.findViewById<Chip>(R.id.chipRecDaily)
        val chipRecWeekly = view.findViewById<Chip>(R.id.chipRecWeekly)
        val chipRecMonthly = view.findViewById<Chip>(R.id.chipRecMonthly)
        val textDueDate = view.findViewById<TextView>(R.id.textDueDate)
        val btnPickDate = view.findViewById<View>(R.id.btnPickDate)
        val btnClearDate = view.findViewById<View>(R.id.btnClearDate)
        val textRecurrenceHint = view.findViewById<TextView>(R.id.textRecurrenceHint)

        val switchReminder = view.findViewById<SwitchCompat>(R.id.switchReminder)
        val layoutReminderTime = view.findViewById<View>(R.id.layoutReminderTime)
        val textReminderTime = view.findViewById<TextView>(R.id.textReminderTime)
        val btnPickReminderTime = view.findViewById<View>(R.id.btnPickReminderTime)
        val textReminderHint = view.findViewById<TextView>(R.id.textReminderHint)

        val textRemindAt = view.findViewById<TextView>(R.id.textRemindAt)
        val btnPickRemindAt = view.findViewById<View>(R.id.btnPickRemindAt)
        val btnClearRemindAt = view.findViewById<View>(R.id.btnClearRemindAt)
        val switchAutoRemind = view.findViewById<SwitchCompat>(R.id.switchAutoRemind)
        val textAutoRemindHint = view.findViewById<TextView>(R.id.textAutoRemindHint)

        var pendingDueDate: Long? = task?.dueDate
        var pendingRemindAt: Long? = task?.remindAt ?: defaultRemindAt()
        var pendingAutoRemind: Boolean = task?.autoRemindEnabled ?: true

        fun updateDueDateLabel() {
            if (pendingDueDate == null) {
                textDueDate.text = "Не указан"
            } else {
                textDueDate.text = DATE_FMT.format(Date(pendingDueDate!!))
            }
        }

        fun updateRemindAtLabel() {
            if (pendingRemindAt == null) {
                textRemindAt.text = "Не задано (только умный пинг)"
            } else {
                textRemindAt.text = DATETIME_FMT.format(Date(pendingRemindAt!!))
            }
        }

        if (task != null) {
            etTitle.setText(task.title)
            etNote.setText(task.note ?: "")
            when (task.priority) {
                TaskEntity.PRIORITY_URGENT -> chipUrgent.isChecked = true
                TaskEntity.PRIORITY_HIGH -> chipHigh.isChecked = true
                TaskEntity.PRIORITY_NORMAL -> chipNormal.isChecked = true
                TaskEntity.PRIORITY_LOW -> chipLow.isChecked = true
            }
            when (task.recurrence) {
                TaskEntity.RECURRENCE_NONE -> chipRecNone.isChecked = true
                TaskEntity.RECURRENCE_DAILY -> chipRecDaily.isChecked = true
                TaskEntity.RECURRENCE_WEEKLY -> chipRecWeekly.isChecked = true
                TaskEntity.RECURRENCE_MONTHLY -> chipRecMonthly.isChecked = true
            }
        } else {
            chipNormal.isChecked = true
            chipRecNone.isChecked = true
        }

        switchAutoRemind.isChecked = pendingAutoRemind
        textAutoRemindHint.visibility = if (pendingAutoRemind) View.VISIBLE else View.GONE

        updateDueDateLabel()
        updateRemindAtLabel()

        chipGroupRecurrence.setOnCheckedStateChangeListener { _, checkedIds ->
            val hasRecurrence = !checkedIds.contains(R.id.chipRecNone)
            textRecurrenceHint.visibility = if (hasRecurrence) View.VISIBLE else View.GONE
        }

        btnPickDate.setOnClickListener {
            val cal = Calendar.getInstance()
            pendingDueDate?.let { cal.timeInMillis = it }
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    val c = Calendar.getInstance()
                    c.set(year, month, day, 0, 0, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    pendingDueDate = c.timeInMillis
                    updateDueDateLabel()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        btnClearDate.setOnClickListener {
            pendingDueDate = null
            updateDueDateLabel()
        }

        fun updateReminderTimeLabel() {
            textReminderTime.text = TaskReminderPreferences.getTimeLabel(this)
        }

        fun updateReminderVisibility() {
            val visible = switchReminder.isChecked
            layoutReminderTime.visibility = if (visible) View.VISIBLE else View.GONE
            textReminderHint.visibility = if (visible) View.VISIBLE else View.GONE
        }

        switchReminder.isChecked = TaskReminderPreferences.isEnabled(this)
        updateReminderTimeLabel()
        updateReminderVisibility()

        switchReminder.setOnCheckedChangeListener { _, _ ->
            updateReminderVisibility()
        }

        btnPickReminderTime.setOnClickListener {
            val h = TaskReminderPreferences.getHour(this)
            val m = TaskReminderPreferences.getMinute(this)
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    TaskReminderPreferences.setTime(this, hour, minute)
                    updateReminderTimeLabel()
                },
                h, m, true
            ).show()
        }

        btnPickRemindAt.setOnClickListener {
            val cal = Calendar.getInstance()
            pendingRemindAt?.let { cal.timeInMillis = it }

            DatePickerDialog(
                this,
                { _, year, month, day ->
                    val c = Calendar.getInstance()
                    c.set(year, month, day, 0, 0, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    val preH = cal.get(Calendar.HOUR_OF_DAY)
                    val preM = cal.get(Calendar.MINUTE)
                    TimePickerDialog(
                        this,
                        { _, hour, minute ->
                            val c2 = c.clone() as Calendar
                            c2.set(Calendar.HOUR_OF_DAY, hour)
                            c2.set(Calendar.MINUTE, minute)
                            c2.set(Calendar.SECOND, 0)
                            c2.set(Calendar.MILLISECOND, 0)
                            pendingRemindAt = c2.timeInMillis
                            updateRemindAtLabel()
                        },
                        preH, preM, true
                    ).show()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        btnClearRemindAt.setOnClickListener {
            pendingRemindAt = null
            updateRemindAtLabel()
        }

        switchAutoRemind.setOnCheckedChangeListener { _, isChecked ->
            pendingAutoRemind = isChecked
            textAutoRemindHint.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (task == null) "Новая задача" else "Редактировать задачу")
            .setView(view)
            .setPositiveButton(if (task == null) "Добавить" else "Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = etTitle.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) {
                    etTitle.error = "Введите название"
                    return@setOnClickListener
                }

                val note = etNote.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

                val priority = when {
                    chipUrgent.isChecked -> TaskEntity.PRIORITY_URGENT
                    chipHigh.isChecked -> TaskEntity.PRIORITY_HIGH
                    chipNormal.isChecked -> TaskEntity.PRIORITY_NORMAL
                    chipLow.isChecked -> TaskEntity.PRIORITY_LOW
                    else -> TaskEntity.PRIORITY_NORMAL
                }

                val recurrence = when {
                    chipRecDaily.isChecked -> TaskEntity.RECURRENCE_DAILY
                    chipRecWeekly.isChecked -> TaskEntity.RECURRENCE_WEEKLY
                    chipRecMonthly.isChecked -> TaskEntity.RECURRENCE_MONTHLY
                    else -> TaskEntity.RECURRENCE_NONE
                }

                lifecycleScope.launch {
                    try {
                        applyGlobalReminderSetting(switchReminder.isChecked)

                        val savedTask: TaskEntity? = withContext(Dispatchers.IO) {
                            if (task == null) {
                                repository.addTask(
                                    title = title,
                                    note = note,
                                    priority = priority,
                                    dueDate = pendingDueDate,
                                    recurrence = recurrence,
                                    createdBy = currentUser,
                                    remindAt = pendingRemindAt,
                                    autoRemindEnabled = pendingAutoRemind
                                )
                            } else {
                                val updated = task.copy(
                                    title = title,
                                    note = note,
                                    priority = priority,
                                    dueDate = pendingDueDate,
                                    recurrence = recurrence,
                                    remindAt = pendingRemindAt,
                                    autoRemindEnabled = pendingAutoRemind,
                                    updatedAt = System.currentTimeMillis()
                                )
                                repository.updateTask(updated)
                                updated
                            }
                        }

                        if (savedTask != null) {
                            TaskReminderScheduler.schedule(this@TaskListActivity, savedTask)
                        }

                        dialog.dismiss()
                        loadTasks()
                    } catch (e: Exception) {
                        Logger.log(TAG, "saveTask error: ${e.message}")
                        Toast.makeText(this@TaskListActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun defaultRemindAt(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 19)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun applyGlobalReminderSetting(enabled: Boolean) {
        try {
            val wasEnabled = TaskReminderPreferences.isEnabled(this)
            TaskReminderPreferences.setEnabled(this, enabled)

            if (enabled) {
                if (!wasEnabled) {
                    TaskReminderScheduler.rescheduleAll(this)
                    Toast.makeText(
                        this,
                        "Напоминания включены (${TaskReminderPreferences.getTimeLabel(this)})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                if (wasEnabled) {
                    TaskReminderScheduler.cancelAll(this)
                    Toast.makeText(this, "Напоминания выключены", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "applyGlobalReminderSetting error: ${e.message}")
        }
    }
}
