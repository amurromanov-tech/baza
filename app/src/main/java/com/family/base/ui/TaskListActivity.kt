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
 * v14.1.0: возвращены напоминания — через WorkManager, глобальная настройка
 * (одно время для всех задач). Секция «Напоминание» встроена в диалог задачи.
 */
class TaskListActivity : AppCompatActivity() {

    private val TAG = "TaskListActivity"
    private val DATE_FMT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

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

        // Toolbar: кнопка «назад» = finish
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.setNavigationOnClickListener { finish() }

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
    // ЗАГРУЗКА
    // ============================================================
    private fun loadTasks() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                when (currentFilter) {
                    Filter.ACTIVE -> repository.getActiveSorted()
                    Filter.DONE -> repository.getDoneSorted()
                    Filter.ALL -> repository.getAllSorted()
                }
            }
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
                } else {
                    val result = withContext(Dispatchers.IO) {
                        repository.markDone(task.id, currentUser)
                    }
                    Logger.log(TAG, "toggleDone: done ${task.id}, clone=${result?.second?.id}")
                    result?.second?.let { clone ->
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
                    withContext(Dispatchers.IO) { repository.softDelete(task.id) }
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

        // v14.1.0: напоминания
        val switchReminder = view.findViewById<SwitchCompat>(R.id.switchReminder)
        val layoutReminderTime = view.findViewById<View>(R.id.layoutReminderTime)
        val textReminderTime = view.findViewById<TextView>(R.id.textReminderTime)
        val btnPickReminderTime = view.findViewById<View>(R.id.btnPickReminderTime)
        val textReminderHint = view.findViewById<TextView>(R.id.textReminderHint)

        var pendingDueDate: Long? = task?.dueDate

        fun updateDueDateLabel() {
            if (pendingDueDate == null) {
                textDueDate.text = "Не указан"
            } else {
                textDueDate.text = DATE_FMT.format(Date(pendingDueDate!!))
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

        updateDueDateLabel()

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
        // v14.1.0: НАПОМИНАНИЯ
        // ============================================================

        fun updateReminderTimeLabel() {
            textReminderTime.text = TaskReminderPreferences.getTimeLabel(this)
        }

        fun updateReminderVisibility() {
            val visible = switchReminder.isChecked
            layoutReminderTime.visibility = if (visible) View.VISIBLE else View.GONE
            textReminderHint.visibility = if (visible) View.VISIBLE else View.GONE
        }

        // Инициализация из глобальных настроек
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
                        withContext(Dispatchers.IO) {
                            if (task == null) {
                                repository.addTask(
                                    title = title,
                                    note = note,
                                    priority = priority,
                                    dueDate = pendingDueDate,
                                    recurrence = recurrence,
                                    createdBy = currentUser
                                )
                            } else {
                                val updated = task.copy(
                                    title = title,
                                    note = note,
                                    priority = priority,
                                    dueDate = pendingDueDate,
                                    recurrence = recurrence,
                                    updatedAt = System.currentTimeMillis()
                                )
                                repository.updateTask(updated)
                            }
                        }

                        // v14.1.0: применить настройку напоминаний
                        applyReminderSetting(switchReminder.isChecked)

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
     * Применить глобальную настройку напоминаний:
     *  - сохранить в SharedPreferences
     *  - перепланировать или отменить WorkManager
     * Всё в try/catch — ошибка планировщика не должна ронять приложение.
     */
    private fun applyReminderSetting(enabled: Boolean) {
        try {
            val wasEnabled = TaskReminderPreferences.isEnabled(this)
            TaskReminderPreferences.setEnabled(this, enabled)

            if (enabled) {
                TaskReminderScheduler.reschedule(this)
                if (!wasEnabled) {
                    Toast.makeText(
                        this,
                        "Напоминания включены (${TaskReminderPreferences.getTimeLabel(this)})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                TaskReminderScheduler.cancel(this)
                if (wasEnabled) {
                    Toast.makeText(this, "Напоминания выключены", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "applyReminderSetting error: ${e.message}")
        }
    }
}
