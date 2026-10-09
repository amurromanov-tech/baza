package com.family.base.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.TaskEntity
import com.family.base.data.repository.TaskRepository
import com.family.base.ui.TaskListActivity
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Worker, который раз в сутки проверяет задачи с приближающимся/просроченным дедлайном
 * и показывает одно сводное уведомление.
 *
 * Правила отбора задач:
 *  - isDone = false
 *  - isDeleted = false
 *  - dueDate != null
 *  - dueDate <= now + 24 часа  (т.е. дедлайн сегодня, завтра или уже просрочен)
 *
 * Если таких задач нет — уведомление не показывается.
 */
class TaskReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "TaskReminderWorker"
        const val CHANNEL_ID = "baza_task_reminders"
        const val CHANNEL_NAME = "Напоминания о делах"
        const val NOTIFICATION_ID = 9001
    }

    override suspend fun doWork(): Result {
        return try {
            // Проверяем разрешение на уведомления (Android 13+)
            if (!hasNotificationPermission()) {
                Logger.log(TAG, "doWork: no notification permission, skip")
                return Result.success()
            }

            // Проверяем, что напоминания всё ещё включены
            if (!TaskReminderPreferences.isEnabled(applicationContext)) {
                Logger.log(TAG, "doWork: reminders disabled, skip")
                return Result.success()
            }

            val db = AppDatabase.getInstance(applicationContext)
            val repository = TaskRepository(applicationContext, db)

            val now = System.currentTimeMillis()
            val horizon = now + TimeUnit.HOURS.toMillis(24)

            val all = repository.getAllSorted()
            val due = all.filter { t ->
                !t.isDone &&
                !t.isDeleted &&
                t.dueDate != null &&
                t.dueDate!! <= horizon
            }

            Logger.log(TAG, "doWork: total=${all.size}, due=$due")

            if (due.isEmpty()) {
                return Result.success()
            }

            showNotification(due)

            Result.success()
        } catch (e: Exception) {
            Logger.log(TAG, "doWork error: ${e.message}")
            // Не роняем приложение — просто сообщаем WorkManager, что повтор не нужен
            Result.success()
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val ctx = applicationContext
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showNotification(tasks: List<TaskEntity>) {
        val ctx = applicationContext

        ensureChannel(ctx)

        // Клик по уведомлению открывает TaskListActivity
        val intent = Intent(ctx, TaskListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, 0, intent, pendingFlags
        )

        val title = if (tasks.size == 1) "🏠 Задача на сегодня" else "🏠 Задачи на сегодня: ${tasks.size}"
        val body = buildBody(tasks)

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
            Logger.log(TAG, "showNotification: shown (${tasks.size} tasks)")
        } catch (e: SecurityException) {
            Logger.log(TAG, "showNotification security error: ${e.message}")
        }
    }

    private fun buildBody(tasks: List<TaskEntity>): String {
        if (tasks.size == 1) {
            return tasks[0].title
        }
        // Показываем первые 5, остальные — многоточием
        val shown = tasks.take(5).joinToString("\n") { "• ${it.title}" }
        return if (tasks.size > 5) {
            "$shown\n… и ещё ${tasks.size - 5}"
        } else {
            shown
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Ежедневные напоминания о домашних делах"
        }
        manager.createNotificationChannel(channel)
        Logger.log(TAG, "ensureChannel: created")
    }
}
