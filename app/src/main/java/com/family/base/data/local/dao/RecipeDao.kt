package com.family.base.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.family.base.data.local.entity.RecipeEntity

/**
 * 🆕 v21.1.0 (миграция 21→22): DAO для рецептов.
 *
 * Стиль — как у ItemDao/FolderDao:
 *  - фильтр isDeleted = 0 обязателен во всех UI-запросах;
 *  - запросы для синка (getAllRecipesRaw, getRecipesBySyncStatusPending)
 *    НЕ фильтруют isDeleted — changelog должен видеть tombstones;
 *  - markRecipeDeleted — soft-delete для синка (tombstone).
 *
 * Поиск и сортировка:
 *  - searchByTitle(query) — LIKE '%query%' COLLATE NOCASE по title и description.
 *    Возвращает List<RecipeEntity> (Room не умеет Flow в этом проекте — везде suspend).
 *  - getBySource(source, sortOrder) — фильтр по source + сортировка.
 *    sortOrder: "new" (createdAt DESC), "name" (title COLLATE NOCASE ASC).
 *    Вместо динамического ORDER BY — два отдельных запроса (Room не любит
 *    подстановку имён колонок, и это безопаснее).
 */
@Dao
interface RecipeDao {

    // ============================================================
    // ЧТЕНИЕ
    // ============================================================

    /**
     * Все «живые» рецепты по source, сортировка «сначала новые».
     * Для экрана «Рецепты ИИ» (source = "ai") и «Мои рецепты» (source = "user").
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getBySourceNewest(source: String): List<RecipeEntity>

    /**
     * Все «живые» рецепты по source, сортировка «по алфавиту».
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
    """)
    suspend fun getBySourceAlphabetical(source: String): List<RecipeEntity>

    /**
     * Все «живые» рецепты (без фильтра по source), сортировка «сначала новые».
     * Пригодится, если захотим общий экран «Все рецепты».
     */
    @Query("""
        SELECT * FROM recipes
        WHERE isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getAllNewest(): List<RecipeEntity>

    /**
     * Поиск по названию И описанию (LIKE, case-insensitive).
     * Если query пустой — вернёт все живые рецепты по source,
     * отсортированные «сначала новые». Это удобно для единого
     * пути в RecipeListActivity (пустой поиск = все).
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND isDeleted = 0
          AND (
              title LIKE '%' || :query || '%' COLLATE NOCASE
              OR (description IS NOT NULL AND description LIKE '%' || :query || '%' COLLATE NOCASE)
          )
        ORDER BY createdAt DESC
    """)
    suspend fun searchBySource(source: String, query: String): List<RecipeEntity>

    /**
     * Один рецепт по id БЕЗ фильтра isDeleted — для синка
     * и для проверки существования. Для UI — getLiveRecipeById.
     */
    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getRecipeById(id: String): RecipeEntity?

    /**
     * Один «живой» рецепт по id — для UI.
     */
    @Query("SELECT * FROM recipes WHERE id = :id AND isDeleted = 0")
    suspend fun getLiveRecipeById(id: String): RecipeEntity?

    /**
     * Все рецепты без фильтров — для синка (changelog должен видеть tombstones).
     */
    @Query("SELECT * FROM recipes")
    suspend fun getAllRecipesRaw(): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE syncStatus = 'pending'")
    suspend fun getRecipesBySyncStatusPending(): List<RecipeEntity>

    @Query("SELECT COUNT(*) FROM recipes WHERE source = :source AND isDeleted = 0")
    suspend fun countBySource(source: String): Int

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: RecipeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipes(recipes: List<RecipeEntity>)

    @Update
    suspend fun updateRecipe(recipe: RecipeEntity)

    @Update
    suspend fun updateRecipes(recipes: List<RecipeEntity>)

    @Query("UPDATE recipes SET syncStatus = :status WHERE id = :recipeId")
    suspend fun updateSyncStatus(recipeId: String, status: String)

    // ============================================================
    // TOMBSTONE (soft-delete)
    // ============================================================

    /**
     * Пометить рецепт как удалённый (tombstone).
     * syncStatus = 'pending' → уедет через outbox.
     * deleteConfirmed = 0 → ещё не подтверждено PUSH'ем.
     */
    @Query("""
        UPDATE recipes SET
            isDeleted = 1,
            deletedAt = :date,
            deletedBy = :user,
            deleteConfirmed = 0,
            updatedAt = :date,
            syncStatus = 'pending'
        WHERE id = :recipeId
    """)
    suspend fun markRecipeDeleted(recipeId: String, date: Long, user: String?)

    /**
     * Подтвердить tombstone после успешного PUSH в changelog.
     */
    @Query("UPDATE recipes SET deleteConfirmed = 1 WHERE id = :recipeId")
    suspend fun confirmRecipeDeleted(recipeId: String)

    /**
     * Подтверждённые tombstones старше указанного времени — для cleanup.
     */
    @Query("""
        SELECT id FROM recipes
        WHERE isDeleted = 1
          AND deleteConfirmed = 1
          AND deletedAt IS NOT NULL
          AND deletedAt < :thresholdMs
    """)
    suspend fun getConfirmedRecipesOlderThan(thresholdMs: Long): List<String>

    /**
     * Физическое удаление подтверждённых tombstones по списку id.
     * ВНИМАНИЕ: перед вызовом нужно удалить медиа рецептов
     * (RecipeMediaDao.hardDeleteByRecipeIds) — иначе осиротеют.
     * См. RecipesRepository.cleanupOldRecipeTombstones.
     */
    @Query("DELETE FROM recipes WHERE id IN (:ids)")
    suspend fun hardDeleteConfirmedRecipes(ids: List<String>)
}
