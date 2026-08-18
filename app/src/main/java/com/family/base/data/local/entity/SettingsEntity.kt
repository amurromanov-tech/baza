package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val notificationDaysBefore: Int = 1,
    val notificationHour: Int = 9,
    val enableNotifications: Boolean = true,
    val isFirstLaunch: Boolean = true
)
