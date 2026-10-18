package com.family.base.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.TaskEntity
import java.util.Calendar

/**
 * Планировщик напоминаний о задачах через AlarmManager.
 *
 * 🆕 Замена WorkManager на AlarmManager — точное время срабатывания.
 *
 * Как работает:
 *  - schedule(task) — ставит точный alarm на время напоминания для конкретной задачи.
 *    Время = dueDate задачи, приведённый к часу:минуте из TaskReminderPreferences
 *    (глобальная настройка «во сколько напоминать в день дедлайна»).
 *  - cancel(taskId) — снимает alarm для задачи.
 *  - cancelAll() — снимает alarms для ВСЕХ задач (при выключении напоминаний).
 *  - rescheduleAll() — перечитывает все активные задачи и перепланирует alarms
 *    (используется BootReceiver после перезагрузки/обновления).
 *
 * Использует setExactAndAllowWhileIdle (RTC_WAKEUP) — срабатывает точно,
 * даже в Doze. На Android 12+ требует разрешение SCHEDULE_EXACT_ALARM;
 * если его нет — fallback на setAndAllowWhileIdle (менее точный, но не молчит).
 *
 * Все методы обёрнуты в try/catch — ошибка планировщика не должна ронять приложение.
 */
object TaskReminderScheduler {

    private const val TAG = "TaskReminderScheduler"

    /**
     * Поставить (или переставить) alarm для конкретной задачи.
     *
     * Условия, при которых alarm не ставится:
     *  - напоминания выключены глобально;
     *  - у задачи нет dueDate;
     *  - задача уже выполнена или удалена;
     *  - вычисленное время напоминания уже в прошлом.
     *
     * Если задача не подходит под условия — существующий alarm для неё
     * снимается (чтобы не висел устаревший).
     */
    fun schedule(context: Context, task: TaskEntity) {
        try {
            if (!TaskReminderPreferences.isEnabled(context)) {
                Logger.log(TAG, "schedule(${task.id}): reminders disabled, cancel")
                cancel(context, task.id)
                return
            }

            if (task.isDone || task.isDeleted) {
                Logger.log(TAG, "schedule(${task.id}): task done/deleted, cancel")
                cancel(context, task.id)
                return
            }

            val dueDate = task.dueDate
            if (dueDate == null) {
                Logger.log(TAG, "schedule(${task.id}): no dueDate, cancel")
                cancel(context, task.id)
                return
            }

            val triggerAt = computeTriggerAtMillis(context, dueDate)
            if (triggerAt <= System.currentTimeMillis()) {
                Logger.log(TAG, "schedule(${task.id}): trigger in past ($triggerAt), cancel")
                cancel(context, task.id)
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = buildPendingIntent(context, task.id)

            if (canScheduleExact(alarmManager)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
                Logger.log(
                    TAG,
                    "schedule(${task.id}): exact alarm at ${formatTime(triggerAt)} (task='${task.title}')"
                )
            } else {
                // Нет разрешения на точные alarms — fallback на неточный, но не молчим.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
                Logger.log(
                    TAG,
                    "schedule(${task.id}): INEXACT alarm (no exact permission) at ${formatTime(triggerAt)}"
                )
            }
        } catch (e: Exception) {
            Logger.log(TAG, "schedule(${task.id}) error: ${e.message}")
        }
    }

    /**
     * Снять alarm для задачи.
     */
    fun cancel(context: Context, taskId: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = buildPendingIntent(context, taskId)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Logger.log(TAG, "cancel($taskId): done")
        } catch (e: Exception) {
            Logger.log(TAG, "cancel($taskId) error: ${e.message}")
        }
    }

    /**
     * 🆕 Снять alarms для ВСЕХ задач.
     *
     * Используется, когда пользователь выключает напоминания глобально
     * (applyGlobalReminderSetting → cancelAll). Без этого уже стоящие
     * alarms остались бы и сработали бы один раз после выключения.
     *
     * Читает задачи из БД синхронно (getAllTasksBlocking) — как и rescheduleAll,
     * это допустимо в контексте onReceive / лёгкой операции UI.
     */
    fun cancelAll(context: Context) {
        try {
            val db = AppDatabase.getInstance(context)
            val tasks = db.taskDao().getAllTasksBlocking()

            var cancelled = 0
            for (task in tasks) {
                cancel(context, task.id)
                cancelled++
            }
            Logger.log(TAG, "cancelAll: cancelled=$cancelled")
        } catch (e: Exception) {
            Logger.log(TAG, "cancelAll error: ${e.message}")
        }
    }

    /**
     * Перепланировать все активные задачи.
     *
     * Используется BootReceiver после перезагрузки/обновления приложения:
     * система стирает все alarms при выключении, их надо восстановить.
     *
     * Читает задачи из БД синхронно (getAllTasksBlocking) — BootReceiver
     * не корутина, а таблица маленькая, запрос быстрый.
     */
    fun rescheduleAll(context: Context) {
        try {
            if (!TaskReminderPreferences.isEnabled(context)) {
                Logger.log(TAG, "rescheduleAll: reminders disabled, skip")
                return
            }

            val db = AppDatabase.getInstance(context)
            val tasks = db.taskDao().getAllTasksBlocking()

            var scheduled = 0
            var skipped = 0
            for (task in tasks) {
                if (task.isDone || task.isDeleted || task.dueDate == null) {
                    skipped++
                    continue
                }
                schedule(context, task)
                scheduled++
            }
            Logger.log(TAG, "rescheduleAll: scheduled=$scheduled, skipped=$skipped")
        } catch (e: Exception) {
            Logger.log(TAG, "rescheduleAll error: ${e.message}")
        }
    }

    // ============================================================
    // ВНУТРЕННЕЕ
    // ============================================================

    /**
     * Вычисляет точное время срабатывания alarm:
     * берёт дату из dueDate (год/месяц/день) и подставляет час:минуту
     * из TaskReminderPreferences (глобальная настройка «во сколько напоминать»).
     *
     * Пример: dueDate = 25.10.2026 00:00, настройка 09:30 → trigger = 25.10.2026 09:30.
     */
    private fun computeTriggerAtMillis(context: Context, dueDate: Long): Long {
        val due = Calendar.getInstance().apply { timeInMillis = dueDate }
        val target = Calendar.getInstance().apply {
            set(Calendar.YEAR, due.get(Calendar.YEAR))
            set(Calendar.MONTH, due.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, due.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, TaskReminderPreferences.getHour(context).coerceIn(0, 23))
            set(Calendar.MINUTE, TaskReminderPreferences.getMinute(context).coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return target.timeInMillis
    }

    /**
     * Собирает PendingIntent на TaskReminderReceiver для конкретной задачи.
     * requestCode = хэш taskId — чтобы у разных задач были разные PendingIntent.
     */
    private fun buildPendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = TaskReminderReceiver.ACTION_REMIND
            putExtra(TaskReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            flags
        )
    }

    /**
     * Проверка, можно ли ставить точные alarms.
     * На Android 12+ требуется разрешение SCHEDULE_EXACT_ALARM.
     * На более старых — всегда можно.
     */
    private fun canScheduleExact(alarmManager: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun formatTime(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val h = c.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val m = c.get(Calendar.MINUTE).toString().padStart(2, '0')
        val d = c.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val mo = (c.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        return "$d.$mo ${h}:$m"
    }
}
