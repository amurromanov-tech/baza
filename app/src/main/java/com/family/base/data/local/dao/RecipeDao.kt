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
    // 🆕 v23.4.9 (миграция 23→24): СОРТИРОВКИ ПО РЕЙТИНГУ
    //
    // По лайкам, дизлайкам, популярности (cookedCount).
    // Все методы возвращают только «живые» рецепты (isDeleted = 0).
    // ============================================================

    /**
     * 🆕 Сортировка по лайкам (убывание).
     * При равенстве лайков — по новизне (createdAt DESC).
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source AND isDeleted = 0
        ORDER BY likes DESC, createdAt DESC
    """)
    suspend fun getBySourceByLikes(source: String): List<RecipeEntity>

    /**
     * 🆕 Сортировка по дизлайкам (убывание).
     * При равенстве дизлайков — по новизне (createdAt DESC).
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source AND isDeleted = 0
        ORDER BY dislikes DESC, createdAt DESC
    """)
    suspend fun getBySourceByDislikes(source: String): List<RecipeEntity>

    /**
     * 🆕 Сортировка по популярности (cookedCount, убывание).
     * При равенстве cookedCount — по новизне (createdAt DESC).
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source AND isDeleted = 0
        ORDER BY cookedCount DESC, createdAt DESC
    """)
    suspend fun getBySourceByPopularity(source: String): List<RecipeEntity>

    // ============================================================
    // 🆕 v23.4.9 (миграция 23→24): ФИЛЬТР ПО СТРАНЕ КУХНИ
    //
    // BottomSheet «По странам»: пользователь выбирает страну →
    * список фильтруется. В заголовке показывается плашка
    * «🇮🇹 Итальянская кухня» с кнопкой «✕ Сбросить».
    // ============================================================

    /**
     * 🆕 Фильтр по стране кухни (source + country).
     * Сортировка — по новизне (createdAt DESC).
     * country = "" → покажет рецепты без указанной страны (старые).
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getBySourceAndCountry(source: String, country: String): List<RecipeEntity>

    /**
     * 🆕 Фильтр по стране кухни + сортировка по алфавиту.
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
    """)
    suspend fun getBySourceAndCountryAlphabetical(source: String, country: String): List<RecipeEntity>

    /**
     * 🆕 Фильтр по стране + сортировка по лайкам.
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY likes DESC, createdAt DESC
    """)
    suspend fun getBySourceAndCountryByLikes(source: String, country: String): List<RecipeEntity>

    /**
     * 🆕 Фильтр по стране + сортировка по дизлайкам.
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY dislikes DESC, createdAt DESC
    """)
    suspend fun getBySourceAndCountryByDislikes(source: String, country: String): List<RecipeEntity>

    /**
     * 🆕 Фильтр по стране + сортировка по популярности.
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY cookedCount DESC, createdAt DESC
    """)
    suspend fun getBySourceAndCountryByPopularity(source: String, country: String): List<RecipeEntity>

    /**
     * 🆕 Поиск внутри страны кухни.
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND cuisineCountry = :country
          AND isDeleted = 0
          AND (
              title LIKE '%' || :query || '%' COLLATE NOCASE
              OR (description IS NOT NULL AND description LIKE '%' || :query || '%' COLLATE NOCASE)
          )
        ORDER BY createdAt DESC
    """)
    suspend fun searchBySourceAndCountry(source: String, country: String, query: String): List<RecipeEntity>

    /**
     * 🆕 Список стран с количеством рецептов (для BottomSheet).
     * Возвращает только те страны, где есть хотя бы 1 рецепт.
     * Сортировка — по убыванию количества, затем по алфавиту.
     *
     * Используется в BottomSheet «По странам»:
     * 🇮🇹 Итальянская (12)
     * 🇷🇺 Русская (8)
     * 🇯🇵 Японская (5)
     */
    @Query("""
        SELECT cuisineCountry AS country, COUNT(*) AS count
        FROM recipes
        WHERE source = :source
          AND isDeleted = 0
          AND cuisineCountry != ''
        GROUP BY cuisineCountry
        ORDER BY count DESC, cuisineCountry COLLATE NOCASE ASC
    """)
    suspend fun getDistinctCountriesWithCount(source: String): List<CountryCountRow>

    /**
     * 🆕 Список стран с количеством рецептов (для всех источников).
     */
    @Query("""
        SELECT cuisineCountry AS country, COUNT(*) AS count
        FROM recipes
        WHERE isDeleted = 0
          AND cuisineCountry != ''
        GROUP BY cuisineCountry
        ORDER BY count DESC, cuisineCountry COLLATE NOCASE ASC
    """)
    suspend fun getAllDistinctCountriesWithCount(): List<CountryCountRow>

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
    // 🆕 v23.4.9: СОРТИРОВКИ ВНУТРИ ПАПКИ
    //
    // Когда пользователь внутри папки — те же 5 сортировок
    // (новизна, алфавит, популярность, лайки, дизлайки).
    // ============================================================

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId AND isDeleted = 0
        ORDER BY likes DESC, createdAt DESC
    """)
    suspend fun getByFolderByLikes(folderId: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId AND isDeleted = 0
        ORDER BY dislikes DESC, createdAt DESC
    """)
    suspend fun getByFolderByDislikes(folderId: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId AND isDeleted = 0
        ORDER BY cookedCount DESC, createdAt DESC
    """)
    suspend fun getByFolderByPopularity(folderId: String): List<RecipeEntity>

    // ============================================================
    // 🆕 v23.4.9: ФИЛЬТР ПО СТРАНЕ ВНУТРИ ПАПКИ
    // ============================================================

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getByFolderAndCountry(folderId: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
    """)
    suspend fun getByFolderAndCountryAlphabetical(folderId: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY likes DESC, createdAt DESC
    """)
    suspend fun getByFolderAndCountryByLikes(folderId: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY dislikes DESC, createdAt DESC
    """)
    suspend fun getByFolderAndCountryByDislikes(folderId: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY cookedCount DESC, createdAt DESC
    """)
    suspend fun getByFolderAndCountryByPopularity(folderId: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId
          AND cuisineCountry = :country
          AND isDeleted = 0
          AND (
              title LIKE '%' || :query || '%' COLLATE NOCASE
              OR (description IS NOT NULL AND description LIKE '%' || :query || '%' COLLATE NOCASE)
          )
        ORDER BY createdAt DESC
    """)
    suspend fun searchInFolderByCountry(folderId: String, country: String, query: String): List<RecipeEntity>

    /**
     * 🆕 Список стран с количеством рецептов внутри папки (для BottomSheet).
     */
    @Query("""
        SELECT cuisineCountry AS country, COUNT(*) AS count
        FROM recipes
        WHERE folderId = :folderId
          AND isDeleted = 0
          AND cuisineCountry != ''
        GROUP BY cuisineCountry
        ORDER BY count DESC, cuisineCountry COLLATE NOCASE ASC
    """)
    suspend fun getDistinctCountriesWithCountInFolder(folderId: String): List<CountryCountRow>

    // ============================================================
    // 🆕 v23.4.9: СОРТИРОВКИ В КОРНЕ «МОИХ РЕЦЕПТОВ»
    //
    // Корень — это рецепты без папки (folderId IS NULL).
    // ============================================================

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND isDeleted = 0
        ORDER BY likes DESC, createdAt DESC
    """)
    suspend fun getRootRecipesByLikes(source: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND isDeleted = 0
        ORDER BY dislikes DESC, createdAt DESC
    """)
    suspend fun getRootRecipesByDislikes(source: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND isDeleted = 0
        ORDER BY cookedCount DESC, createdAt DESC
    """)
    suspend fun getRootRecipesByPopularity(source: String): List<RecipeEntity>

    // ============================================================
    // 🆕 v23.4.9: ФИЛЬТР ПО СТРАНЕ В КОРНЕ
    // ============================================================

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getRootRecipesByCountry(source: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
    """)
    suspend fun getRootRecipesByCountryAlphabetical(source: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY likes DESC, createdAt DESC
    """)
    suspend fun getRootRecipesByCountryByLikes(source: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY dislikes DESC, createdAt DESC
    """)
    suspend fun getRootRecipesByCountryByDislikes(source: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND cuisineCountry = :country
          AND isDeleted = 0
        ORDER BY cookedCount DESC, createdAt DESC
    """)
    suspend fun getRootRecipesByCountryByPopularity(source: String, country: String): List<RecipeEntity>

    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND cuisineCountry = :country
          AND isDeleted = 0
          AND (
              title LIKE '%' || :query || '%' COLLATE NOCASE
              OR (description IS NOT NULL AND description LIKE '%' || :query || '%' COLLATE NOCASE)
          )
        ORDER BY createdAt DESC
    """)
    suspend fun searchRootRecipesByCountry(source: String, country: String, query: String): List<RecipeEntity>

    /**
     * 🆕 Список стран с количеством рецептов в корне (для BottomSheet).
     */
    @Query("""
        SELECT cuisineCountry AS country, COUNT(*) AS count
        FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND isDeleted = 0
          AND cuisineCountry != ''
        GROUP BY cuisineCountry
        ORDER BY count DESC, cuisineCountry COLLATE NOCASE ASC
    """)
    suspend fun getDistinctCountriesWithCountInRoot(source: String): List<CountryCountRow>

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
    // 🆕 v23.4.9 (миграция 23→24): ИНКРЕМЕНТ СЧЁТЧИКОВ
    //
    // Лайк, дизлайк, «Приготовлено». Каждый клик = +1.
    // После изменения — syncStatus = 'pending' (уезжает в changelog).
    // ============================================================

    /**
     * 🆕 +1 к лайкам. Вызывается при клике на кнопку «Лайк» в UI.
     */
    @Query("""
        UPDATE recipes SET
            likes = likes + 1,
            updatedAt = :date,
            updatedBy = :user,
            syncStatus = 'pending'
        WHERE id = :recipeId
    """)
    suspend fun incrementLikes(recipeId: String, date: Long, user: String?)

    /**
     * 🆕 +1 к дизлайкам. Вызывается при клике на кнопку «Дизлайк» в UI.
     */
    @Query("""
        UPDATE recipes SET
            dislikes = dislikes + 1,
            updatedAt = :date,
            updatedBy = :user,
            syncStatus = 'pending'
        WHERE id = :recipeId
    """)
    suspend fun incrementDislikes(recipeId: String, date: Long, user: String?)

    /**
     * 🆕 +1 к счётчику приготовлений.
     * Вызывается при клике на кнопку «Приготовлено» в UI.
     * Используется для сортировки «По популярности».
     */
    @Query("""
        UPDATE recipes SET
            cookedCount = cookedCount + 1,
            updatedAt = :date,
            updatedBy = :user,
            syncStatus = 'pending'
        WHERE id = :recipeId
    """)
    suspend fun incrementCookedCount(recipeId: String, date: Long, user: String?)

    /**
     * 🆕 Обновить страну кухни (при редактировании рецепта).
     */
    @Query("""
        UPDATE recipes SET
            cuisineCountry = :country,
            updatedAt = :date,
            updatedBy = :user,
            syncStatus = 'pending'
        WHERE id = :recipeId
    """)
    suspend fun updateCuisineCountry(recipeId: String, country: String, date: Long, user: String?)

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

// ============================================================
// 🆕 v23.4.9 (27.11.2026) — миграция 23→24
// Добавлены методы для:
//   - сортировки по likes/dislikes/cookedCount (для source, folder, root)
//   - фильтрации по стране кухни (cuisineCountry)
//   - получения списка стран с количеством рецептов (для BottomSheet)
//   - инкремента счётчиков лайков/дизлайков/приготовлений
//   - обновления страны кухни
// ============================================================
