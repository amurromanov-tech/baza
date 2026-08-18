package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val action: String, // "create", "update", "delete", "quantity_change"
    val oldValue: String? = null,
    val newValue: String? = null,
    val changedBy: String,
    val changedAt: Long = System.currentTimeMillis()
)