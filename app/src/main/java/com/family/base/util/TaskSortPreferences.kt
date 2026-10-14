package com.family.base.util

import android.content.Context

/**
 * Хранит выбранный режим сортировки задач «Домашние дела».
 *
 * Хранится в SharedPreferences (отдельный файл task_sort_prefs.xml),
 * потому что режим — это UI-настройка, а не данные проекта:
 * синхронизировать между устройствами её не нужно.
 *
 * Если ключ в SharedPreferences отсутствует — используется
 * «режим по умолчанию»: TaskListActivity отдаёт список в том
 * порядке, как его вернул TaskDao без пересортировки.
 */
object TaskSortPreferences {

    private const val PREFS_NAME = "task_sort_prefs"
    private const val KEY_SORT_MODE = "task_sort_mode"

    /**
     * Режим сортировки.
     *  - DEFAULT   — как отдаёт DAO (кнопка «По умолчанию»);
     *  - DATE_DESC — дата создания, новые сверху;
     *  - DATE_ASC  — дата создания, старые сверху;
     *  - NAME_ASC  — название, А→Я;
     *  - NAME_DESC — название, Я→А.
     */
    enum class Mode {
        DEFAULT,
        DATE_DESC,
        DATE_ASC,
        NAME_ASC,
        NAME_DESC
    }

    /**
     * Возвращает текущий сохранённый режим.
     * Если ничего не сохранено — DEFAULT.
     */
    fun getMode(context: Context): Mode {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SORT_MODE, null) ?: return Mode.DEFAULT
        return try {
            Mode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            Mode.DEFAULT
        }
    }

    /**
     * Сохраняет выбранный режим.
     * Для Mode.DEFAULT ключ УДАЛЯЕТСЯ — это и есть «сброс к дефолту».
     */
    fun setMode(context: Context, mode: Mode) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (mode == Mode.DEFAULT) {
            prefs.edit().remove(KEY_SORT_MODE).apply()
        } else {
            prefs.edit().putString(KEY_SORT_MODE, mode.name).apply()
        }
    }

    /**
     * Сброс к дефолту (эквивалент setMode(DEFAULT)).
     * Оставлено отдельным методом для читаемости вызова.
     */
    fun reset(context: Context) {
        setMode(context, Mode.DEFAULT)
    }
}
