package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "folders")
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
     * Пример: «🏠 Домашние дела».
     */
    val isSystem: Boolean = false,

    /**
     * Ключ системной папки.
     * NULL — для обычных папок.
     * "home_tasks" — для папки «🏠 Домашние дела».
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
     * Значение по умолчанию после миграции v14 → v15 = "synced":
     * все существующие записи считаются уже синхронизированными,
     * чтобы первый запуск v15 не заливал на Диск всю базу.
     */
    val syncStatus: String = SYNC_STATUS_SYNCED
) {
    companion object {
        const val SYSTEM_KEY_HOME_TASKS = "home_tasks"

        /**
         * Фиксированный id системной папки «Домашние дела».
         * НЕ UUID — чтобы на всех устройствах это была одна и та же папка
         * и синк через folders.json не создавал дубликатов.
         */
        const val SYSTEM_ID_HOME_TASKS = "system-folder-home-tasks"

        const val SYNC_STATUS_SYNCED = "synced"
        const val SYNC_STATUS_PENDING = "pending"
        const val SYNC_STATUS_CONFLICT = "conflict"
    }
}
