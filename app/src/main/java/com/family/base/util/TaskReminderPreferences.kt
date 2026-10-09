package com.family.base.util

import android.content.Context

/**
 * Настройки напоминаний о задачах.
 * Хранятся в SharedPreferences (не в Room).
 *
 * Ключи:
 *  - enabled      — включены ли напоминания
 *  - hour         — во сколько часов утра проверять (по умолчанию 9:00)
 *  - daysBefore   — за сколько дней предупреждать (0 = в день дедлайна)
 */
class TaskReminderPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "task_reminder_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOUR = "hour"
        private const val KEY_DAYS_BEFORE = "days_before"
        private const val KEY_LAST_CHECK = "last_check"

        const val DEFAULT_HOUR = 9
        const val DEFAULT_DAYS_BEFORE = 0
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Час, в который запускается ежедневная проверка (0..23).
     */
    var hour: Int
        get() = prefs.getInt(KEY_HOUR, DEFAULT_HOUR)
        set(value) = prefs.edit().putInt(KEY_HOUR, value.coerceIn(0, 23)).apply()

    /**
     * За сколько дней до дедлайна предупреждать.
     * 0 — в день дедлайна.
     */
    var daysBefore: Int
        get() = prefs.getInt(KEY_DAYS_BEFORE, DEFAULT_DAYS_BEFORE)
        set(value) = prefs.edit().putInt(KEY_DAYS_BEFORE, value.coerceIn(0, 7)).apply()

    /**
     * Timestamp последней проверки — чтобы не спамить при каждом старте.
     */
    var lastCheck: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()
}
