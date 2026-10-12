package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Список обработанных входящих пакетов changelog.
 *
 * Каждый пакет, скачанный с Диска и применённый к локальной БД,
 * фиксируется здесь по batchId. Это защищает от повторного применения
 * одного и того же пакета при следующем синке.
 *
 * Также используется, чтобы:
 *  - не обрабатывать свои же пакеты (по deviceId);
 *  - показывать историю синка (createdAt, entriesCount);
 *  - чистить очень старые записи (createdAt < N дней).
 */
@Entity(
    tableName = "processed_changelogs",
    indices = [
        Index(value = ["batchId"], unique = true),
        Index(value = ["deviceId"]),
        Index(value = ["processedAt"])
    ]
)
data class ProcessedChangelogEntity(
    /**
     * ID пакета: "{deviceId}_{createdAt}".
     * Уникален, первичный ключ.
     */
    @PrimaryKey val batchId: String,

    /**
     * deviceId устройства, с которого пришёл пакет.
     * Свои пакеты тоже записываем — чтобы не тянуть их обратно.
     */
    val deviceId: String,

    /**
     * Кто сформировал пакет (имя пользователя из batch.changedBy).
     */
    val changedBy: String?,

    /**
     * Когда пакет был создан на устройстве-источнике.
     */
    val createdAt: Long,

    /**
     * Когда мы его применили локально.
     */
    val processedAt: Long = System.currentTimeMillis(),

    /**
     * Сколько entries было в пакете (для отладки/статистики).
     */
    val entriesCount: Int = 0,

    /**
     * Сколько entries реально применено (без пропущенных конфликтов).
     */
    val appliedCount: Int = 0,

    /**
     * Флаг: свой ли это пакет (deviceId == наш deviceId).
     * Свои пакеты не применяются повторно, но и не удаляются
     * сразу — вдруг пригодится для отладки.
     */
    val isOwn: Boolean = false
)
