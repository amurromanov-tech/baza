package com.family.base.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Задача для системной папки «🏠 Домашние дела».
 *
 * Отдельная сущность (не ItemEntity), потому что у задачи нет
 * количества, срока годности, цены, штрих-кода, фото, ревизии,
 * архивации. Зато есть приоритет, дедлайн, повтор, статус выполнения.
 *
 * Синхронизируется через общий changelog (v15), наравне с items и folders.
 *
 * 🆕 v18.1.2 (fix миграции 18→19): добавлены indices для
 * isDeleted и (deleteConfirmed, deletedAt).
 *
 * 🆕 v18.2.0 (шаг 2, миграция 19→20): Путь 2 + Путь 3 напоминаний.
 *  - remindAt          — точный момент напоминания (Путь 2). NULL = нет точного.
 *  - autoRemindEnabled — умный пинг «пока не сделаю» (Путь 3). По умолчанию true.
 *
 * Семантика напоминаний (приоритет в TaskReminderScheduler):
 *  1) remindAt задан и ещё не сработал → точный пинг в remindAt;
 *  2) remindAt нет, но dueDate есть → пинг в dueDate - N минут (дефолт из дедлайна);
 *  3) ни remindAt, ни dueDate нет, но autoRemindEnabled = true → умный пинг
 *     по расписанию (важные — ежедневно, обычные — раз в 2 дня);
 *  4) autoRemindEnabled = false и точных пингов нет → задача молчит.
 *
 * ⚠️ Миграция 19→20 добавляет:
 *   ALTER TABLE tasks ADD COLUMN remindAt INTEGER                   (nullable, без DEFAULT)
 *   ALTER TABLE tasks ADD COLUMN autoRemindEnabled INTEGER NOT NULL DEFAULT 1
 *
 * Поэтому:
 *  - remindAt — БЕЗ @ColumnInfo(defaultValue): в SQL колонка nullable без DEFAULT;
 *  - autoRemindEnabled — С @ColumnInfo(defaultValue = "1"): в SQL DEFAULT 1.
 * Иначе Room снова разойдётся по схеме (инцидент 18.1.0–18.1.2, правило №34).
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["isDeleted"], name = "index_tasks_isDeleted"),
        Index(
            value = ["deleteConfirmed", "deletedAt"],
            name = "index_tasks_deleteConfirmed_deletedAt"
        ),
        // 🆕 v18.2.0 (шаг 2): индекс под выборку задач для умного пинга
        // и под точные напоминания. Миграция 19→20 создаёт:
        //   CREATE INDEX index_tasks_remindAt ON tasks(remindAt);
        //   CREATE INDEX index_tasks_autoRemindEnabled_isDone_isDeleted
        //     ON tasks(autoRemindEnabled, isDone, isDeleted);
        Index(value = ["remindAt"], name = "index_tasks_remindAt"),
        Index(
            value = ["autoRemindEnabled", "isDone", "isDeleted"],
            name = "index_tasks_autoRemindEnabled_isDone_isDeleted"
        )
    ]
)
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
     * Используется Пути 3 для расчёта «возраста» задачи:
     * умный пинг считается от createdAt, а не от dueDate.
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

    // ===== НАПОМИНАНИЯ (v18.2.0, шаг 2: Путь 2 + Путь 3) =====

    /**
     * 🆕 v18.2.0 (Путь 2): точный момент напоминания (timestamp, millis).
     *
     * NULL — точного пинга нет. Тогда scheduler пробует:
     *  - dueDate (если есть) — пинг за N минут до;
     *  - autoRemindEnabled (Путь 3) — умный пинг по возрасту задачи.
     *
     * ⚠️ БЕЗ @ColumnInfo(defaultValue): миграция 19→20 добавляет колонку
     * как nullable без DEFAULT. Если поставить defaultValue — Room
     * снова разойдётся по схеме (правило №34).
     */
    val remindAt: Long? = null,

    /**
     * 🆕 v18.2.0 (Путь 3): включён ли умный пинг «пока не сделаю».
     *
     * true  — если точный пинг не сработал / не задан, scheduler периодически
     *         напоминает о задаче: важные — ежедневно, обычные — раз в 2 дня,
     *         просроченные — ежедневно. Пинг считается от createdAt.
     * false — задача молчит, если нет точного remindAt и dueDate.
     *
     * По умолчанию true: даже задача без даты получит хотя бы один пинг.
     *
     * 🆕 Миграция 19→20: ALTER TABLE tasks ADD COLUMN autoRemindEnabled INTEGER NOT NULL DEFAULT 1.
     * Поэтому @ColumnInfo(defaultValue = "1") — иначе Room снова разойдётся.
     */
    @ColumnInfo(defaultValue = "1")
    val autoRemindEnabled: Boolean = true,

    // ===== TOMBSTONE (v19, №10) =====
    /**
     * Soft-delete для синка.
     * Удалённые задачи помечаются true и синкаются через changelog,
     * чтобы удаление разошлось по всем устройствам и не «воскресло»
     * при merge со старой версией с Диска.
     *
     * В UI такие задачи не показываются.
     *
     * ⚠️ v18.1.1 (fix миграции 18→19): НЕ ставим @ColumnInfo(defaultValue = "0")!
     * Эта колонка была создана ещё в миграции v13→v14 через
     * CREATE TABLE tasks (... isDeleted INTEGER NOT NULL, ...) — без DEFAULT.
     * В схеме БД defaultValue = 'undefined', и Room ждёт то же.
     * Если поставить defaultValue = "0" — снова будет расхождение,
     * и БД не откроется.
     */
    val isDeleted: Boolean = false,

    /**
     * Когда удалена (timestamp, millis).
     * NULL — если ещё не удалена.
     */
    val deletedAt: Long? = null,

    /**
     * Кто удалил (AppUser.name).
     * NULL — если ещё не удалена.
     */
    val deletedBy: String? = null,

    /**
     * Подтверждён ли tombstone.
     * false — tombstone ещё не разошёлся через changelog.
     * true  — PUSH в changelog прошёл успешно; через 1 год cleanup
     *         может физически удалить запись из базы.
     *
     * 🆕 v18.1.1 (fix миграции 18→19): defaultValue = "0" —
     * миграция добавила колонку через
     * ALTER TABLE tasks ADD COLUMN deleteConfirmed INTEGER NOT NULL DEFAULT 0.
     */
    @ColumnInfo(defaultValue = "0")
    val deleteConfirmed: Boolean = false,

    // ===== СИНХРОНИЗАЦИЯ (v15) =====
    /**
     * Статус синхронизации записи.
     *  - "synced"  — запись синхронизирована с Яндекс.Диском.
     *  - "pending" — запись изменена локально, ждёт отправки в changelog.
     *  - "conflict" — конфликт при merge (задел на будущее, сейчас не используется).
     *
     * 🆕 v18.1.1 (fix миграции 18→19): defaultValue = "synced" —
     * миграция v14→v15 добавила колонку через
     * ALTER TABLE tasks ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'.
     */
    @ColumnInfo(defaultValue = "synced")
    val syncStatus: String = SYNC_STATUS_SYNCED
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

        const val SYNC_STATUS_SYNCED = "synced"
        const val SYNC_STATUS_PENDING = "pending"
        const val SYNC_STATUS_CONFLICT = "conflict"
    }
}
