package com.family.base.util

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Планировщик напоминаний о задачах через WorkManager.
 *
 * Использует PeriodicWorkRequest с интервалом 1 раз в сутки.
 * Время первого запуска вычисляется от текущего момента до заданного часа:минуты.
 *
 * Все методы обёрнуты в try/catch — если WorkManager по какой-то причине
 * недоступен, приложение не должно крашиться.
 */
object TaskReminderScheduler {

    private const val TAG = "TaskReminderScheduler"
    private const val WORK_NAME = "baza_task_reminder_daily"

    /**
     * Запустить (или перезапустить) ежедневное напоминание.
     * Если напоминания выключены — ничего не делает.
     */
    fun schedule(context: Context) {
        try {
            if (!TaskReminderPreferences.isEnabled(context)) {
                Logger.log(TAG, "schedule: reminders disabled, skip")
                return
            }

            val delayMs = computeInitialDelayMs(
                context,
                TaskReminderPreferences.getHour(context),
                TaskReminderPreferences.getMinute(context)
            )

            val request = PeriodicWorkRequestBuilder<TaskReminderWorker>(
                1, TimeUnit.DAYS
            )
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request
            )

            Logger.log(TAG, "schedule: enqueued, initial delay = ${delayMs / 1000 / 60} min")
        } catch (e: Exception) {
            Logger.log(TAG, "schedule error: ${e.message}")
        }
    }

    /**
     * Отменить запланированное напоминание.
     */
    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Logger.log(TAG, "cancel: done")
        } catch (e: Exception) {
            Logger.log(TAG, "cancel error: ${e.message}")
        }
    }

    /**
     * Перепланировать: отменить и заново запустить с новыми настройками.
     */
    fun reschedule(context: Context) {
        try {
            cancel(context)
            schedule(context)
        } catch (e: Exception) {
            Logger.log(TAG, "reschedule error: ${e.message}")
        }
    }

    /**
     * Вычислить задержку в миллисекундах от текущего момента
     * до ближайшего наступления указанного часа:минуты.
     *
     * Если время уже прошло сегодня — берём завтра.
     */
    private fun computeInitialDelayMs(context: Context, hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }
}
