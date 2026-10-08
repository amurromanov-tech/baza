package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val isFirstLaunch: Boolean = true,

    // ===== Поля, которые УЖЕ есть в БД (восстановлены) =====
    val notificationDaysBefore: Int = 3,
    val notificationHour: Int = 10,
    val enableNotifications: Boolean = true,

    /**
     * 🆕 v12: флаг разовой миграции истории изменений.
     * NOT NULL с дефолтом 0 — так его создала миграция MIGRATION_11_12.
     */
    val historyMigratedV12: Boolean = false
)
