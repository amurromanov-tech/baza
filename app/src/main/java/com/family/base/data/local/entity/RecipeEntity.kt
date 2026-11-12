package com.family.base.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 🆕 v21.1.0 (миграция 21→22): РЕЦЕПТЫ.
 *
 * Одна таблица для двух источников:
 *  - source = "ai"   → сгенерированные в «Рецептах ИИ»;
 *  - source = "user" → созданные пользователем в «Моих рецептах».
 *
 * Одна таблица (а не две) — меньше кода, проще синк через changelog,
 * проще фильтр по source в UI. См. обсуждение в чате.
 *
 * МЕДИА (фото/видео) — НЕ здесь, а в отдельной таблице recipe_media
 * (RecipeMediaEntity). Причина: у одного рецепта может быть до ~100 фото
 * и до ~100 видео, и каждое грузится на Яндекс.Диск независимо.
 * Хранить JSON-массив в этой таблице — плохо для changelog (при
 * добавлении одного фото перезаливался бы весь рецепт + все файлы).
 *
 * ПОИСК/СОРТИРОВКА:
 *  - index_recipes_title        — быстрый поиск по названию (LIKE тоже работает);
 *  - index_recipes_createdAt    — сортировка «сначала новые»;
 *  - index_recipes_source_createdAt — фильтр по source + сортировка;
 *  - index_recipes_isDeleted    — фильтр tombstones;
 *  - index_recipes_deleteConfirmed_deletedAt — cleanup tombstones.
 *
 * ⚠️ Правило №34: схема @Entity должна точно совпадать с SQL в MIGRATION_21_22.
 *  - source: TEXT NOT NULL DEFAULT 'user' → @ColumnInfo(defaultValue = "user")
 *  - syncStatus: TEXT NOT NULL DEFAULT 'synced' → @ColumnInfo(defaultValue = "synced")
 *  - isDeleted: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 *  - deleteConfirmed: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 */
@Entity(
    tableName = "recipes",
    indices = [
        Index(value = ["title"], name = "index_recipes_title"),
        Index(value = ["createdAt"], name = "index_recipes_createdAt"),
        Index(value = ["source", "createdAt"], name = "index_recipes_source_createdAt"),
        Index(value = ["isDeleted"], name = "index_recipes_isDeleted"),
        Index(
            value = ["deleteConfirmed", "deletedAt"],
            name = "index_recipes_deleteConfirmed_deletedAt"
        )
    ]
)
data class RecipeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),

    /**
     * Название рецепта. Например: «Блины», «Борщ», «Шарлотка».
     * По нему же идёт поиск (LIKE '%q%' COLLATE NOCASE).
     */
    val title: String,

    /**
     * Описание / шаги / ингредиенты одним текстом.
     * Для «Рецептов ИИ» сюда кладётся структурированный текст,
     * который вернул прокси (он сам форматирует список ингредиентов
     * и шагов в один читаемый текст).
     * NULL — если пользователь создал рецепт без описания (например,
     * только фото). Такое допустимо.
     */
    val description: String? = null,

    /**
     * Источник рецепта:
     *  - "ai"   — сгенерирован в «Рецептах ИИ»;
     *  - "user" — создан в «Моих рецептах» вручную.
     *
     * Используется для фильтра: в «Рецептах ИИ» показываем только
     * source = "ai", в «Моих рецептах» — только source = "user".
     */
    @ColumnInfo(defaultValue = "user")
    val source: String = SOURCE_USER,

    val createdBy: String,
    val createdAt: Long = System.currentTimeMillis(),

    val updatedBy: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),

    // ===== СИНХРОНИЗАЦИЯ (v15, как у items/folders/tasks) =====
    /**
     * Статус синхронизации записи.
     *  - "synced"  — синхронизирована с Яндекс.Диском;
     *  - "pending" — изменена локально, ждёт отправки в changelog.
     *
     * ⚠️ defaultValue = "synced" обязателен: миграция создаёт колонку
     * с DEFAULT 'synced', Room валидирует схему и требует именно это.
     */
    @ColumnInfo(defaultValue = "synced")
    val syncStatus: String = SYNC_STATUS_SYNCED,

    // ===== TOMBSTONE (v19, как у items/folders/tasks) =====
    /**
     * Soft-delete для синка.
     * Удалённые рецепты помечаются true и уезжают через changelog,
     * чтобы удаление разошлось по всем устройствам и не «воскресло»
     * при merge со старой версией с Диска.
     *
     * В UI такие рецепты не показываются.
     */
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,

    val deletedAt: Long? = null,
    val deletedBy: String? = null,

    /**
     * Подтверждён ли tombstone (PUSH в changelog прошёл успешно).
     * Через год cleanup может физически удалить запись.
     */
    @ColumnInfo(defaultValue = "0")
    val deleteConfirmed: Boolean = false
) {
    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_USER = "user"

        const val SYNC_STATUS_SYNCED = "synced"
        const val SYNC_STATUS_PENDING = "pending"
    }
}
