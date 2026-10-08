package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val isFirstLaunch: Boolean = true,

    /**
     * 🆕 v12: флаг разовой миграции истории изменений.
     * После первого прогона migrateOldHistoryEntries() ставится в true.
     * Защищает от повторной обработки записей.
     */
    val historyMigratedV12: Boolean = false
)
