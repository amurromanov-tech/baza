package com.family.base.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Планировщик напоминаний о задачах через AlarmManager.
 *
 * 🆕 v20.0.0 (шаг 1): ФИКС ПАДЕНИЯ НА ГЛАВНОМ ПОТОКЕ.
 *  - Чтение БД для rescheduleAll/cancelAll вынесено в Dispatchers.IO.
 *
 * 🆕 v20.0.0 (шаг 2): ПУТЬ 2 (remindAt) + ПУТЬ 3 (умный пинг).
 *
 * 🆕 v20.1.0 (Вариант A): УБРАНО ГЛОБАЛЬНОЕ ВРЕМЯ СУТОК.
 *  - Раньше точный пинг от dueDate и умный пинг брали час:минуту
 *    из TaskReminderPreferences (одна настройка на всё приложение).
 *    Это путало: меняешь у одной задачи — «меняется у всех».
 *  - Теперь время суток для этих расчётов — ФИКСИРОВАННОЕ:
 *      DEFAULT_REMINDER_HOUR = 8, DEFAULT_REMINDER_MINUTE = 0
 *    (8:00 утра локального времени).
 *  - Точное напоминание (remindAt) по-прежнему per-task: пользователь
 *    задаёт дату+время в диалоге, и это значение используется как есть.
 *  - TaskReminderPreferences.getHour/getMinute больше не читаются.
 *    Глобальный бегунок isEnabled/setEnabled остаётся (вкл/выкл
 *    напоминаний).
 *
 * Приоритет напоминаний для одной задачи:
 *  1) remindAt задан и в будущем → точный alarm на remindAt (Путь 2).
 *  2) remindAt нет, dueDate задан и в будущем → точный alarm
 *     на dueDate - 15 минут (Путь 2, время суток уже в dueDate).
 *  3) remindAt нет, dueDate нет или в прошлом, autoRemindEnabled = true
 *     → умный пинг (Путь 3) в 08:00 локального времени:
 *        - важные (HIGH/URGENT) → ежедневно;
 *        - обычные (NORMAL)     → раз в 2 дня;
 *        - низкие (LOW)         → раз в 3 дня.
 *     Просроченные (dueDate < now) пингуются ежедневно.
 *  4) autoRemindEnabled = false и точных пингов нет → задача молчит.
 *
 * ⚠️ requestCode для точного и умного alarm РАЗНЫЕ
 * (taskId.hashCode() для точного, +SMART_OFFSET для умного).
 *
 * ⚠️ Все методы обёрнуты в try/catch — ошибка планировщика
 * не должна ронять приложение.
 */
object TaskReminderScheduler {

    private const val TAG = "TaskReminderScheduler"

    /** Сдвиг requestCode для умного пинга, чтобы не пересекаться с точным. */
    private const val SMART_REQUEST_OFFSET = 100_000

    /** За сколько минут до dueDate напоминать, если remindAt не задан. */
    private const val DUE_SOON_MINUTES = 15

    /**
     * 🆕 v20.1.0 (Вариант A): фиксированное время суток для умного пинга
     * и для точного пинга от dueDate.
     *
     * Раньше бралось из TaskReminderPreferences (одна настройка на всех).
     * Теперь — константа 08:00, чтобы не путать пользователя.
     * Если понадобится per-task время — вернёмся к этому отдельно.
     */
    private const val DEFAULT_REMINDER_HOUR = 8
    private const val DEFAULT_REMINDER_MINUTE = 0

    // ============================================================
    // СИНХРОННЫЕ ОБЁРТКИ (для BootReceiver / BaseApplication)
    // ============================================================

    fun rescheduleAll(context: Context) {
        try {
            if (!TaskReminderPreferences.isEnabled(context)) {
                Logger.log(TAG, "rescheduleAll: reminders disabled, skip")
                return
            }
            val tasks = readAllTasksBlockingFromIo(context)
            doRescheduleAll(context, tasks)
        } catch (e: Exception) {
            Logger.log(TAG, "rescheduleAll error: ${e.message}")
        }
    }

    suspend fun rescheduleAllSuspend(context: Context) {
        try {
            if (!TaskReminderPreferences.isEnabled(context)) {
                Logger.log(TAG, "rescheduleAllSuspend: reminders disabled, skip")
                return
            }
            val tasks = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(context).taskDao().getAllTasksBlocking()
            }
            doRescheduleAll(context, tasks)
        } catch (e: Exception) {
            Logger.log(TAG, "rescheduleAllSuspend error: ${e.message}")
        }
    }

    fun cancelAll(context: Context) {
        try {
            val tasks = readAllTasksBlockingFromIo(context)
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

    suspend fun cancelAllSuspend(context: Context) {
        try {
            val tasks = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(context).taskDao().getAllTasksBlocking()
            }
            var cancelled = 0
            for (task in tasks) {
                cancel(context, task.id)
                cancelled++
            }
            Logger.log(TAG, "cancelAllSuspend: cancelled=$cancelled")
        } catch (e: Exception) {
            Logger.log(TAG, "cancelAllSuspend error: ${e.message}")
        }
    }

    // ============================================================
    // ПЛАНИРОВАНИЕ ОДНОЙ ЗАДАЧИ
    // ============================================================

    fun schedule(context: Context, task: TaskEntity) {
        try {
            cancelExact(context, task.id)
            cancelSmart(context, task.id)

            if (!TaskReminderPreferences.isEnabled(context)) {
                Logger.log(TAG, "schedule(${task.id}): reminders disabled, skip")
                return
            }

            if (task.isDone || task.isDeleted) {
                Logger.log(TAG, "schedule(${task.id}): task done/deleted, skip")
                return
            }

            val now = System.currentTimeMillis()

            // ── ПУТЬ 2a: точный remindAt ──
            val remindAt = task.remindAt
            if (remindAt != null && remindAt > now) {
                scheduleExact(context, task.id, remindAt, "remindAt")
                return
            }

            // ── ПУТЬ 2b: из dueDate (за N минут до) ──
            val dueDate = task.dueDate
            if (remindAt == null && dueDate != null) {
                val triggerAt = dueDate - DUE_SOON_MINUTES * 60_000L
                if (triggerAt > now) {
                    scheduleExact(context, task.id, triggerAt, "dueDate-$DUE_SOON_MINUTES min")
                    return
                }
                // dueDate в прошлом — уходим в умный пинг (если включён).
            }

            // ── ПУТЬ 3: умный пинг ──
            if (task.autoRemindEnabled) {
                val nextSmartAt = computeNextSmartPingAt(task, now)
                if (nextSmartAt > now) {
                    scheduleSmart(context, task.id, nextSmartAt)
                    return
                }
            }

            Logger.log(TAG, "schedule(${task.id}): no remindAt, no dueDate, autoRemind off — silent")
        } catch (e: Exception) {
            Logger.log(TAG, "schedule(${task.id}) error: ${e.message}")
        }
    }

    fun cancel(context: Context, taskId: String) {
        cancelExact(context, taskId)
        cancelSmart(context, taskId)
    }

    // ============================================================
    // ТОЧНЫЙ ALARM
    // ============================================================

    private fun scheduleExact(context: Context, taskId: String, triggerAt: Long, reason: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, taskId, smart = false)

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
                "scheduleExact($taskId): exact alarm at ${formatTime(triggerAt)} ($reason)"
            )
        } else {
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
                "scheduleExact($taskId): INEXACT alarm (no exact permission) at ${formatTime(triggerAt)} ($reason)"
            )
        }
    }

    private fun cancelExact(context: Context, taskId: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = buildPendingIntent(context, taskId, smart = false)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        } catch (e: Exception) {
            Logger.log(TAG, "cancelExact($taskId) error: ${e.message}")
        }
    }

    // ============================================================
    // УМНЫЙ ALARM (Путь 3)
    // ============================================================

    private fun scheduleSmart(context: Context, taskId: String, triggerAt: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, taskId, smart = true)

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
        } else {
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
        }
        Logger.log(
            TAG,
            "scheduleSmart($taskId): smart ping at ${formatTime(triggerAt)}"
        )
    }

    private fun cancelSmart(context: Context, taskId: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = buildPendingIntent(context, taskId, smart = true)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        } catch (e: Exception) {
            Logger.log(TAG, "cancelSmart($taskId) error: ${e.message}")
        }
    }

    // ============================================================
    // РАСЧЁТ УМНОГО ПИНГА
    // ============================================================

    /**
     * 🆕 v20.1.0 (Вариант A): без чтения TaskReminderPreferences.
     * Время суток — фиксированное DEFAULT_REMINDER_HOUR:MINUTE (08:00).
     */
    private fun computeNextSmartPingAt(task: TaskEntity, now: Long): Long {
        val isOverdue = task.dueDate != null && task.dueDate < now

        val intervalDays = if (isOverdue) {
            1
        } else {
            when (task.priority) {
                TaskEntity.PRIORITY_URGENT, TaskEntity.PRIORITY_HIGH -> 1
                TaskEntity.PRIORITY_NORMAL -> 2
                else -> 3
            }
        }

        val baseAt = task.createdAt
        val intervalMs = intervalDays * 24L * 60L * 60L * 1000L

        var candidate = alignToFixedMorning(baseAt + intervalMs)
        while (candidate <= now) {
            candidate = alignToFixedMorning(candidate + intervalMs)
        }
        return candidate
    }

    /**
     * Приводит timestamp к фиксированному утру (08:00) в тот же день.
     */
    private fun alignToFixedMorning(ts: Long): Long {
        val src = Calendar.getInstance().apply { timeInMillis = ts }
        val target = Calendar.getInstance().apply {
            set(Calendar.YEAR, src.get(Calendar.YEAR))
            set(Calendar.MONTH, src.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, src.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, DEFAULT_REMINDER_HOUR)
            set(Calendar.MINUTE, DEFAULT_REMINDER_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return target.timeInMillis
    }

    // ============================================================
    // ВНУТРЕННЕЕ
    // ============================================================

    private fun readAllTasksBlockingFromIo(context: Context): List<TaskEntity> {
        return runBlocking(Dispatchers.IO) {
            AppDatabase.getInstance(context).taskDao().getAllTasksBlocking()
        }
    }

    private fun doRescheduleAll(context: Context, tasks: List<TaskEntity>) {
        var scheduled = 0
        var skipped = 0
        for (task in tasks) {
            if (task.isDone || task.isDeleted) {
                skipped++
                continue
            }
            schedule(context, task)
            scheduled++
        }
        Logger.log(TAG, "rescheduleAll: scheduled=$scheduled, skipped=$skipped")
    }

    private fun buildPendingIntent(context: Context, taskId: String, smart: Boolean): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = TaskReminderReceiver.ACTION_REMIND
            putExtra(TaskReminderReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskReminderReceiver.EXTRA_IS_SMART, smart)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val requestCode = if (smart) taskId.hashCode() + SMART_REQUEST_OFFSET else taskId.hashCode()
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

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
