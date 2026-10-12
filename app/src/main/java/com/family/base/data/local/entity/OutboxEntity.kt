package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Очередь исходящих изменений для синхронизации через changelog.
 *
 * Каждое локальное изменение Item / Folder / Task / History кладёт сюда запись.
 * SyncManager читает несинхронизированные записи, формирует
 * JSON-пакет changelog и отправляет на Яндекс.Диск.
 *
 * После успешной отправки — record помечается isSynced = true.
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
     *  - "history"  🆕 v16.2
     */
    val entityType: String,

    /**
     * ID изменённой сущности (item.id / folder.id / task.id / history.id).
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
        const val ENTITY_HISTORY = "history"  // 🆕 v16.2

        const val ACTION_CREATE = "create"
        const val ACTION_UPDATE = "update"
        const val ACTION_DELETE = "delete"
    }
}
