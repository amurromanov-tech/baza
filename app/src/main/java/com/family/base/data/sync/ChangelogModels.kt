package com.family.base.data.sync

import com.google.gson.annotations.SerializedName

/**
 * Модель пакета changelog, который лежит на Яндекс.Диске:
 *   /BAZA/changelog/{deviceId}_{timestamp}.json
 *
 * Один пакет = пачка изменений с одного устройства за один цикл синка.
 *
 * Структура НЕ должна ломаться: schemaVersion = 1 фиксирует формат.
 * При будущих изменениях — либо добавляем опциональные поля,
 * либо поднимаем schemaVersion и учим парсер понимать обе версии.
 */
data class ChangelogBatch(
    @SerializedName("schemaVersion")
    val schemaVersion: Int = SCHEMA_VERSION,

    /**
     * Стабильный UUID устройства, сгенерированный DeviceIdProvider.
     * По нему сервер (Диск) различает, чьи пакеты.
     */
    @SerializedName("deviceId")
    val deviceId: String,

    /**
     * ID пакета. Формат: "{deviceId}_{createdAt}".
     * Используется как имя файла и как ключ в processed_changelogs.
     */
    @SerializedName("batchId")
    val batchId: String,

    /**
     * Время формирования пакета (millis).
     */
    @SerializedName("createdAt")
    val createdAt: Long,

    /**
     * Кто сформировал пакет (имя пользователя).
     * Для отладки; при применении не используется.
     */
    @SerializedName("changedBy")
    val changedBy: String,

    /**
     * Изменения в пакете.
     */
    @SerializedName("entries")
    val entries: List<ChangelogEntry>
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Одно изменение в пакете.
 */
data class ChangelogEntry(
    /**
     * Тип сущности: "item" | "folder" | "task".
     */
    @SerializedName("entityType")
    val entityType: String,

    /**
     * ID изменённой сущности.
     */
    @SerializedName("entityId")
    val entityId: String,

    /**
     * Действие: "create" | "update" | "delete".
     */
    @SerializedName("action")
    val action: String,

    /**
     * Полный JSON сущности на момент изменения.
     * Для delete — минимальный JSON с id (и, возможно, name).
     */
    @SerializedName("payloadJson")
    val payloadJson: String,

    /**
     * Когда произошло изменение (millis).
     * Используется для разрешения конфликтов по updatedAt.
     */
    @SerializedName("changedAt")
    val changedAt: Long,

    /**
     * Кто изменил (имя пользователя).
     */
    @SerializedName("changedBy")
    val changedBy: String
)
