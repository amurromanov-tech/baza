package com.family.base.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.TaskEntity
import com.family.base.ui.TaskListActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * WorkManager-воркер: проверяет задачи с дедлайном и шлёт одно сводное уведомление.
 *
 * Логика:
 *  1. Берём все активные (isDone = 0, isDeleted = 0) задачи с непустым dueDate.
 *  2. Оставляем те, чей дедлайн <= (now + daysBefore дней).
 *  3. Если таких нет — выходим тихо.
 *  4. Если есть — формируем одно сводное уведомление.
 *
 * Не отправляет повторно, если с последней проверки прошло меньше 20 часов
 * (защита от спама при старте приложения).
 */
class TaskReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "task_reminders"
        const val CHANNEL_NAME = "Напоминания о задачах"
        const val NOTIFICATION_ID = 9001
        private const val MIN_INTERVAL_MS = 20L * 60 * 60 * 1000  // 20 часов
        private const val TAG = "TaskReminderWorker"
    }

    override suspend fun doWork(): Result {
        val prefs = TaskReminderPreferences(applicationContext)

        if (!prefs.isEnabled) {
            Logger.log(TAG, "Reminders disabled, skipping")
            return Result.success()
        }

        // Защита от повторного запуска в течение 20 часов
        val now = System.currentTimeMillis()
        if (now - prefs.lastCheck < MIN_INTERVAL_MS) {
            Logger.log(TAG, "Last check was ${(now - prefs.lastCheck) / 1000 / 60} min ago, skipping")
            return Result.success()
        }

        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val tasks = db.taskDao().getActiveSorted()

            val daysBefore = prefs.daysBefore
            val threshold = now + daysBefore * 24L * 60 * 60 * 1000

            val dueTasks = tasks.filter { task ->
                task.dueDate != null && task.dueDate <= threshold
            }

            Logger.log(TAG, "Found ${dueTasks.size} due tasks (daysBefore=$daysBefore)")

            if (dueTasks.isEmpty()) {
                prefs.lastCheck = now
                return Result.success()
            }

            showSummaryNotification(dueTasks)
            prefs.lastCheck = now
            Result.success()
        } catch (e: Exception) {
            Logger.log(TAG, "Error in doWork: ${e.message}", e)
            Result.retry()
        }
    }

    // ============================================================
    // УВЕДОМЛЕНИЕ
    // ============================================================

    private fun showSummaryNotification(tasks: List<TaskEntity>) {
        createChannelIfNeeded()

        val title: String
        val body: String

        if (tasks.size == 1) {
            val task = tasks.first()
            title = "📋 Задача: ${task.title}"
            body = buildSingleBody(task)
        } else {
            title = "📋 Задачи на сегодня (${tasks.size})"
            body = tasks.take(5).joinToString("\n") { task ->
                val priority = priorityEmoji(task.priority)
                "• $priority ${task.title}"
            } + if (tasks.size > 5) "\n…и ещё ${tasks.size - 5}" else ""
        }

        val intent = Intent(applicationContext, TaskListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            pendingFlags
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_pending)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, notification)
            Logger.log(TAG, "Notification shown: $title")
        } catch (e: SecurityException) {
            Logger.log(TAG, "SecurityException showing notification: ${e.message}")
        }
    }

    private fun buildSingleBody(task: TaskEntity): String {
        val parts = mutableListOf<String>()
        task.dueDate?.let { due ->
            val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            val overdue = due < System.currentTimeMillis()
            parts.add(if (overdue) "⏰ Просрочено: ${fmt.format(Date(due))}" else "⏰ ${fmt.format(Date(due))}")
        }
        task.note?.let { parts.add(it) }
        return parts.joinToString("\n")
    }

    private fun priorityEmoji(priority: Int): String = when (priority) {
        TaskEntity.PRIORITY_URGENT -> "🔴"
        TaskEntity.PRIORITY_HIGH -> "🟠"
        TaskEntity.PRIORITY_NORMAL -> "🟡"
        TaskEntity.PRIORITY_LOW -> "🟢"
        else -> "🟡"
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Сводные напоминания о задачах «Домашние дела»"
        }
        manager.createNotificationChannel(channel)
        Logger.log(TAG, "Notification channel created: $CHANNEL_ID")
    }
}
