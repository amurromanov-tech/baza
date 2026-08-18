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
    val parentId: String?,         // родительская папка (для блокировки)
    val data: String?,             // JSON данных (для восстановления)
    val timestamp: Long = System.currentTimeMillis()
)
