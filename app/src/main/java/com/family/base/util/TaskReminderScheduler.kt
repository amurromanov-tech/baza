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
import com.family.base.data.repository.TaskRepository
import com.family.base.ui.TaskListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 🆕 BroadcastReceiver для показа напоминания о конкретной задаче.
 *
 * Вызывается AlarmManager'ом ровно в момент напоминания (см. TaskReminderScheduler).
 *
 * 🆕 v18.2.0 (шаг 2): два типа alarm.
 *  - EXTRA_IS_SMART = false → точный пинг (Путь 2: remindAt или dueDate-15мин).
 *    После показа уведомления: сбрасываем remindAt у задачи (точный пинг
 *    больше не нужен), outbox-логируем. Дальше задача либо уйдёт в умный пинг
 *    (если autoRemindEnabled = true), либо замолчит.
 *  - EXTRA_IS_SMART = true → умный пинг (Путь 3).
 *    После показа уведомления: перепланируем следующий умный пинг на
 *    следующий интервал по важности (через TaskReminderScheduler.schedule).
 *
 * Что делает onReceive:
 *  1. Достаёт taskId и smart-флаг из extras.
 *  2. Читает задачу из БД (TaskDao.getById).
 *  3. Если задача ещё актуальна (не выполнена, не удалена) — показывает
 *     уведомление.
 *  4. Для точного пинга — сбрасывает remindAt и логирует в outbox.
 *     Для умного — перепланирует следующий умный пинг.
 *
 * Клик по уведомлению открывает TaskListActivity.
 *
 * ⚠️ onReceive работает в главном потоке. Чтение БД и запись (сброс remindAt,
 * outbox) — через goAsync() + applicationScope.launch, чтобы не ловить
 * "Cannot access database on the main thread" (урок из шага 1).
 *
 * Регистрируется в AndroidManifest.xml.
 */
class TaskReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TaskReminderReceiver"

        /** Action, с которым AlarmManager шлёт broadcast. */
        const val ACTION_REMIND = "com.family.base.action.TASK_REMIND"

        /** Ключ extras: id задачи. */
        const val EXTRA_TASK_ID = "extra_task_id"

        /** 🆕 v18.2.0 (шаг 2): true — умный пинг (Путь 3), false — точный (Путь 2). */
        const val EXTRA_IS_SMART = "extra_is_smart"

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

            val isSmart = intent.getBooleanExtra(EXTRA_IS_SMART, false)

            Logger.log(TAG, "onReceive: taskId=$taskId, isSmart=$isSmart")

            // 1. Проверяем разрешение на уведомления (Android 13+)
            if (!hasNotificationPermission(context)) {
                Logger.log(TAG, "onReceive: no POST_NOTIFICATIONS permission, skip")
                return
            }

            // 2. goAsync — даём ресиверу дожить до конца корутины:
            //    нужно прочитать задачу, показать уведомление и обновить БД
            //    (сброс remindAt / outbox / перепланирование).
            val pendingResult = goAsync()
            val appContext = context.applicationContext

            com.family.base.BaseApplication.applicationScope.launch {
                try {
                    handleReminder(appContext, taskId, isSmart)
                } catch (e: Exception) {
                    Logger.log(TAG, "handleReminder error: ${e.message}", e)
                } finally {
                    try {
                        pendingResult.finish()
                    } catch (e: Exception) {
                        Logger.log(TAG, "pendingResult.finish error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "onReceive error: ${e.message}")
        }
    }

    // ============================================================
    // ОСНОВНАЯ ЛОГИКА (в корутине, IO)
    // ============================================================

    private suspend fun handleReminder(context: Context, taskId: String, isSmart: Boolean) {
        // 1. Читаем задачу из БД (с IO-диспетчером).
        val db = AppDatabase.getInstance(context)
        val task = withContext(Dispatchers.IO) { db.taskDao().getById(taskId) }

        if (task == null) {
            Logger.log(TAG, "handleReminder: task $taskId not found, skip")
            return
        }
        if (task.isDone) {
            Logger.log(TAG, "handleReminder: task $taskId already done, skip")
            return
        }
        if (task.isDeleted) {
            Logger.log(TAG, "handleReminder: task $taskId deleted, skip")
            return
        }

        // 2. Показываем уведомление (разный текст для точного и умного).
        showNotification(context, task, isSmart)

        // 3. Пост-обработка.
        if (isSmart) {
            // Умный пинг: перепланируем следующий на следующий интервал.
            // schedule() сам разберётся: если autoRemindEnabled = false —
            // снимет оба alarm и задача замолчит.
            try {
                TaskReminderScheduler.schedule(context, task)
                Logger.log(TAG, "handleReminder: smart ping rescheduled for '$taskId'")
            } catch (e: Exception) {
                Logger.log(TAG, "handleReminder: reschedule smart error: ${e.message}")
            }
        } else {
            // Точный пинг: сбрасываем remindAt (он уже сработал), логируем
            // в outbox. dueDate не трогаем — задача остаётся с тем же сроком.
            try {
                val repository = TaskRepository(context, db)
                val updated = repository.clearRemindAtAfterNotification(
                    taskId = taskId,
                    changedBy = task.createdBy
                )
                Logger.log(
                    TAG,
                    "handleReminder: remindAt cleared for '$taskId' " +
                        "(updated=${updated != null})"
                )

                // Перепланируем: теперь у задачи может включиться умный пинг
                // (если autoRemindEnabled = true и dueDate нет/в прошлом)
                // или задача замолчит (если autoRemindEnabled = false).
                val refreshed = withContext(Dispatchers.IO) { db.taskDao().getById(taskId) }
                if (refreshed != null) {
                    TaskReminderScheduler.schedule(context, refreshed)
                }
            } catch (e: Exception) {
                Logger.log(TAG, "handleReminder: clearRemindAt error: ${e.message}")
            }
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

    private fun showNotification(context: Context, task: TaskEntity, isSmart: Boolean) {
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

        // 🆕 v18.2.0 (шаг 2): разный заголовок для точного и умного пинга.
        val title = if (isSmart) "🏠 Напоминание (умное)" else "🏠 Напоминание"
        val body = if (isSmart) {
            "Долго висит: ${task.title}"
        } else {
            task.title
        }

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

        // ID уведомления: точный и умный для одной задачи НЕ должны
        // перекрывать друг друга (на всякий случай), поэтому
        // для умного — отдельный суффикс.
        val notificationId = if (isSmart) {
            BASE_NOTIFICATION_ID + task.id.hashCode() + 1
        } else {
            BASE_NOTIFICATION_ID + task.id.hashCode()
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            Logger.log(TAG, "showNotification: shown for '$body' (id=$notificationId, smart=$isSmart)")
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
