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
 * 🆕 v21.1.0+ (Шаг 1.7-1, миграция 22→23): ПАПКИ РЕЦЕПТОВ.
 * Добавлены методы для работы с папками:
 *  - getByFolder(folderId, sortOrder) — рецепты в папке;
 *  - getRootRecipes(source) — рецепты без папки (folderId IS NULL);
 *  - searchInFolder(folderId, query) — поиск внутри папки;
 *  - countByFolder(folderId) — для запрета удаления непустой папки;
 *  - moveRecipeToFolder(recipeId, folderId, ...) — перемещение.
 *
 * Поиск и сортировка:
 *  - searchBySource(query) — LIKE '%query%' COLLATE NOCASE по title и description
 *    (в корне «Моих рецептов» или в «Рецептах ИИ»).
 *  - Вместо динамического ORDER BY — отдельные запросы (Room не любит
 *    подстановку имён колонок, и это безопаснее).
 */
@Dao
interface RecipeDao {

    // ============================================================
    // ЧТЕНИЕ — ОБЩИЕ (без папок)
    // ============================================================

    /**
     * Все «живые» рецепты по source, сортировка «сначала новые».
     * Для экрана «Рецепты ИИ» (source = "ai") и «Мои рецепты» (source = "user").
     *
     * ⚠️ Для «Моих рецептов» этот запрос возвращает ВСЕ рецепты,
     * включая лежащие в папках. Для корня «Моих рецептов» использовать
     * getRootRecipes(source). См. ниже.
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
     * Поиск по названию И описанию (LIKE, case-insensitive) в рамках source.
     * Если query пустой — вернёт все живые рецепты по source,
     * отсортированные «сначала новые». Это удобно для единого
     * пути в RecipeListActivity (пустой поиск = все).
     *
     * ⚠️ Для «Моих рецептов» этот запрос ищет по ВСЕМ рецептам source = "user",
     * включая лежащие в папках. Для поиска в корне «Моих рецептов» —
     * searchRootRecipes(source, query). См. ниже.
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
    // 🆕 ШАГ 1.7-1: ЧТЕНИЕ — ПАПКИ РЕЦЕПТОВ
    // ============================================================

    /**
     * Рецепты в указанной папке, сортировка «сначала новые».
     * folderId — id папки рецептов (RecipeFolderEntity.id).
     */
    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getByFolderNewest(folderId: String): List<RecipeEntity>

    /**
     * Рецепты в указанной папке, сортировка «по алфавиту».
     */
    @Query("""
        SELECT * FROM recipes
        WHERE folderId = :folderId AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
    """)
    suspend fun getByFolderAlphabetical(folderId: String): List<RecipeEntity>

    /**
     * Рецепты БЕЗ папки (в корне «Моих рецептов») по source,
     * сортировка «сначала новые».
     *
     * Для «Моих рецептов»: source = "user", folderId IS NULL —
     * это то, что показываем в корне рядом с папками.
     * Для «Рецептов ИИ»: source = "ai", folderId IS NULL —
     * у ИИ-рецептов папок нет, вернутся все живые.
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getRootRecipesNewest(source: String): List<RecipeEntity>

    /**
     * Рецепты БЕЗ папки по source, сортировка «по алфавиту».
     */
    @Query("""
        SELECT * FROM recipes
        WHERE source = :source
          AND folderId IS NULL
          AND isDeleted = 0
        ORDER BY title COLLATE NOCASE ASC
    """)
    suspend fun getRootRecipesAlphabetical(source: String): List<RecipeEntity>

    /**
     * Поиск по названию И описанию среди рецептов БЕЗ папки (корень «Моих рецептов»).
     * Пустой query = все рецепты корня (см. getRootRecipesNewest).
     */
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

    /**
     * Поиск по названию И описанию среди рецептов В ПАПКЕ.
     * Пустой query = все рецепты папки (см. getByFolderNewest).
     */
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

    /**
     * Сколько «живых» рецептов в папке.
     * Для запрета удаления непустой папки (см. RecipesRepository.deleteFolder).
     */
    @Query("""
        SELECT COUNT(*) FROM recipes
        WHERE folderId = :folderId AND isDeleted = 0
    """)
    suspend fun countByFolder(folderId: String): Int

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

    /**
     * 🆕 Шаг 1.7-1: переместить рецепт в папку (или в корень, если folderId = null).
     * Обновляет folderId, updatedAt, updatedBy и ставит syncStatus = 'pending',
     * чтобы изменение уехало через outbox.
     */
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
