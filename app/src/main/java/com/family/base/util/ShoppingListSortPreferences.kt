package com.family.base.util

import android.content.Context

/**
 * Хранит выбранный режим сортировки «🛒 Списка покупок».
 *
 * Отдельный SharedPreferences-файл и отдельный ключ от TaskSortPreferences,
 * чтобы сортировка в «Домашних делах» и в «Списке покупок» была НЕЗАВИСИМОЙ.
 *
 * Хранится в SharedPreferences (отдельный файл shopping_list_sort_prefs.xml),
 * потому что режим — это UI-настройка, а не данные проекта.
 *
 * Если ключ отсутствует — используется DEFAULT:
 * ShoppingListViewModel отдаёт список в порядке
 * «toBuy items + bought items» (оба — по name COLLATE NOCASE ASC).
 */
object ShoppingListSortPreferences {

    private const val PREFS_NAME = "shopping_list_sort_prefs"
    private const val KEY_SORT_MODE = "shopping_list_sort_mode"

    /**
     * Режим сортировки.
     *  - DEFAULT   — как отдаёт ViewModel;
     *  - DATE_DESC — дата добавления, новые сверху;
     *  - DATE_ASC  — дата добавления, старые сверху;
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

    fun reset(context: Context) {
        setMode(context, Mode.DEFAULT)
    }
}
