package com.family.base.util

import android.content.Context

/**
 * Простые настройки напоминаний о задачах.
 *
 * 🆕 v20.1.0 (Вариант A): упрощено.
 *  - Осталось ТОЛЬКО глобальное включение/выключение напоминаний
 *    (isEnabled / setEnabled). Это единственный «глобальный» переключатель,
 *    который влияет на все задачи: если он выключен — ни одно напоминание
 *    не приходит.
 *  - Глобальное время суток (getHour / getMinute / setTime / getTimeLabel)
 *    БОЛЬШЕ НЕ ИСПОЛЬЗУЕТСЯ для планирования. Оно было источником путаницы:
 *    «меняю в одной задаче — меняется у всех». Теперь планировщик берёт
 *    фиксированное 08:00 (см. TaskReminderScheduler.DEFAULT_REMINDER_HOUR).
 *
 *  - Методы getHour/getMinute/setTime/getTimeLabel оставлены
 *    @Deprecated, чтобы старый код компилировался, но новые правки
 *    их не использовали. Их можно удалить отдельным шагом, когда
 *    убедимся, что нигде не вызываются.
 *
 * 🆕 v20.2.3: добавлены настройки «Показывать сводку на сегодня при запуске».
 *  - isTodayDialogEnabled / setTodayDialogEnabled — глобальный переключатель
 *    (по умолчанию true);
 *  - getLastTodayDialogDate / setLastTodayDialogDate — дата последнего
 *    показа окна (формат yyyy-MM-dd, локальная зона), чтобы показывать
 *    не чаще одного раза в день.
 *
 * Хранится в SharedPreferences (baza_task_reminders).
 */
object TaskReminderPreferences {

    private const val PREFS_NAME = "baza_task_reminders"
    private const val KEY_ENABLED = "reminders_enabled"
    private const val KEY_HOUR = "reminder_hour"
    private const val KEY_MINUTE = "reminder_minute"

    // 🆕 v20.2.3: «Показывать сводку на сегодня при запуске»
    private const val KEY_TODAY_DIALOG_ENABLED = "today_dialog_enabled"
    private const val KEY_LAST_TODAY_DIALOG_DATE = "last_today_dialog_date"

    /**
     * @deprecated v20.1.0 — время суток больше не глобальное.
     * Значение оставлено только для обратной совместимости чтения
     * старых SharedPreferences. Планировщик его НЕ читает.
     */
    @Deprecated("Global reminder time removed in v20.1.0. Scheduler uses fixed 08:00.")
    const val DEFAULT_HOUR = 9

    /**
     * @deprecated v20.1.0 — см. DEFAULT_HOUR.
     */
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
    //
    // Не использовать в новом коде. Оставлены, чтобы не ломать
    // существующие вызовы; при следующей чистке — удалить.
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
    // 🆕 v20.2.3: «ПОКАЗЫВАТЬ СВОДКУ НА СЕГОДНЯ ПРИ ЗАПУСКЕ»
    // ============================================================

    /**
     * 🆕 v20.2.3: включён ли показ окна «На сегодня» при запуске.
     * По умолчанию true — чтобы пользователь видел сводку.
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
     * 🆕 v20.2.3: дата последнего показа окна «На сегодня»
     * в формате yyyy-MM-dd (локальная зона). null — ещё не показывали.
     *
     * Нужна, чтобы показывать окно не чаще одного раза в день
     * при первом запуске.
     */
    fun getLastTodayDialogDate(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_TODAY_DIALOG_DATE, null)
    }

    fun setLastTodayDialogDate(context: Context, date: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_TODAY_DIALOG_DATE, date).apply()
        Logger.log("TaskReminderPreferences", "setLastTodayDialogDate: $date")
    }
}
