package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Задача для системной папки «🏠 Домашние дела».
 *
 * Отдельная сущность (не ItemEntity), потому что у задачи нет
 * количества, срока годности, цены, штрих-кода, фото, ревизии,
 * архивации. Зато есть приоритет, дедлайн, повтор, статус выполнения.
 *
 * Синхронизируется отдельным файлом tasks.json (merge-then-upload,
 * по аналогии с history.json).
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),

    /**
     * Название задачи. Обязательное.
     * Пример: «Купить хлеб», «Вынести мусор».
     */
    val title: String,

    /**
     * Опциональное примечание / подробности.
     */
    val note: String? = null,

    /**
     * Приоритет задачи.
     * 0 = LOW    🟢
     * 1 = NORMAL 🟡
     * 2 = HIGH   🟠
     * 3 = URGENT 🔴
     */
    val priority: Int = 1,

    /**
     * Дедлайн (timestamp, millis).
     * NULL — без дедлайна.
     */
    val dueDate: Long? = null,

    /**
     * Выполнена ли задача.
     * После true задача НЕ удаляется — остаётся в списке зачёркнутой.
     */
    val isDone: Boolean = false,

    /**
     * Когда выполнена (timestamp).
     */
    val doneAt: Long? = null,

    /**
     * Кто выполнил (AppUser.name).
     * NULL — если ещё не выполнена.
     */
    val doneBy: String? = null,

    /**
     * Кто создал задачу (AppUser.name).
     */
    val createdBy: String,

    /**
     * Когда создана (timestamp).
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Когда последний раз изменена (timestamp).
     * Используется для merge-синка: более новая версия побеждает.
     */
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * Повторяемость.
     * 0 = NONE    — не повторяется
     * 1 = DAILY   — ежедневно
     * 2 = WEEKLY  — еженедельно
     * 3 = MONTHLY — ежемесячно
     *
     * При отметке «выполнено» у повторяющейся задачи автоматически
     * создаётся новая задача-клон с dueDate + period.
     */
    val recurrence: Int = 0,

    /**
     * id родительской задачи, от которой создан этот клон
     * при повторе. NULL — если это оригинал.
     */
    val recurrenceSource: String? = null,

    /**
     * Ручная сортировка внутри списка.
     * Меньше — выше.
     */
    val sortOrder: Int = 0,

    /**
     * Soft-delete для синка.
     * Удалённые задачи помечаются true и синкаются,
     * чтобы удаление разошлось по всем устройствам.
     * В UI такие задачи не показываются.
     */
    val isDeleted: Boolean = false
) {
    companion object {
        const val PRIORITY_LOW = 0
        const val PRIORITY_NORMAL = 1
        const val PRIORITY_HIGH = 2
        const val PRIORITY_URGENT = 3

        const val RECURRENCE_NONE = 0
        const val RECURRENCE_DAILY = 1
        const val RECURRENCE_WEEKLY = 2
        const val RECURRENCE_MONTHLY = 3
    }
}
