package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val entityType: String,        // "folder" или "item"
    val entityId: String,          // ID папки или предмета
    val action: String,            // "create", "update", "delete"

    /**
     * Родительская ПАПКА.
     * Используется для блокировки операций (нельзя переместить
     * папку в саму себя или в своего потомка).
     */
    val parentId: String?,

    /**
     * Родительский ПРЕДМЕТ (для вложенности).
     * NULL — если узел не вложен в предмет.
     *
     * Для полного upload (uploadAllItemsToDisk / uploadAllFoldersToDisk)
     * это поле не критично — сериализуется вся таблица.
     * Но нужно для:
     *   - будущей валидации связей;
     *   - чистоты очереди (видно, куда именно переместили узел);
     *   - возможности частичного применения (если решим).
     */
    val parentItemId: String? = null,

    val data: String?,             // JSON данных (для восстановления)
    val timestamp: Long = System.currentTimeMillis()
)
