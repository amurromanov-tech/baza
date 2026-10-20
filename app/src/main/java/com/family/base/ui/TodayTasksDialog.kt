package com.family.base.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.family.base.R
import com.family.base.data.local.entity.TaskEntity
import com.family.base.databinding.DialogTodayTasksBinding
import com.family.base.util.Logger
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 🆕 v20.2.3: парящее окно «На сегодня» при запуске MainActivity.
 *
 * Показывает задачи, которые попадают в сегодняшний день
 * (дедлайн сегодня, напоминание сегодня или просрочка).
 *
 * Поведение:
 *  - если задач нет — пустое состояние «🌿 На сегодня ничего срочного»;
 *  - если есть — список с цветной полоской (🔴 просрочено / 🟠 сегодня);
 *  - кнопка «Открыть Домашние дела →» и ✕ закрывают окно и открывают
 *    TaskListActivity (если передан onOpenTasks — вызывается он);
 *  - тап вне окна и кнопка Назад тоже закрывают.
 *
 * Окно показывается не чаще одного раза в день — контроль в MainActivity
 * (TaskReminderPreferences.getLastTodayDialogDate).
 */
object TodayTasksDialog {

    private const val TAG = "TodayTasksDialog"

    /**
     * Показать окно «На сегодня».
     *
     * @param activity    Activity, поверх которой показываем.
     * @param tasks       список задач (уже отфильтрованный DAO).
     * @param onOpenTasks что сделать по кнопке «Открыть Домашние дела →».
     *                    Если null — просто закрыть окно.
     */
    fun show(
        activity: Activity,
        tasks: List<TaskEntity>,
        onOpenTasks: (() -> Unit)? = null
    ) {
        val dialog = Dialog(activity)
        val binding = DialogTodayTasksBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setDimAmount(0.4f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                windowAnimations = android.R.style.Animation_Dialog
            }
        }

        // ===== Дата =====
        binding.tvTodayDate.text = formatTodayLabel()

        // ===== Ограничение высоты списка (50% экрана) =====
        val screenHeight = activity.resources.displayMetrics.heightPixels
        val maxListHeight = (screenHeight * 0.5f).toInt()
        binding.tasksScroll.layoutParams = binding.tasksScroll.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.tasksScroll.post {
            val measured = binding.tasksScroll.measuredHeight
            if (measured > maxListHeight) {
                binding.tasksScroll.layoutParams =
                    binding.tasksScroll.layoutParams.apply { height = maxListHeight }
            }
        }

        // ===== Список / пустое состояние =====
        val now = System.currentTimeMillis()
        val visibleTasks = tasks.filter { !it.isDone && !it.isDeleted }

        if (visibleTasks.isEmpty()) {
            binding.tasksScroll.visibility = View.GONE
            binding.todayDivider.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.btnTodayOpenTasks.setText(R.string.today_dialog_open_tasks_empty)
        } else {
            binding.tasksScroll.visibility = View.VISIBLE
            binding.todayDivider.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
            binding.btnTodayOpenTasks.setText(R.string.today_dialog_open_tasks)
            fillTasks(activity, binding.tasksContainer, visibleTasks, now)
        }

        // ===== Кнопки =====
        binding.btnTodayClose.setOnClickListener {
            Logger.log(TAG, "Closed by X")
            dialog.dismiss()
        }
        binding.btnTodayOpenTasks.setOnClickListener {
            Logger.log(TAG, "Open tasks clicked")
            dialog.dismiss()
            onOpenTasks?.invoke()
        }

        dialog.setOnDismissListener {
            Logger.log(TAG, "Dismissed")
        }

        dialog.show()
    }

    // ============================================================
    // ЗАПОЛНЕНИЕ СПИСКА
    // ============================================================

    private fun fillTasks(
        activity: Activity,
        container: LinearLayout,
        tasks: List<TaskEntity>,
        now: Long
    ) {
        container.removeAllViews()

        val df = SimpleDateFormat("HH:mm", Locale.getDefault())

        for (task in tasks) {
            val isOverdue = isOverdue(task, now)
            container.addView(buildTaskRow(activity, task, isOverdue, df))
        }
    }

    private fun buildTaskRow(
        activity: Activity,
        task: TaskEntity,
        isOverdue: Boolean,
        df: SimpleDateFormat
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(activity, 6)
                bottomMargin = dp(activity, 6)
            }
            setPadding(0, dp(activity, 4), 0, dp(activity, 4))
        }

        // Цветная полоска слева
        val strip = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 4), dp(activity, 32)).apply {
                marginEnd = dp(activity, 12)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 2).toFloat()
                setColor(
                    if (isOverdue) {
                        ContextCompat.getColor(activity, android.R.color.holo_red_dark)
                    } else {
                        ContextCompat.getColor(activity, android.R.color.holo_orange_dark)
                    }
                )
            }
        }
        row.addView(strip)

        // Название (+ note, если есть)
        val titleColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val titleTv = TextView(activity).apply {
            text = task.title
            textSize = 15f
            setTextColor(ContextCompat.getColor(activity, android.R.color.black))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleColumn.addView(titleTv)

        val note = task.note?.trim()
        if (!note.isNullOrEmpty()) {
            val noteTv = TextView(activity).apply {
                text = note
                textSize = 12f
                setTextColor(Color.GRAY)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(activity, 2), 0, 0)
            }
            titleColumn.addView(noteTv)
        }

        row.addView(titleColumn)

        // Время справа: remindAt приоритетнее dueDate
        val timeText = buildString {
            if (task.remindAt != null) {
                append("🔔 ")
                append(df.format(Date(task.remindAt)))
            } else if (task.dueDate != null) {
                append(df.format(Date(task.dueDate)))
            }
            if (isOverdue) {
                if (isNotEmpty()) append(" · ")
                append("просрочено")
            }
        }
        if (timeText.isNotEmpty()) {
            val timeTv = TextView(activity).apply {
                text = timeText
                textSize = 12f
                setTextColor(
                    if (isOverdue) {
                        ContextCompat.getColor(activity, android.R.color.holo_red_dark)
                    } else {
                        Color.GRAY
                    }
                )
                setPadding(dp(activity, 8), 0, 0, 0)
            }
            row.addView(timeTv)
        }

        return row
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНОЕ
    // ============================================================

    private fun isOverdue(task: TaskEntity, now: Long): Boolean {
        val dueOverdue = task.dueDate != null && task.dueDate < now
        val remindOverdue = task.remindAt != null && task.remindAt < now
        return dueOverdue || remindOverdue
    }

    private fun formatTodayLabel(): String {
        val cal = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("d MMMM", Locale("ru"))
        val weekdayFormat = SimpleDateFormat("EEEE", Locale("ru"))
        val dateStr = dateFormat.format(cal.time)
        val weekday = weekdayFormat.format(cal.time)
        return "$dateStr, $weekday"
    }

    private fun dp(activity: Activity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
