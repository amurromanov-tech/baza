package com.family.base.util

import android.content.Context

/**
 * Простые настройки напоминаний о задачах.
 *
 * 🆕 v20.1.0 (Вариант A): упрощено.
 *  - Осталось ТОЛЬКО глобальное включение/выключение напоминаний
 *    (isEnabled / setEnabled).
 *  - Глобальное время суток (getHour / getMinute / setTime / getTimeLabel)
 *    БОЛЬШЕ НЕ ИСПОЛЬЗУЕТСЯ. Оставлены @Deprecated.
 *
 * 🆕 v20.2.3: добавлены настройки «Показывать сводку на сегодня при запуске».
 *
 * 🆕 v20.3.4: переделка показа окна «На сегодня».
 *  - Храним не дату-строку, а timestamp последнего показа (millis).
 *  - Окно показывается один раз в день, но не раньше TODAY_DIALOG_HOUR (07:00).
 *  - Логика: now >= сегодня 07:00 И lastShown < сегодня 07:00 → показать.
 *  - Старые getLastTodayDialogDate / setLastTodayDialogDate — @Deprecated.
 *
 * Хранится в SharedPreferences (baza_task_reminders).
 */
object TaskReminderPreferences {

    private const val PREFS_NAME = "baza_task_reminders"
    private const val KEY_ENABLED = "reminders_enabled"
    private const val KEY_HOUR = "reminder_hour"
    private const val KEY_MINUTE = "reminder_minute"

    // 🆕 v20.2.3 / v20.3.4: «Показывать сводку на сегодня при запуске»
    private const val KEY_TODAY_DIALOG_ENABLED = "today_dialog_enabled"
    private const val KEY_LAST_TODAY_DIALOG_TIMESTAMP = "last_today_dialog_timestamp"
    private const val KEY_LAST_TODAY_DIALOG_DATE_LEGACY = "last_today_dialog_date"

    /**
     * 🆕 v20.3.4: час, раньше которого окно «На сегодня» не показывается.
     * Открыл в 06:59 — не покажется. Открыл в 07:00 — покажется.
     */
    const val TODAY_DIALOG_HOUR = 7

    /**
     * 🆕 v20.3.4: минута (в паре с TODAY_DIALOG_HOUR).
     */
    const val TODAY_DIALOG_MINUTE = 0

    @Deprecated("Global reminder time removed in v20.1.0. Scheduler uses fixed 08:00.")
    const val DEFAULT_HOUR = 9

    @Deprecated("Global reminder time removed in v20.1.0. Scheduler uses fixed 08:00.")
    const val DEFAULT_MINUTE = 0

    // ============================================================
    // ГЛОБАЛЬНЫЙ ФЛАГ «НАПОМИНАНИЯ ВКЛЮЧЕНЫ»
    // ============================================================

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        Logger.log("TaskReminderPreferences", "setEnabled: $enabled")
    }

    // ============================================================
    // 🚫 УСТАРЕВШИЕ МЕТОДЫ (глобальное время суток)
    // ============================================================

    @Deprecated("Global reminder time removed in v20.1.0. Use fixed 08:00 in scheduler.")
    fun getHour(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_HOUR, DEFAULT_HOUR)
    }

    @Deprecated("Global reminder time removed in v20.1.0. Use fixed 08:00 in scheduler.")
    fun getMinute(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MINUTE, DEFAULT_MINUTE)
    }

    @Deprecated("Global reminder time removed in v20.1.0. No longer used by scheduler.")
    fun setTime(context: Context, hour: Int, minute: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, minute.coerceIn(0, 59))
            .apply()
        Logger.log("TaskReminderPreferences", "setTime (deprecated): $hour:$minute")
    }

    @Deprecated("Global reminder time removed in v20.1.0. No longer used by scheduler.")
    fun getTimeLabel(context: Context): String {
        val h = getHour(context).toString().padStart(2, '0')
        val m = getMinute(context).toString().padStart(2, '0')
        return "$h:$m"
    }

    // ============================================================
    // 🆕 v20.2.3 / v20.3.4: «ПОКАЗЫВАТЬ СВОДКУ НА СЕГОДНЯ»
    // ============================================================

    /**
     * 🆕 v20.2.3: включён ли показ окна «На сегодня» при запуске.
     * По умолчанию true.
     */
    fun isTodayDialogEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TODAY_DIALOG_ENABLED, true)
    }

    fun setTodayDialogEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_TODAY_DIALOG_ENABLED, enabled).apply()
        Logger.log("TaskReminderPreferences", "setTodayDialogEnabled: $enabled")
    }

    /**
     * 🆕 v20.3.4: timestamp (millis) последнего показа окна «На сегодня».
     * 0L — ещё не показывали.
     *
     * Логика в MainActivity:
     *   todayAt7 = сегодня 07:00
     *   if (now >= todayAt7 && lastShown < todayAt7) → показать
     */
    fun getLastTodayDialogTimestamp(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_TODAY_DIALOG_TIMESTAMP, 0L)
    }

    fun setLastTodayDialogTimestamp(context: Context, timestamp: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_TODAY_DIALOG_TIMESTAMP, timestamp).apply()
        Logger.log("TaskReminderPreferences", "setLastTodayDialogTimestamp: $timestamp")
    }

    /**
     * @deprecated v20.3.4 — заменено на getLastTodayDialogTimestamp(Long).
     * Оставлено для обратной совместимости чтения старых prefs.
     * Новый код использовать НЕ должен.
     */
    @Deprecated("Use getLastTodayDialogTimestamp(Long) instead.")
    fun getLastTodayDialogDate(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_TODAY_DIALOG_DATE_LEGACY, null)
    }

    /**
     * @deprecated v20.3.4 — заменено на setLastTodayDialogTimestamp(Long).
     */
    @Deprecated("Use setLastTodayDialogTimestamp(Long) instead.")
    fun setLastTodayDialogDate(context: Context, date: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_TODAY_DIALOG_DATE_LEGACY, date).apply()
        Logger.log("TaskReminderPreferences", "setLastTodayDialogDate (deprecated): $date")
    }
}
