package com.family.base.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 🆕 v21.1.0 (миграция 21→22): РЕЦЕПТЫ.
 * Одна таблица для двух источников:
 * source = "ai"   → сгенерированные в «Рецептах ИИ»;
 * source = "user" → созданные пользователем в «Моих рецептах».
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
 * 🆕 v21.1.0+ (Шаг 1.7-1, миграция 22→23): ПАПКИ РЕЦЕПТОВ.
 * Добавлено поле folderId — ссылка на RecipeFolderEntity.
 * folderId = null  → рецепт лежит в корне «Моих рецептов»
 * (рядом с папками);
 * folderId != null → рецепт внутри папки (RecipeFolderEntity.id).
 * Папки есть ТОЛЬКО у «Моих рецептов» (source = "user").
 * У «Рецептов ИИ» (source = "ai") folderId всегда null.
 *
 * 🆕 v23.4.9 (миграция 23→24): КУХНЯ МИРА + РЕЙТИНГ.
 * Добавлены поля:
 * - cuisineCountry (страна кухни, обязательное, по умолчанию "")
 * - likes (количество лайков, по умолчанию 0)
 * - dislikes (количество дизлайков, по умолчанию 0)
 * - cookedCount (количество приготовлений, по умолчанию 0)
 *
 * ПОИСК/СОРТИРОВКА:
 * index_recipes_title        — быстрый поиск по названию (LIKE тоже работает);
 * index_recipes_createdAt    — сортировка «сначала новые»;
 * index_recipes_source_createdAt — фильтр по source + сортировка;
 * index_recipes_folderId     — выборка рецептов в папке (getByFolder,
 *                              countByFolder, getRootRecipes);
 * index_recipes_source_folderId_createdAt — фильтр по source + папке +
 *                              сортировка (корень «Моих рецептов», список внутри папки);
 * index_recipes_isDeleted    — фильтр tombstones;
 * index_recipes_deleteConfirmed_deletedAt — cleanup tombstones;
 * index_recipes_cuisineCountry — фильтрация по стране кухни;
 * index_recipes_cookedCount  — сортировка «По популярности»;
 * index_recipes_likes        — сортировка «По лайкам»;
 * index_recipes_dislikes     — сортировка «По дизлайкам».
 *
 * ⚠️ Правило №34: схема @Entity должна точно совпадать с SQL в MIGRATION_21_22
 * (и в MIGRATION_22_23 для folderId, MIGRATION_23_24 для новых полей).
 * source: TEXT NOT NULL DEFAULT 'user' → @ColumnInfo(defaultValue = "user")
 * syncStatus: TEXT NOT NULL DEFAULT 'synced' → @ColumnInfo(defaultValue = "synced")
 * isDeleted: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 * deleteConfirmed: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 * folderId: TEXT (nullable, без DEFAULT) → без @ColumnInfo(defaultValue)
 * cuisineCountry: TEXT NOT NULL DEFAULT '' → @ColumnInfo(defaultValue = "")
 * likes: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 * dislikes: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 * cookedCount: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 */
@Entity(
    tableName = "recipes",
    indices = [
        Index(value = ["title"], name = "index_recipes_title"),
        Index(value = ["createdAt"], name = "index_recipes_createdAt"),
        Index(value = ["source", "createdAt"], name = "index_recipes_source_createdAt"),
        Index(value = ["folderId"], name = "index_recipes_folderId"),
        Index(
            value = ["source", "folderId", "createdAt"],
            name = "index_recipes_source_folderId_createdAt"
        ),
        Index(value = ["isDeleted"], name = "index_recipes_isDeleted"),
        Index(
            value = ["deleteConfirmed", "deletedAt"],
            name = "index_recipes_deleteConfirmed_deletedAt"
        ),
        // 🆕 v23.4.9 (миграция 23→24): индексы для новых полей
        Index(value = ["cuisineCountry"], name = "index_recipes_cuisineCountry"),
        Index(value = ["cookedCount"], name = "index_recipes_cookedCount"),
        Index(value = ["likes"], name = "index_recipes_likes"),
        Index(value = ["dislikes"], name = "index_recipes_dislikes")
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
     * "ai"   — сгенерирован в «Рецептах ИИ»;
     * "user" — создан в «Моих рецептах» вручную.
     * Используется для фильтра: в «Рецептах ИИ» показываем только
     * source = "ai", в «Моих рецептах» — только source = "user".
     */
    @ColumnInfo(defaultValue = "user")
    val source: String = SOURCE_USER,

    /**
     * 🆕 v21.1.0+ (Шаг 1.7-1, миграция 22→23): папка рецепта.
     * null      — рецепт в корне «Моих рецептов» (рядом с папками);
     * не null   — рецепт внутри папки (RecipeFolderEntity.id).
     * Папки есть только у «Моих рецептов» (source = "user").
     * У «Рецептов ИИ» (source = "ai") folderId всегда null.
     *
     * ⚠️ Колонка nullable, без DEFAULT. В MIGRATION_22_23 добавляется
     * через ALTER TABLE recipes ADD COLUMN folderId TEXT — без NOT NULL.
     * Room валидирует схему и ожидает именно nullable TEXT без default.
     */
    val folderId: String? = null,

    /**
     * 🆕 v23.4.9 (миграция 23→24): страна кухни.
     * Обязательное поле (NOT NULL), по умолчанию пустая строка.
     * В UI при создании/редактировании рецепта это поле обязательно к заполнению.
     * Для старых рецептов (до миграции) будет пустая строка — в UI показываем
     * "Страна не указана" или предлагаем выбрать страну.
     */
    @ColumnInfo(defaultValue = "")
    val cuisineCountry: String = "",

    /**
     * 🆕 v23.4.9 (миграция 23→24): количество лайков.
     * По умолчанию 0. Увеличивается при нажатии на кнопку "Лайк" в UI.
     */
    @ColumnInfo(defaultValue = "0")
    val likes: Int = 0,

    /**
     * 🆕 v23.4.9 (миграция 23→24): количество дизлайков.
     * По умолчанию 0. Увеличивается при нажатии на кнопку "Дизлайк" в UI.
     */
    @ColumnInfo(defaultValue = "0")
    val dislikes: Int = 0,

    /**
     * 🆕 v23.4.9 (миграция 23→24): количество приготовлений.
     * По умолчанию 0. Увеличивается при нажатии на кнопку "Приготовлено" в UI.
     * Используется для сортировки "По популярности".
     */
    @ColumnInfo(defaultValue = "0")
    val cookedCount: Int = 0,

    val createdBy: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedBy: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),

    // ===== СИНХРОНИЗАЦИЯ (v15, как у items/folders/tasks) =====
    /**
     * Статус синхронизации записи.
     * "synced"  — синхронизирована с Яндекс.Диском;
     * "pending" — изменена локально, ждёт отправки в changelog.
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

// ============================================================
// 🆕 v23.4.9 (27.11.2026) — миграция 23→24
// Добавлены поля: cuisineCountry, likes, dislikes, cookedCount
// Добавлены индексы: index_recipes_cuisineCountry,
//                    index_recipes_cookedCount,
//                    index_recipes_likes,
//                    index_recipes_dislikes
// ============================================================
