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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.TaskEntity
import com.family.base.data.repository.TaskRepository
import com.family.base.ui.adapter.TaskAdapter
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
 * Показывает список задач с фильтрами (Активные / Выполненные / Все),
 * позволяет создавать, редактировать, удалять, отмечать выполненными.
 * Повторяющиеся задачи при выполнении автоматически создают клон.
 *
 * 🆕 AlarmManager: напоминания теперь ставятся на конкретную задачу
 * (TaskReminderScheduler.schedule/cancel). Глобальная настройка
 * TaskReminderPreferences осталась — она задаёт час:минуту и общий флаг
 * «напоминания включены».
 *
 * 🆕 v16.0.12: сортировка списка.
 *
 * 🆕 v19 (№10): удаление задачи — tombstone (softDelete с deletedBy).
 *
 * 🆕 v18.2.0 (шаг 2): Путь 2 + Путь 3.
 *  - В диалоге создания/редактирования появился блок «Когда напомнить»:
 *      · точное напоминание (remindAt) — по умолчанию «Сегодня 19:00»;
 *      · чекбокс «Напоминать, пока не сделаю» (autoRemindEnabled) — по умолчанию true.
 *  - При сохранении новые поля передаются в addTask/updateTask.
 *  - Для уже существующих задач, где remindAt = null и autoRemindEnabled = true,
 *    умный пинг включён по умолчанию (Путь 3) — задача без даты всё равно напомнит.
 */
class TaskListActivity : AppCompatActivity() {

    private val TAG = "TaskListActivity"
    private val DATE_FMT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val DATETIME_FMT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    private lateinit var db: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var tokenStorage: TokenStorage
    private lateinit var adapter: TaskAdapter

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

        adapter = TaskAdapter(
            onToggleDone = { task -> toggleDone(task) },
            onEdit = { task -> showEditDialog(task) },
            onDelete = { task -> confirmDelete(task) }
        )

        rvTasks.layoutManager = LinearLayoutManager(this)
        rvTasks.adapter = adapter

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
            adapter.submitList(tasks)

            if (tasks.isEmpty()) {
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

    // ============================================================
    // ДЕЙСТВИЯ
    // ============================================================

    private fun toggleDone(task: TaskEntity) {
        lifecycleScope.launch {
            try {
                if (task.isDone) {
                    withContext(Dispatchers.IO) { repository.markUndone(task.id) }
                    Logger.log(TAG, "toggleDone: undone ${task.id}")
                    // 🆕 AlarmManager: если задачу вернули в работу — перечитать
                    // актуальную версию и заново запланировать.
                    val refreshed = withContext(Dispatchers.IO) { repository.getById(task.id) }
                    if (refreshed != null) {
                        TaskReminderScheduler.schedule(this@TaskListActivity, refreshed)
                    }
                } else {
                    val result = withContext(Dispatchers.IO) {
                        repository.markDone(task.id, currentUser)
                    }
                    Logger.log(TAG, "toggleDone: done ${task.id}, clone=${result?.second?.id}")

                    // 🆕 AlarmManager: выполненная задача больше не напоминает.
                    // cancel снимает оба alarm (точный и умный).
                    TaskReminderScheduler.cancel(this@TaskListActivity, task.id)

                    // Если создался клон (повторяющаяся задача) — поставить напоминание на него
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
                    // 🆕 v19 (№10): tombstone с указанием, кто удалил.
                    withContext(Dispatchers.IO) {
                        repository.softDelete(task.id, currentUser)
                    }
                    // 🆕 AlarmManager: удалённая задача больше не напоминает.
                    // cancel снимает оба alarm (точный и умный).
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
        val chipGroupPriority = view.findViewById<ChipGroup>(R.id.chipGroupPriority)
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

        // Глобальная настройка «напоминания включены» + «во сколько напоминать»
        val switchReminder = view.findViewById<SwitchCompat>(R.id.switchReminder)
        val layoutReminderTime = view.findViewById<View>(R.id.layoutReminderTime)
        val textReminderTime = view.findViewById<TextView>(R.id.textReminderTime)
        val btnPickReminderTime = view.findViewById<View>(R.id.btnPickReminderTime)
        val textReminderHint = view.findViewById<TextView>(R.id.textReminderHint)

        // 🆕 v18.2.0 (шаг 2): Путь 2 + Путь 3
        val textRemindAt = view.findViewById<TextView>(R.id.textRemindAt)
        val btnPickRemindAt = view.findViewById<View>(R.id.btnPickRemindAt)
        val btnClearRemindAt = view.findViewById<View>(R.id.btnClearRemindAt)
        val switchAutoRemind = view.findViewById<SwitchCompat>(R.id.switchAutoRemind)
        val textAutoRemindHint = view.findViewById<TextView>(R.id.textAutoRemindHint)

        var pendingDueDate: Long? = task?.dueDate

        // 🆕 remindAt: null = умный пинг по умолчанию (Путь 3),
        //            конкретное значение = точный пинг (Путь 2).
        // Для новой задачи дефолт — «сегодня 19:00» (или «завтра 19:00»,
        // если сейчас уже позже 19:00). Пользователь может сбросить.
        var pendingRemindAt: Long? = task?.remindAt
            ?: defaultRemindAt()

        // 🆕 autoRemind: по умолчанию true (даже если точного remindAt нет,
        // задача будет пинговаться по Пути 3).
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

        // ============================================================
        // ЗАПОЛНЕНИЕ ПОЛЕЙ
        // ============================================================

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

        // ============================================================
        // СЛУШАТЕЛИ
        // ============================================================

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

        // ============================================================
        // ГЛОБАЛЬНАЯ НАСТРОЙКА «ВО СКОЛЬКО НАПОМИНАТЬ»
        // ============================================================

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

        // ============================================================
        // 🆕 ТОЧНОЕ НАПОМИНАНИЕ (Путь 2)
        // ============================================================

        btnPickRemindAt.setOnClickListener {
            val cal = Calendar.getInstance()
            pendingRemindAt?.let { cal.timeInMillis = it }

            DatePickerDialog(
                this,
                { _, year, month, day ->
                    val c = Calendar.getInstance()
                    c.set(year, month, day, 0, 0, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    // После выбора даты — сразу выбор времени, чтобы получить
                    // полноценный remindAt, а не просто «этот день».
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

        // ============================================================
        // ДИАЛОГ
        // ============================================================

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
                        // 🆕 AlarmManager: глобальную настройку применяем ДО сохранения,
                        // чтобы schedule() увидел актуальный флаг isEnabled.
                        applyGlobalReminderSetting(switchReminder.isChecked)

                        val savedTask: TaskEntity? = withContext(Dispatchers.IO) {
                            if (task == null) {
                                val newTask = repository.addTask(
                                    title = title,
                                    note = note,
                                    priority = priority,
                                    dueDate = pendingDueDate,
                                    recurrence = recurrence,
                                    createdBy = currentUser,
                                    // 🆕 v18.2.0 (шаг 2):
                                    remindAt = pendingRemindAt,
                                    autoRemindEnabled = pendingAutoRemind
                                )
                                newTask
                            } else {
                                // ⚠️ Для существующих задач нельзя «молча» перезаписать
                                // remindAt/autoRemindEnabled старыми значениями —
                                // берём то, что пользователь выбрал в диалоге.
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

                        // 🆕 AlarmManager: поставить (или снять) напоминание для задачи.
                        // schedule() сам разберётся: точный remindAt или умный пинг.
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

    /**
     * 🆕 v18.2.0 (шаг 2): дефолтное точное напоминание для новой задачи —
     * «сегодня 19:00», а если сейчас уже позже 19:00 — «завтра 19:00».
     *
     * Это решает главную проблему: даже если пользователь ничего не выбрал,
     * задача без даты всё равно пингует сегодня вечером.
     */
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

    /**
     * 🆕 AlarmManager: сохранить глобальную настройку «напоминания включены».
     */
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
