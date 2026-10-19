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
 * 🆕 v18.2.0 (Шаг 1): ФИКС ПАДЕНИЯ НА ГЛАВНОМ ПОТОКЕ.
 *
 * Было: rescheduleAll()/cancelAll() вызывали getAllTasksBlocking() прямо
 * в вызывающем потоке. Из BaseApplication.onCreate и BootReceiver.onReceive
 * это главный поток → Room бросал IllegalStateException
 * "Cannot access database on the main thread", ловился в try/catch,
 * и НИ ОДНА задача не планировалась. Отсюда «утром ничего не пришло».
 *
 * Стало:
 *  - чтение задач вынесено в runBlocking(Dispatchers.IO) внутри
 *    suspend-версий rescheduleAllSuspend()/cancelAllSuspend();
 *  - старые синхронные rescheduleAll()/cancelAll() оставлены как обёртки
 *    для обратной совместимости (BootReceiver, BaseApplication),
 *    но внутри они тоже читают БД в IO-потоке.
 *
 * 🆕 v18.2.0: задачи без dueDate БОЛЬШЕ НЕ отменяются молча.
 * Раньше schedule() при dueDate == null вызывал cancel() — это
 * скрывало проблему и мешало будущему Пути 2 (remindAt). Теперь
 * при dueDate == null просто пропускаем (alarm не ставим и не снимаем),
 * а cancel() вызывается только при явных условиях (done/deleted/disabled).
 *
 * ⚠️ Путь 2 (remindAt) и Путь 3 (autoRemind) будут добавлены на Шагах 3–4.
 * Здесь пока только точная дата из dueDate — как было, но без падений.
 */
object TaskReminderScheduler {

    private const val TAG = "TaskReminderScheduler"

    // ============================================================
    // СИНХРОННЫЕ ОБЁРТКИ (для BootReceiver / BaseApplication)
    // ============================================================

    /**
     * Перепланировать все активные задачи.
     *
     * Синхронная обёртка: читает БД в IO-потоке через runBlocking,
     * чтобы не упасть на главном потоке (BootReceiver.onReceive,
     * BaseApplication.onCreate).
     *
     * Если можешь вызвать suspend-версию (rescheduleAllSuspend) —
     * лучше вызывай её из корутины, без runBlocking.
     */
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

    /**
     * Suspend-версия rescheduleAll. Предпочтительна, если вызывающий код
     * уже в корутине (applicationScope.launch { }).
     */
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

    /**
     * Снять alarms для ВСЕХ задач (когда пользователь выключает напоминания).
     *
     * Синхронная обёртка: чтение БД — в IO-потоке.
     */
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

    /**
     * Suspend-версия cancelAll.
     */
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

    /**
     * Поставить (или переставить) alarm для конкретной задачи.
     *
     * Условия, при которых alarm НЕ ставится:
     *  - напоминания выключены глобально → cancel (убираем устаревший);
     *  - задача уже выполнена или удалена → cancel;
     *  - у задачи нет dueDate → ПРОПУСКАЕМ (не cancel! см. ниже);
     *  - вычисленное время в прошлом → cancel.
     *
     * 🆕 Почему при dueDate == null теперь НЕ cancel:
     * Раньше cancel() скрывал проблему — задачи без даты молча
     * оставались без напоминаний, и это выглядело как «баг AlarmManager».
     * Теперь мы просто не трогаем alarm: если он был поставлен ранее
     * (например, из remindAt на будущем Шаге 2) — он останется.
     * Если его не было — его и не будет, но это осознанно.
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
                // 🆕 НЕ cancel — просто пропускаем. На Шагах 3–4 здесь
                // подключится remindAt / autoRemind.
                Logger.log(TAG, "schedule(${task.id}): no dueDate, skip (Path 2/3 not implemented yet)")
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

    // ============================================================
    // ВНУТРЕННЕЕ
    // ============================================================

    /**
     * Читает ВСЕ задачи из БД в IO-потоке через runBlocking.
     * Нужна только для синхронных обёрток (BootReceiver, BaseApplication).
     *
     * runBlocking здесь безопасен: вызывающий код уже не главный поток
     * (мы внутри runBlocking переключились на Dispatchers.IO).
     */
    private fun readAllTasksBlockingFromIo(context: Context): List<TaskEntity> {
        return runBlocking(Dispatchers.IO) {
            AppDatabase.getInstance(context).taskDao().getAllTasksBlocking()
        }
    }

    /**
     * Общая логика перепланирования: проходим по всем задачам,
     * планируем/пропускаем/снимаем. Вынесено, чтобы не дублировать
     * между rescheduleAll() и rescheduleAllSuspend().
     */
    private fun doRescheduleAll(context: Context, tasks: List<TaskEntity>) {
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
    }

    /**
     * Вычисляет точное время срабатывания alarm:
     * берёт дату из dueDate (год/месяц/день) и подставляет час:минуту
     * из TaskReminderPreferences (глобальная настройка «во сколько напоминать»).
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
