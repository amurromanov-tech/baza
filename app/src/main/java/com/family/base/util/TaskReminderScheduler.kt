package com.family.base.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Планировщик напоминаний о задачах.
 *
 * Две задачи:
 *  1. Periodic — раз в сутки в указанный час (по умолчанию 9:00).
 *  2. OneTime — при старте приложения (с задержкой 5 сек, если давно не проверяли).
 *
 * Использование:
 *  - TaskReminderScheduler.schedule(context)         — при старте приложения
 *  - TaskReminderScheduler.reschedule(context)       — при изменении настроек
 *  - TaskReminderScheduler.cancel(context)           — при выключении напоминаний
 */
object TaskReminderScheduler {

    private const val TAG = "TaskReminderScheduler"
    private const val PERIODIC_WORK_NAME = "task_reminder_periodic"
    private const val ONESHOT_WORK_NAME = "task_reminder_oneshot"

    /**
     * Полное перепланирование: отменяет старое, ставит новое.
     * Вызывается при старте приложения и при изменении настроек.
     */
    fun schedule(context: Context) {
        val prefs = TaskReminderPreferences(context)
        if (!prefs.isEnabled) {
            Logger.log(TAG, "Reminders disabled — cancel all")
            cancel(context)
            return
        }

        schedulePeriodic(context, prefs.hour)
        scheduleOneShotIfNeeded(context)
    }

    /**
     * Только периодическая задача — раз в сутки в указанный час.
     */
    fun reschedule(context: Context) {
        schedule(context)
    }

    /**
     * Отмена всех задач.
     */
    fun cancel(context: Context) {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
            wm.cancelUniqueWork(ONESHOT_WORK_NAME)
            Logger.log(TAG, "Cancelled all reminder work")
        } catch (e: Exception) {
            Logger.log(TAG, "cancel error: ${e.message}")
        }
    }

    // ============================================================
    // ВНУТРЕННЕЕ
    // ============================================================

    private fun schedulePeriodic(context: Context, hour: Int) {
        try {
            val initialDelay = calculateInitialDelay(hour)
            Logger.log(
                TAG,
                "Scheduling periodic at hour=$hour, initialDelay=${initialDelay / 1000 / 60} min"
            )

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val request = PeriodicWorkRequestBuilder<TaskReminderWorker>(
                24, TimeUnit.HOURS
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .addTag(PERIODIC_WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } catch (e: Exception) {
            Logger.log(TAG, "schedulePeriodic error: ${e.message}")
        }
    }

    private fun scheduleOneShotIfNeeded(context: Context) {
        try {
            val prefs = TaskReminderPreferences(context)
            val now = System.currentTimeMillis()
            val minInterval = 20L * 60 * 60 * 1000

            if (now - prefs.lastCheck < minInterval) {
                Logger.log(TAG, "OneShot skipped — last check was recent")
                return
            }

            Logger.log(TAG, "Scheduling OneShot with 5 sec delay")

            val request = OneTimeWorkRequestBuilder<TaskReminderWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .addTag(ONESHOT_WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        } catch (e: Exception) {
            Logger.log(TAG, "scheduleOneShotIfNeeded error: ${e.message}")
        }
    }

    /**
     * Сколько миллисекунд до ближайшего указанного часа.
     * Если час уже прошёл сегодня — берём завтра.
     */
    private fun calculateInitialDelay(hour: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }
}
