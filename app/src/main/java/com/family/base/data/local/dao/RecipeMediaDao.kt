package com.family.base.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.family.base.data.local.entity.RecipeMediaEntity

/**
 * 🆕 v21.1.0 (миграция 21→22): DAO для медиа рецептов.
 *
 * Стиль — как у ItemDao/FolderDao/RecipeDao:
 *  - фильтр isDeleted = 0 обязателен во всех UI-запросах;
 *  - запросы для синка (getAllRaw, getPending) НЕ фильтруют isDeleted;
 *  - markMediaDeleted — soft-delete для синка (tombstone).
 *
 * Отдельные методы для фото и видео (getPhotos / getVideos) —
 * чтобы UI делал два независимых запроса и строил две галереи
 * (горизонтальная прокрутка фото сверху, видео снизу).
 */
@Dao
interface RecipeMediaDao {

    // ============================================================
    // ЧТЕНИЕ
    // ============================================================

    /**
     * Всё медиа рецепта (фото + видео), по orderIndex.
     * Используется, когда порядок важен целиком (например, для
     * отладки). В UI обычно нужны отдельно фото и видео.
     */
    @Query("""
        SELECT * FROM recipe_media
        WHERE recipeId = :recipeId AND isDeleted = 0
        ORDER BY orderIndex ASC, createdAt ASC
    """)
    suspend fun getByRecipe(recipeId: String): List<RecipeMediaEntity>

    /**
     * Только фото рецепта, по orderIndex.
     */
    @Query("""
        SELECT * FROM recipe_media
        WHERE recipeId = :recipeId
          AND mediaType = 'photo'
          AND isDeleted = 0
        ORDER BY orderIndex ASC, createdAt ASC
    """)
    suspend fun getPhotos(recipeId: String): List<RecipeMediaEntity>

    /**
     * Только видео рецепта, по orderIndex.
     */
    @Query("""
        SELECT * FROM recipe_media
        WHERE recipeId = :recipeId
          AND mediaType = 'video'
          AND isDeleted = 0
        ORDER BY orderIndex ASC, createdAt ASC
    """)
    suspend fun getVideos(recipeId: String): List<RecipeMediaEntity>

    /**
     * Следующий orderIndex для нового медиа в рецепте.
     * MAX(orderIndex) + 1, или 0, если медиа ещё нет.
     */
    @Query("""
        SELECT COALESCE(MAX(orderIndex), -1) + 1
        FROM recipe_media
        WHERE recipeId = :recipeId
    """)
    suspend fun nextOrderIndex(recipeId: String): Int

    /**
     * Одно медиа по id БЕЗ фильтра isDeleted — для синка.
     */
    @Query("SELECT * FROM recipe_media WHERE id = :id")
    suspend fun getMediaById(id: String): RecipeMediaEntity?

    /**
     * Все медиа без фильтров — для синка (changelog должен видеть tombstones).
     */
    @Query("SELECT * FROM recipe_media")
    suspend fun getAllRaw(): List<RecipeMediaEntity>

    @Query("SELECT * FROM recipe_media WHERE syncStatus = 'pending'")
    suspend fun getPending(): List<RecipeMediaEntity>

    @Query("SELECT COUNT(*) FROM recipe_media WHERE recipeId = :recipeId AND isDeleted = 0")
    suspend fun countByRecipe(recipeId: String): Int

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(media: RecipeMediaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaBatch(media: List<RecipeMediaEntity>)

    @Update
    suspend fun updateMedia(media: RecipeMediaEntity)

    @Update
    suspend fun updateMediaBatch(media: List<RecipeMediaEntity>)

    @Query("UPDATE recipe_media SET syncStatus = :status WHERE id = :mediaId")
    suspend fun updateSyncStatus(mediaId: String, status: String)

    // ============================================================
    // TOMBSTONE (soft-delete)
    // ============================================================

    /**
     * Пометить медиа как удалённое.
     * Сам файл на Диске удалим отдельной операцией (deleteFileOnDisk)
     * в RecipesRepository — чтобы если удаление файла не удалось,
     * не потерять факт «это медиа удалено» в БД.
     */
    @Query("""
        UPDATE recipe_media SET
            isDeleted = 1,
            deletedAt = :date,
            deletedBy = :user,
            deleteConfirmed = 0,
            syncStatus = 'pending'
        WHERE id = :mediaId
    """)
    suspend fun markMediaDeleted(mediaId: String, date: Long, user: String?)

    /**
     * Пометить ВСЁ медиа рецепта как удалённое (при удалении рецепта).
     * Возвращает количество затронутых строк.
     *
     * ⚠️ Возвращаемое Int в Room для @Query UPDATE — это число
     * изменённых строк, поддерживается.
     */
    @Query("""
        UPDATE recipe_media SET
            isDeleted = 1,
            deletedAt = :date,
            deletedBy = :user,
            deleteConfirmed = 0,
            syncStatus = 'pending'
        WHERE recipeId = :recipeId AND isDeleted = 0
    """)
    suspend fun markAllByRecipeDeleted(recipeId: String, date: Long, user: String?): Int

    @Query("UPDATE recipe_media SET deleteConfirmed = 1 WHERE id = :mediaId")
    suspend fun confirmMediaDeleted(mediaId: String)

    @Query("""
        SELECT id FROM recipe_media
        WHERE isDeleted = 1
          AND deleteConfirmed = 1
          AND deletedAt IS NOT NULL
          AND deletedAt < :thresholdMs
    """)
    suspend fun getConfirmedMediaOlderThan(thresholdMs: Long): List<String>

    /**
     * Физическое удаление подтверждённых tombstones по списку id.
     */
    @Query("DELETE FROM recipe_media WHERE id IN (:ids)")
    suspend fun hardDeleteConfirmedMedia(ids: List<String>)

    /**
     * Физическое удаление ВСЕГО медиа конкретного рецепта.
     * Используется при cleanupOldRecipeTombstones — перед тем как
     * hardDeleteConfirmedRecipes удалит сам рецепт.
     */
    @Query("DELETE FROM recipe_media WHERE recipeId IN (:recipeIds)")
    suspend fun hardDeleteByRecipeIds(recipeIds: List<String>)
}
