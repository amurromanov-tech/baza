package com.family.base.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.family.base.data.local.entity.RecipeFolderEntity

/**
 * 🆕 v21.1.0+ (Шаг 1.7-1, миграция 22→23): DAO для папок рецептов.
 *
 * Стиль — как у FolderDao/RecipeDao:
 *  - фильтр isDeleted = 0 обязателен во всех UI-запросах;
 *  - запросы для синка (getAllRecipeFoldersRaw, getRecipeFoldersBySyncStatusPending)
 *    НЕ фильтруют isDeleted — changelog должен видеть tombstones;
 *  - markRecipeFolderDeleted — soft-delete для синка (tombstone).
 *
 * Вложенность:
 *  - getFolders(parentId) — дочерние папки указанного родителя
 *    (parentId = null → корневые папки «Моих рецептов»);
 *  - getByParentNewest(parentId) / getByParentAlphabetical(parentId) —
 *    с сортировкой (по createdAt DESC / по name COLLATE NOCASE ASC);
 *  - hasChildren(parentId) — есть ли дочерние папки (для запрета удаления).
 *
 * Рецепты в папке:
 *  - countRecipesInFolder(folderId) — сколько рецептов в папке
 *    (для запрета удаления непустой папки). Вызывается через
 *    RecipeDao.countByFolder(folderId) — см. RecipeDao.
 *
 * ⚠️ Room не любит динамический ORDER BY — поэтому два отдельных запроса
 * (newest / alphabetical), как в RecipeDao.
 */
@Dao
interface RecipeFolderDao {

    // ============================================================
    // ЧТЕНИЕ
    // ============================================================

    /**
     * Дочерние «живые» папки указанного родителя, сортировка «сначала новые».
     * parentId = null → корневые папки «Моих рецептов».
     */
    @Query("""
        SELECT * FROM recipe_folders
        WHERE parentId IS :parentId AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    suspend fun getByParentNewest(parentId: String?): List<RecipeFolderEntity>

    /**
     * Дочерние «живые» папки указанного родителя, сортировка «по алфавиту».
     * parentId = null → корневые папки «Моих рецептов».
     */
    @Query("""
        SELECT * FROM recipe_folders
        WHERE parentId IS :parentId AND isDeleted = 0
        ORDER BY name COLLATE NOCASE ASC
    """)
    suspend fun getByParentAlphabetical(parentId: String?): List<RecipeFolderEntity>

    /**
     * Все «живые» папки рецептов (без фильтра по родителю).
     * Пригодится для выпадающего списка «Папка» в RecipeEditActivity
     * (иерархия строится в памяти) и для проверки существования.
     */
    @Query("""
        SELECT * FROM recipe_folders
        WHERE isDeleted = 0
        ORDER BY name COLLATE NOCASE ASC
    """)
    suspend fun getAllLive(): List<RecipeFolderEntity>

    /**
     * Одна папка по id БЕЗ фильтра isDeleted — для синка
     * и для проверки существования. Для UI — getLiveFolderById.
     */
    @Query("SELECT * FROM recipe_folders WHERE id = :id")
    suspend fun getFolderById(id: String): RecipeFolderEntity?

    /**
     * Одна «живая» папка по id — для UI.
     */
    @Query("SELECT * FROM recipe_folders WHERE id = :id AND isDeleted = 0")
    suspend fun getLiveFolderById(id: String): RecipeFolderEntity?

    /**
     * Сколько «живых» дочерних папок у указанного родителя.
     * parentId = null → корневые папки.
     * Для запрета удаления папки, у которой есть дочерние.
     */
    @Query("""
        SELECT COUNT(*) FROM recipe_folders
        WHERE parentId IS :parentId AND isDeleted = 0
    """)
    suspend fun countChildren(parentId: String?): Int

    /**
     * Есть ли у папки «живые» дочерние папки (boolean-обёртка над countChildren).
     * Для запрета удаления непустой папки.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM recipe_folders
            WHERE parentId = :parentId AND isDeleted = 0
        )
    """)
    suspend fun hasChildren(parentId: String): Boolean

    /**
     * Все папки без фильтров — для синка (changelog должен видеть tombstones).
     */
    @Query("SELECT * FROM recipe_folders")
    suspend fun getAllRecipeFoldersRaw(): List<RecipeFolderEntity>

    @Query("SELECT * FROM recipe_folders WHERE syncStatus = 'pending'")
    suspend fun getRecipeFoldersBySyncStatusPending(): List<RecipeFolderEntity>

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: RecipeFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<RecipeFolderEntity>)

    @Update
    suspend fun updateFolder(folder: RecipeFolderEntity)

    @Update
    suspend fun updateFolders(folders: List<RecipeFolderEntity>)

    @Query("UPDATE recipe_folders SET syncStatus = :status WHERE id = :folderId")
    suspend fun updateSyncStatus(folderId: String, status: String)

    // ============================================================
    // TOMBSTONE (soft-delete)
    // ============================================================

    /**
     * Пометить папку как удалённую (tombstone).
     * syncStatus = 'pending' → уедет через outbox.
     * deleteConfirmed = 0 → ещё не подтверждено PUSH'ем.
     *
     * ⚠️ Перед вызовом проверить, что папка пуста
     * (countChildren == 0 && RecipeDao.countByFolder == 0).
     * См. RecipesRepository.deleteFolder.
     */
    @Query("""
        UPDATE recipe_folders SET
            isDeleted = 1,
            deletedAt = :date,
            deletedBy = :user,
            deleteConfirmed = 0,
            updatedAt = :date,
            syncStatus = 'pending'
        WHERE id = :folderId
    """)
    suspend fun markFolderDeleted(folderId: String, date: Long, user: String?)

    /**
     * Подтвердить tombstone после успешного PUSH в changelog.
     */
    @Query("UPDATE recipe_folders SET deleteConfirmed = 1 WHERE id = :folderId")
    suspend fun confirmFolderDeleted(folderId: String)

    /**
     * Подтверждённые tombstones старше указанного времени — для cleanup.
     */
    @Query("""
        SELECT id FROM recipe_folders
        WHERE isDeleted = 1
          AND deleteConfirmed = 1
          AND deletedAt IS NOT NULL
          AND deletedAt < :thresholdMs
    """)
    suspend fun getConfirmedFoldersOlderThan(thresholdMs: Long): List<String>

    /**
     * Физическое удаление подтверждённых tombstones по списку id.
     * ВНИМАНИЕ: перед вызовом нужно убедиться, что в папке нет рецептов
     * и дочерних папок (иначе осиротеют). См. RecipesRepository.cleanupOldRecipeTombstones.
     */
    @Query("DELETE FROM recipe_folders WHERE id IN (:ids)")
    suspend fun hardDeleteConfirmedFolders(ids: List<String>)
}
