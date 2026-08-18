package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locks")
data class LockEntity(
    @PrimaryKey val objectId: String,        // ID папки или предмета
    val objectType: String,                  // "folder" или "item"
    val lockedBy: String,                    // email пользователя
    val lockedByDisplayName: String,         // имя для отображения
    val lockedAt: Long,                      // время создания блокировки
    val expiresAt: Long                      // время истечения (lockedAt + 10 минут)
)
