package com.family.base.util

import android.content.Context

/**
 * Простые настройки напоминаний о задачах.
 *
 * Хранит в SharedPreferences:
 *  - включены ли напоминания вообще
 *  - час и минута, когда показывать уведомление (локальное время)
 *
 * Никаких разрешений не требует, никакой логики планирования — только чтение/запись.
 */
object TaskReminderPreferences {

    private const val PREFS_NAME = "baza_task_reminders"
    private const val KEY_ENABLED = "reminders_enabled"
    private const val KEY_HOUR = "reminder_hour"
    private const val KEY_MINUTE = "reminder_minute"

    const val DEFAULT_HOUR = 9
    const val DEFAULT_MINUTE = 0

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        Logger.log("TaskReminderPreferences", "setEnabled: $enabled")
    }

    fun getHour(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_HOUR, DEFAULT_HOUR)
    }

    fun getMinute(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MINUTE, DEFAULT_MINUTE)
    }

    fun setTime(context: Context, hour: Int, minute: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, minute.coerceIn(0, 59))
            .apply()
        Logger.log("TaskReminderPreferences", "setTime: $hour:$minute")
    }

    fun getTimeLabel(context: Context): String {
        val h = getHour(context).toString().padStart(2, '0')
        val m = getMinute(context).toString().padStart(2, '0')
        return "$h:$m"
    }
}
