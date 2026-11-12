package com.family.base.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 🆕 v21.1.0 (миграция 21→22): МЕДИА РЕЦЕПТА.
 *
 * Одна строка = один файл (фото или видео), привязанный к рецепту.
 *
 * Почему отдельная таблица, а не JSON-массив в RecipeEntity:
 *  1. Синк. В changelog уходит одна строка = один файл. При добавлении
 *     одного фото не нужно перезаливать весь рецепт и все его медиа.
 *  2. Прогресс загрузки. uploadUnsyncedRecipeMedia фильтрует по
 *     imageSynced = false — как у items. 100 фото = 100 независимых
 *     upload-ов, каждый со своим флагом.
 *  3. Удаление одного файла. Не надо перезаписывать весь рецепт —
 *     только isDeleted = 1 на одной строке.
 *  4. Сортировка. ORDER BY orderIndex — стабильный порядок в UI.
 *
 * ФАЙЛЫ НА ДИСКЕ:
 *   /BAZA/data/recipe_media/<recipeId>/<mediaId>.jpg
 *   /BAZA/data/recipe_media/<recipeId>/<mediaId>.mp4
 *
 * ЛОКАЛЬНО (на устройстве):
 *   filesDir/recipe_media/<recipeId>/<mediaId>.jpg
 *   filesDir/recipe_media/<recipeId>/<mediaId>.mp4
 *   filesDir/recipe_media/<recipeId>/<mediaId>_thumb.jpg  — превью видео
 *
 * ⚠️ Правило №34: схема @Entity должна точно совпадать с SQL в MIGRATION_21_22.
 *  - mediaType: TEXT NOT NULL → без defaultValue (обязательное поле)
 *  - orderIndex: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 *  - imageSynced: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 *  - syncStatus: TEXT NOT NULL DEFAULT 'synced' → @ColumnInfo(defaultValue = "synced")
 *  - isDeleted: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 *  - deleteConfirmed: INTEGER NOT NULL DEFAULT 0 → @ColumnInfo(defaultValue = "0")
 */
@Entity(
    tableName = "recipe_media",
    indices = [
        // Выборка всего медиа рецепта (UI: галереи фото/видео)
        Index(value = ["recipeId"], name = "index_recipe_media_recipeId"),
        // Сортировка внутри рецепта
        Index(
            value = ["recipeId", "orderIndex"],
            name = "index_recipe_media_recipeId_orderIndex"
        ),
        // Выборка «что ещё не залито на Диск»
        Index(value = ["imageSynced"], name = "index_recipe_media_imageSynced"),
        // Фильтр tombstones
        Index(value = ["isDeleted"], name = "index_recipe_media_isDeleted"),
        // Cleanup tombstones
        Index(
            value = ["deleteConfirmed", "deletedAt"],
            name = "index_recipe_media_deleteConfirmed_deletedAt"
        )
    ]
)
data class RecipeMediaEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),

    /**
     * id рецепта-родителя (RecipeEntity.id).
     * ON DELETE CASCADE не используется — при удалении рецепта
     * его медиа помечаются tombstone отдельно (через outbox).
     * Так безопаснее для синка: каждое удаление файла уезжает
     * отдельной записью, и не возникает «файл на Диске остался,
     * а строки в БД нет».
     */
    val recipeId: String,

    /**
     * Тип медиа:
     *  - "photo" — фото (jpg/webp);
     *  - "video" — видео (mp4/mkv/3gp/mov/…).
     *
     * Строкой, а не enum, — чтобы не сломать Gson-сериализацию
     * в changelog, если завтра добавим "audio" или "document".
     */
    val mediaType: String,

    /**
     * Порядок отображения внутри рецепта.
     * Меньше = выше (левее в горизонтальной галерее).
     * При добавлении нового медиа ставим max(orderIndex)+1.
     * NULL не бывает — defaultValue = "0" для старых записей.
     */
    @ColumnInfo(defaultValue = "0")
    val orderIndex: Int = 0,

    /**
     * Имя локального файла (или URL после скачивания с Диска).
     *
     * Для фото:    "<mediaId>.jpg"
     * Для видео:   "<mediaId>.mp4" (или .mkv/.3gp/.mov — по mime)
     *
     * Полный локальный путь вычисляется как
     *   filesDir/recipe_media/<recipeId>/<imageUrl>
     * (см. ImageUtils.getRecipeMediaFile).
     *
     * NULL — если файл ещё не сохранён локально (например, рецепт
     * пришёл с другого устройства, imageSynced = true, но файла
     * на этом устройстве ещё нет — SyncManager скачает).
     */
    val imageUrl: String? = null,

    /**
     * Превью видео (первый кадр через MediaMetadataRetriever).
     * Только для mediaType = "video". Для "photo" — NULL.
     *
     * Формат: "<mediaId>_thumb.jpg" в той же папке, что и видео.
     * Нужно, чтобы в списке рецептов и в галерее показывать
     * превью без запуска ExoPlayer.
     */
    val thumbnailUrl: String? = null,

    /**
     * Признак «файл реально загружен на Яндекс.Диск».
     *
     * false — файл есть только локально (или его вообще нет).
     *         uploadUnsyncedRecipeMedia подхватит и зальёт,
     *         если локальный файл существует.
     * true  — заливка прошла успешно. Повторно не грузим.
     *
     * Симметрично ItemEntity.imageSynced. Смысл тот же:
     * разделить «файл в кэше приложения» и «файл на Диске».
     */
    @ColumnInfo(defaultValue = "0")
    val imageSynced: Boolean = false,

    /**
     * MIME-тип файла. Например "image/jpeg", "video/mp4".
     * Нужен VideoPlayerActivity, чтобы ExoPlayer знал контейнер.
     * NULL — определим по расширению при проигрывании.
     */
    val mimeType: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val createdBy: String,

    // ===== СИНХРОНИЗАЦИЯ (как у items/folders/tasks/recipes) =====
    @ColumnInfo(defaultValue = "synced")
    val syncStatus: String = SYNC_STATUS_SYNCED,

    // ===== TOMBSTONE =====
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,

    val deletedAt: Long? = null,
    val deletedBy: String? = null,

    @ColumnInfo(defaultValue = "0")
    val deleteConfirmed: Boolean = false
) {
    companion object {
        const val TYPE_PHOTO = "photo"
        const val TYPE_VIDEO = "video"

        const val SYNC_STATUS_SYNCED = "synced"
        const val SYNC_STATUS_PENDING = "pending"
    }
}
