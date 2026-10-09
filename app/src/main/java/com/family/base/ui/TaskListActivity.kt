package com.family.base.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.base.BaseApplication
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Экран «🏠 Домашние дела».
 *
 * Показывает список задач с фильтрами (Активные / Выполненные / Все),
 * позволяет создавать, редактировать, удалять, отмечать выполненными.
 * Повторяющиеся задачи при выполнении автоматически создают клон.
 *
 * 🆕 v14.1: настройки напоминаний (⚙ в toolbar).
 */
class TaskListActivity : AppCompatActivity() {

    private val TAG = "TaskListActivity"
    private val DATE_FMT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private lateinit var db: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var tokenStorage: TokenStorage
    private lateinit var reminderPrefs: TaskReminderPreferences
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

    // ============================================================
    // РАЗРЕШЕНИЕ НА УВЕДОМЛЕНИЯ (Android 13+)
    // ============================================================
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.log(TAG, "POST_NOTIFICATIONS granted=$granted")
        if (!granted) {
            Toast.makeText(
                this,
                "Без разрешения напоминания не смогут показываться",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_list)

        db = AppDatabase.getInstance(this)
        repository = TaskRepository(applicationContext, db)
        tokenStorage = TokenStorage(this)
        reminderPrefs = TaskReminderPreferences(this)

        rvTasks = findViewById(R.id.rvTasks)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        textCounter = findViewById(R.id.textCounter)
        fabAddTask = findViewById(R.id.fabAddTask)
        chipGroupFilter = findViewById(R.id.chipGroupFilter)
        chipActive = findViewById(R.id.chipActive)
        chipDone = findViewById(R.id.chipDone)
        chipAll = findViewById(R.id.chipAll)

        // Toolbar: кнопка «назад» = finish, кнопка ⚙ = настройки напоминаний
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.setNavigationOnClickListener { finish() }
        toolbar?.inflateMenu(R.menu.menu_task_list)
        toolbar?.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reminder_settings) {
                showReminderSettingsDialog()
                true
            } else false
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

        // Запрос разрешения на уведомления (Android 13+)
        requestNotificationPermissionIfNeeded()

        loadTasks()
    }

    override fun onResume() {
        super.onResume()
        loadTasks()
    }

    // ============================================================
    // РАЗРЕШЕНИЕ
    // ============================================================
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted && reminderPrefs.isEnabled) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
                            "🔁 Создана следующая: ${clone.title}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                // Напоминания могли измениться — решедул
                TaskReminderScheduler.reschedule(this@TaskListActivity)
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
                    TaskReminderScheduler.reschedule(this@TaskListActivity)
                    Toast.makeText(this@TaskListActivity, "Удалено", Toast.LENGTH_SHORT).show()
                    loadTasks()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // НАСТРОЙКИ НАПОМИНАНИЙ
    // ============================================================
    private fun showReminderSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_task_reminder_settings, null)

        val switchEnabled = view.findViewById<MaterialSwitch>(R.id.switchRemindersEnabled)
        val textHour = view.findViewById<TextView>(R.id.textHour)
        val btnPickHour = view.findViewById<View>(R.id.btnPickHour)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupDaysBefore)
        val chipDays0 = view.findViewById<Chip>(R.id.chipDays0)
        val chipDays1 = view.findViewById<Chip>(R.id.chipDays1)
        val chipDays3 = view.findViewById<Chip>(R.id.chipDays3)
        val chipDays7 = view.findViewById<Chip>(R.id.chipDays7)

        // Заполняем текущие значения
        switchEnabled.isChecked = reminderPrefs.isEnabled
        textHour.text = String.format(Locale.getDefault(), "%02d:00", reminderPrefs.hour)
        when (reminderPrefs.daysBefore) {
            0 -> chipDays0.isChecked = true
            1 -> chipDays1.isChecked = true
            3 -> chipDays3.isChecked = true
            7 -> chipDays7.isChecked = true
            else -> chipDays0.isChecked = true
        }

        // Временные значения в диалоге
        var pendingHour = reminderPrefs.hour

        btnPickHour.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, _ ->
                    pendingHour = hourOfDay
                    textHour.text = String.format(Locale.getDefault(), "%02d:00", hourOfDay)
                },
                pendingHour,
                0,
                true
            ).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Настройки напоминаний")
            .setView(view)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val enabled = switchEnabled.isChecked
                val daysBefore = when {
                    chipDays1.isChecked -> 1
                    chipDays3.isChecked -> 3
                    chipDays7.isChecked -> 7
                    else -> 0
                }

                reminderPrefs.isEnabled = enabled
                reminderPrefs.hour = pendingHour
                reminderPrefs.daysBefore = daysBefore

                Logger.log(
                    TAG,
                    "Reminder settings saved: enabled=$enabled, hour=$pendingHour, daysBefore=$daysBefore"
                )

                if (enabled) {
                    requestNotificationPermissionIfNeeded()
                    TaskReminderScheduler.reschedule(this@TaskListActivity)
                    Toast.makeText(
                        this@TaskListActivity,
                        "Напоминания включены (${String.format(Locale.getDefault(), "%02d:00", pendingHour)})",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    TaskReminderScheduler.cancel(this@TaskListActivity)
                    Toast.makeText(
                        this@TaskListActivity,
                        "Напоминания выключены",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                dialog.dismiss()
            }
        }

        dialog.show()
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
                        // Напоминания могли измениться — решедул
                        TaskReminderScheduler.reschedule(this@TaskListActivity)
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
}
