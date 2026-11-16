package com.family.base.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.family.base.data.local.entity.RecipeEntity

@Dao
interface RecipeDao {

    // ============================================================
    // ЧТЕНИЕ — ОБЩИЕ (без папок)
    // ============================================================

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getBySourceNewest(source: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
    """)
    suspend fun getBySourceAlphabetical(source: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getAllNewest(): List<RecipeEntity>

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
     * 🆕 Глубокий поиск по ВСЕМ рецептам source = 'user' (включая лежащие в папках).
     * Для поиска в корне «Моих рецептов» — чтобы находились рецепты
     * на любой глубине вложенности.
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = 'user'
          AND isDeleted = 0
          AND (
              title LIKE '%' || :query || '%' COLLATE NOCASE
              OR (description IS NOT NULL AND description LIKE '%' || :query || '%' COLLATE NOCASE)
          )
        ORDER BY createdAt DESC
    """)
    suspend fun searchAllUserRecipes(query: String): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getRecipeById(id: String): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE id = :id AND isDeleted = 0")
    suspend fun getLiveRecipeById(id: String): RecipeEntity?

    @Query("SELECT * FROM recipes")
    suspend fun getAllRecipesRaw(): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE syncStatus = 'pending'")
    suspend fun getRecipesBySyncStatusPending(): List<RecipeEntity>

    @Query("SELECT COUNT(*) FROM recipes WHERE source = :source AND isDeleted = 0")
    suspend fun countBySource(source: String): Int

    // ============================================================
    // ПАПКИ РЕЦЕПТОВ — ЧТЕНИЕ
    // ============================================================

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getByFolderNewest(folderId: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
    """)
    suspend fun getByFolderAlphabetical(folderId: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getRootRecipesNewest(source: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
    """)
    suspend fun getRootRecipesAlphabetical(source: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND isDeleted = 0
          AND (
              title LIKE '%' || :query || '%' COLLATE NOCASE
              OR (description IS NOT NULL AND description LIKE '%' || :query || '%' COLLATE NOCASE)
          )
        ORDER BY createdAt DESC
    """)
    suspend fun searchRootRecipes(source: String, query: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId
          AND isDeleted = 0
          AND (
              title LIKE '%' || :query || '%' COLLATE NOCASE
              OR (description IS NOT NULL AND description LIKE '%' || :query || '%' COLLATE NOCASE)
          )
        ORDER BY createdAt DESC
    """)
    suspend fun searchInFolder(folderId: String, query: String): List<RecipeEntity>

    @Query("""
        SELECT COUNT(*) FROM recipes
        WHERE folderId = :folderId AND isDeleted = 0
    """)
    suspend fun countByFolder(folderId: String): Int

    /**
     * 🆕 Глубокий подсчёт рецептов по папкам (batch, рекурсивный CTE).
     * Для каждой папки из :folderIds считает все «живые» рецепты
     * на всю глубину вложенности (папка + все её подпапки).
     * Аналог ItemDao.getFolderStatsDeep, но для рецептов.
     */
    @Query("""
        WITH RECURSIVE folder_tree(root_id, folder_id) AS (
            SELECT id, id FROM recipe_folders WHERE id IN (:folderIds) AND isDeleted = 0
            UNION ALL
            SELECT ft.root_id, f.id
            FROM recipe_folders f
            INNER JOIN folder_tree ft ON f.parentId = ft.folder_id
            WHERE f.isDeleted = 0
        )
        SELECT ft.root_id AS folderId,
               COUNT(r.id) AS itemCount,
               0.0 AS totalPrice
        FROM folder_tree ft
        LEFT JOIN recipes r
               ON r.folderId = ft.folder_id
              AND r.isDeleted = 0
        GROUP BY ft.root_id
    """)
    suspend fun getRecipeFolderStatsDeep(folderIds: List<String>): List<FolderStatsRow>

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

    @Query("""
        UPDATE recipes SET
            folderId = :folderId,
            updatedAt = :date,
            updatedBy = :user,
            syncStatus = 'pending'
        WHERE id = :recipeId
    """)
    suspend fun moveRecipeToFolder(recipeId: String, folderId: String?, date: Long, user: String?)

    // ============================================================
    // TOMBSTONE (soft-delete)
    // ============================================================

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

    @Query("UPDATE recipes SET deleteConfirmed = 1 WHERE id = :recipeId")
    suspend fun confirmRecipeDeleted(recipeId: String)

    @Query("""
        SELECT id FROM recipes
        WHERE isDeleted = 1
          AND deleteConfirmed = 1
          AND deletedAt IS NOT NULL
          AND deletedAt < :thresholdMs
    """)
    suspend fun getConfirmedRecipesOlderThan(thresholdMs: Long): List<String>

    @Query("DELETE FROM recipes WHERE id IN (:ids)")
    suspend fun hardDeleteConfirmedRecipes(ids: List<String>)
}
