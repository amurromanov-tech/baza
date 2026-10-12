package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Очередь исходящих изменений для синхронизации через changelog.
 *
 * Каждое локальное изменение Item / Folder / Task кладёт сюда запись.
 * SyncManager читает несинхронизированные записи, формирует
 * JSON-пакет changelog и отправляет на Яндекс.Диск.
 *
 * После успешной отправки — record удаляется или помечается isSynced = true.
 *
 * Отличие от SyncQueueEntity:
 *  - SyncQueueEntity — старая очередь для полного upload (folders.json/items.json).
 *  - OutboxEntity  — новая очередь для дельта-синка (changelog).
 *  Старая очередь сохраняется для обратной совместимости.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["isSynced"]),
        Index(value = ["entityType", "entityId"]),
        Index(value = ["createdAt"])
    ]
)
data class OutboxEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),

    /**
     * Тип сущности:
     *  - "item"
     *  - "folder"
     *  - "task"
     */
    val entityType: String,

    /**
     * ID изменённой сущности (item.id / folder.id / task.id).
     * Для дальнейшего поиска и возможного схлопывания операций.
     */
    val entityId: String,

    /**
     * Действие:
     *  - "create"  — создание
     *  - "update"  — изменение
     *  - "delete"  — удаление (обычно soft-delete, isDeleted = true)
     */
    val action: String,

    /**
     * Полный JSON сущности на момент изменения.
     * Для delete — JSON может быть пустым или содержать только id.
     * Хранится, чтобы SyncManager мог сформировать пакет без
     * повторного обращения к БД по каждой записи.
     */
    val payloadJson: String,

    /**
     * Время изменения (millis).
     * Используется для разрешения конфликтов по updatedAt.
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Кто изменил (AppUser.name).
     */
    val changedBy: String,

    /**
     * Отправлено ли изменение в changelog.
     * false — ждёт отправки.
     * true  — успешно отправлено (можно чистить).
     */
    val isSynced: Boolean = false,

    /**
     * Время успешной отправки (millis).
     * NULL — если ещё не отправлено.
     */
    val syncedAt: Long? = null,

    /**
     * ID пакета changelog, в который попало это изменение.
     * NULL — если ещё не отправлено.
     * Формат: "{deviceId}_{timestamp}".
     */
    val batchId: String? = null
) {
    companion object {
        const val ENTITY_ITEM = "item"
        const val ENTITY_FOLDER = "folder"
        const val ENTITY_TASK = "task"

        const val ACTION_CREATE = "create"
        const val ACTION_UPDATE = "update"
        const val ACTION_DELETE = "delete"
    }
}
