package com.family.base.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.TaskEntity
import com.family.base.ui.TaskListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 🆕 BroadcastReceiver для показа напоминания о конкретной задаче.
 *
 * Вызывается AlarmManager'ом ровно в момент напоминания (см. TaskReminderScheduler).
 *
 * Что делает:
 *  1. Достаёт taskId из extras.
 *  2. Читает задачу из БД (TaskDao.getById).
 *  3. Если задача ещё актуальна (не выполнена, не удалена) — показывает
 *     персональное уведомление с её названием.
 *
 * Клик по уведомлению открывает TaskListActivity.
 *
 * Регистрируется в AndroidManifest.xml.
 *
 * ⚠️ onReceive работает в главном потоке — БД читаем через runBlocking
 * с коротким таймаутом. Для одной задачи это быстро (Room + индекс по id).
 * Если когда-нибудь понадобится показывать много уведомлений — переделаем
 * на goAsync() + корутину.
 */
class TaskReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TaskReminderReceiver"

        /** Action, с которым AlarmManager шлёт broadcast. */
        const val ACTION_REMIND = "com.family.base.action.TASK_REMIND"

        /** Ключ extras: id задачи. */
        const val EXTRA_TASK_ID = "extra_task_id"

        /** Канал уведомлений. */
        const val CHANNEL_ID = "baza_task_reminders"
        const val CHANNEL_NAME = "Напоминания о делах"

        /**
         * Базовый id уведомления. Для разных задач — разные id,
         * чтобы уведомления не перекрывали друг друга.
         * Используем taskId.hashCode() как суффикс.
         */
        private const val BASE_NOTIFICATION_ID = 9000
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != ACTION_REMIND) {
                Logger.log(TAG, "onReceive: unexpected action=${intent?.action}, skip")
                return
            }

            val taskId = intent.getStringExtra(EXTRA_TASK_ID)
            if (taskId.isNullOrEmpty()) {
                Logger.log(TAG, "onReceive: no taskId, skip")
                return
            }

            Logger.log(TAG, "onReceive: taskId=$taskId")

            // 1. Проверяем разрешение на уведомления (Android 13+)
            if (!hasNotificationPermission(context)) {
                Logger.log(TAG, "onReceive: no POST_NOTIFICATIONS permission, skip")
                return
            }

            // 2. Читаем задачу из БД. Короткий runBlocking — это BroadcastReceiver,
            //    тут нельзя запускать долгие корутины без goAsync.
            val task = readTaskBlocking(context, taskId)
            if (task == null) {
                Logger.log(TAG, "onReceive: task $taskId not found, skip")
                return
            }

            if (task.isDone) {
                Logger.log(TAG, "onReceive: task $taskId already done, skip")
                return
            }
            if (task.isDeleted) {
                Logger.log(TAG, "onReceive: task $taskId deleted, skip")
                return
            }

            // 3. Показываем уведомление
            showNotification(context, task)

            Logger.log(TAG, "onReceive: notification shown for '$task.title'")
        } catch (e: Exception) {
            Logger.log(TAG, "onReceive error: ${e.message}")
        }
    }

    // ============================================================
    // ЧТЕНИЕ ЗАДАЧИ
    // ============================================================

    private fun readTaskBlocking(context: Context, taskId: String): TaskEntity? {
        return try {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val db = AppDatabase.getInstance(context)
                    db.taskDao().getById(taskId)
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "readTaskBlocking($taskId) error: ${e.message}")
            null
        }
    }

    // ============================================================
    // УВЕДОМЛЕНИЕ
    // ============================================================

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showNotification(context: Context, task: TaskEntity) {
        ensureChannel(context)

        // Клик по уведомлению открывает TaskListActivity
        val intent = Intent(context, TaskListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            intent,
            pendingFlags
        )

        val title = "🏠 Напоминание"
        val body = task.title

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationId = BASE_NOTIFICATION_ID + task.id.hashCode()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            Logger.log(TAG, "showNotification: shown for '$body' (id=$notificationId)")
        } catch (e: SecurityException) {
            Logger.log(TAG, "showNotification security error: ${e.message}")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Напоминания о домашних делах"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
        Logger.log(TAG, "ensureChannel: created")
    }
}
