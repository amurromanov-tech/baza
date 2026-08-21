package com.family.base.data

import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.SettingsEntity

data class BackupData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val folders: List<FolderEntity> = emptyList(),
    val items: List<ItemEntity> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val settings: SettingsEntity? = null,
    val folderName: String = "BAZA"
)
