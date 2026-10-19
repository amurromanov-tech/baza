package com.family.base.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 🆕 v18.1.2 (fix миграции 18→19): добавлены indices для
 * isDeleted и (deleteConfirmed, deletedAt).
 *
 * Миграция 18→19 создаёт индексы:
 *   CREATE INDEX index_folders_isDeleted ON folders(isDeleted);
 *   CREATE INDEX index_folders_deleteConfirmed_deletedAt ON folders(deleteConfirmed, deletedAt);
 *
 * Без @Entity(indices=...) Room ожидал indices=[], а в БД они есть —
 * валидация схемы падала с "Migration didn't properly handle: folders".
 * Теперь ожидаемые и фактические индексы совпадают.
 */
@Entity(
    tableName = "folders",
    indices = [
        Index(value = ["isDeleted"], name = "index_folders_isDeleted"),
        Index(
            value = ["deleteConfirmed", "deletedAt"],
            name = "index_folders_deleteConfirmed_deletedAt"
        )
    ]
)
data class FolderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,

    /**
     * Родитель-ПАПКА.
     * NULL — если папка корневая или вложена в предмет (см. parentItemId).
     */
    val parentId: String? = null,

    /**
     * Родитель-ПРЕДМЕТ (вложенность папки в предмет).
     * NULL — если папка не вложена в предмет.
     * Если не NULL — папка находится «внутри» предмета-родителя.
     *
     * ИНВАРИАНТ: ровно одно из (parentId, parentItemId) может быть не NULL.
     *  - parentId != null, parentItemId == null → папка внутри папки
     *  - parentId == null, parentItemId != null → папка внутри предмета
     *  - оба null → корневая папка
     */
    val parentItemId: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val createdBy: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val path: String,
    val iconUrl: String? = null,
    val iconThumbnailUrl: String? = null,

    // ===== СИСТЕМНАЯ ПАПКА =====

    /**
     * Является ли папка системной.
     * Системные папки нельзя удалять, перемещать, переименовывать,
     * архивировать. В UI скрываются соответствующие кнопки.
     *
     * Примеры: «🏠 Домашние дела», «🛒 Покупки», «🛒 Список покупок».
     */
    val isSystem: Boolean = false,

    /**
     * Ключ системной папки.
     * NULL — для обычных папок.
     * "home_tasks"    — для папки «🏠 Домашние дела».
     * "purchases"     — для папки «🛒 Покупки» (чеки).
     * "shopping_list" — для папки «🛒 Список покупок».
     *
     * Используется для поиска системной папки при старте приложения
     * (upsert, если её нет) и для перехвата клика в MainActivity.
     */
    val systemKey: String? = null,

    // ===== СИНХРОНИЗАЦИЯ (v15) =====
    /**
     * Статус синхронизации записи.
     *  - "synced"  — запись синхронизирована с Яндекс.Диском.
     *  - "pending" — запись изменена локально, ждёт отправки в changelog.
     *  - "conflict" — конфликт при merge (задел на будущее, сейчас не используется).
     *
     * 🆕 v18.1.1 (fix миграции 18→19): defaultValue = "synced" —
     * чтобы схема Entity совпадала с фактической схемой БД,
     * созданной миграцией v14→v15 (ALTER TABLE ... DEFAULT 'synced').
     */
    @ColumnInfo(defaultValue = "synced")
    val syncStatus: String = SYNC_STATUS_SYNCED,

    // ===== TOMBSTONE (v19, №10) =====
    /**
     * Soft-delete для синка.
     * Удалённые папки помечаются true и синкаются через changelog,
     * чтобы удаление разошлось по всем устройствам и не «воскресло»
     * при merge со старой версией с Диска.
     *
     * В UI такие папки не показываются.
     * Системные папки (isSystem = true) удалять нельзя — tombstone
     * для них не ставится никогда.
     *
     * 🆕 v18.1.1 (fix миграции 18→19): defaultValue = "0".
     */
    @ColumnInfo(defaultValue = "0")
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
     * 🆕 v18.1.1 (fix миграции 18→19): defaultValue = "0".
     */
    @ColumnInfo(defaultValue = "0")
    val deleteConfirmed: Boolean = false
) {
    companion object {
        // ===== «🏠 Домашние дела» =====
        const val SYSTEM_KEY_HOME_TASKS = "home_tasks"

        /**
         * Фиксированный id системной папки «Домашние дела».
         * НЕ UUID — чтобы на всех устройствах это была одна и та же папка
         * и синк через folders.json не создавал дубликатов.
         */
        const val SYSTEM_ID_HOME_TASKS = "system-folder-home-tasks"

        // ===== «🛒 Покупки» =====
        const val SYSTEM_KEY_PURCHASES = "purchases"
        const val SYSTEM_ID_PURCHASES = "system-folder-purchases"

        // ===== «🛒 Список покупок» =====
        const val SYSTEM_KEY_SHOPPING_LIST = "shopping_list"
        const val SYSTEM_ID_SHOPPING_LIST = "system-folder-shopping-list"

        const val SYNC_STATUS_SYNCED = "synced"
        const val SYNC_STATUS_PENDING = "pending"
        const val SYNC_STATUS_CONFLICT = "conflict"
    }
}
