package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.FolderEntity

@Dao
interface FolderDao {

    // ============================================================
    // ЧТЕНИЕ
    // ============================================================

    /**
     * Получение корневых папок (parentId IS NULL, parentItemId IS NULL)
     */
    @Query("SELECT * FROM folders WHERE parentId IS NULL AND parentItemId IS NULL ORDER BY name ASC")
    suspend fun getRootFolders(): List<FolderEntity>

    /**
     * Получение папок по parentId (родитель-папка).
     * Поддерживает null (IS NULL) и конкретный parentId.
     * ВАЖНО: возвращает только те, у которых parentItemId IS NULL
     * (т.е. не вложенные в предмет).
     */
    @Query("""
        SELECT * FROM folders 
        WHERE ((parentId IS NULL AND :parentId IS NULL) OR parentId = :parentId)
          AND parentItemId IS NULL
        ORDER BY name ASC
    """)
    suspend fun getFoldersByParent(parentId: String?): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolderById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE path = :path LIMIT 1")
    suspend fun getFolderByPath(path: String): FolderEntity?

    @Query("SELECT * FROM folders")
    suspend fun getAllFolders(): List<FolderEntity>

    // ============================================================
    // 🆕 v15: СИНХРОНИЗАЦИЯ
    // ============================================================

    /**
     * Все папки со статусом pending — для SyncManager.
     */
    @Query("SELECT * FROM folders WHERE syncStatus = 'pending'")
    suspend fun getFoldersBySyncStatusPending(): List<FolderEntity>

    /**
     * Обновить syncStatus у одной папки.
     */
    @Query("UPDATE folders SET syncStatus = :status WHERE id = :folderId")
    suspend fun updateSyncStatus(folderId: String, status: String)

    /**
     * Пометить все переданные папки как synced.
     */
    @Query("UPDATE folders SET syncStatus = 'synced' WHERE id IN (:ids)")
    suspend fun markFoldersSynced(ids: List<String>)

    /**
     * Пометить все переданные папки как pending.
     */
    @Query("UPDATE folders SET syncStatus = 'pending' WHERE id IN (:ids)")
    suspend fun markFoldersPending(ids: List<String>)

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Update
    suspend fun updateFolders(folders: List<FolderEntity>)

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderById(id: String)

    // ============================================================
    // СЧЁТЧИКИ
    // ============================================================

    /**
     * ВАЖНО: считаем только НЕархивные предметы.
     * Архивные «висят» с тем же parentId, но в папке их не видно.
     */
    @Query("SELECT COUNT(*) FROM items WHERE parentId = :folderId AND isArchived = 0")
    suspend fun getItemCountInFolder(folderId: String): Int

    @Query("SELECT COUNT(*) FROM folders WHERE parentId = :folderId")
    suspend fun getSubfolderCountInFolder(folderId: String): Int

    // ============================================================
    // ПЕРЕМЕЩЕНИЯ
    // ============================================================

    /**
     * Перенос всех подпапок из папки в корень.
     * Используется при удалении папки (защита от «висячих» подпапок).
     */
    @Query("UPDATE folders SET parentId = NULL WHERE parentId = :folderId")
    suspend fun moveSubfoldersToRoot(folderId: String)

    // ============================================================
    // ВЛОЖЕННОСТЬ (parentItemId) — папка внутри предмета
    // ============================================================

    /**
     * Прямые дочерние папки предмета-родителя.
     * Используется в карточке предмета — секция «📦 Вложенные».
     */
    @Query("""
        SELECT * FROM folders 
        WHERE parentItemId = :parentItemId 
        ORDER BY name ASC
    """)
    suspend fun getFoldersByParentItem(parentItemId: String): List<FolderEntity>

    /**
     * Есть ли у предмета дочерние папки (прямые).
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM folders 
            WHERE parentItemId = :itemId
        )
    """)
    suspend fun hasFolderChildren(itemId: String): Boolean

    /**
     * Количество прямых дочерних папок предмета.
     */
    @Query("""
        SELECT COUNT(*) FROM folders 
        WHERE parentItemId = :itemId
    """)
    suspend fun getFolderChildCount(itemId: String): Int

    /**
     * Отвязать все прямые дочерние папки от предмета-родителя.
     * Используется при «удалить/архивировать родителя, отвязав детей».
     * После отвязки папки становятся корневыми (parentId = NULL, parentItemId = NULL).
     */
    @Query("""
        UPDATE folders 
        SET parentId = NULL, parentItemId = NULL, updatedAt = :date 
        WHERE parentItemId = :parentItemId
    """)
    suspend fun clearFolderParentItem(parentItemId: String, date: Long)

    /**
     * Все потомки-папки рекурсивно (прямые + вложенные на любую глубину).
     * Возвращает только id — для каскадных операций.
     */
    @Query("""
        WITH RECURSIVE descendants(id) AS (
            SELECT id FROM folders WHERE parentItemId = :rootId
            UNION ALL
            SELECT f.id FROM folders f
            INNER JOIN descendants d ON f.parentItemId = d.id
        )
        SELECT id FROM descendants
    """)
    suspend fun getAllFolderDescendantIds(rootId: String): List<String>

    /**
     * Найти все папки, которые вложены в предмет напрямую
     * (нужно для каскадного удаления предмета).
     */
    @Query("SELECT * FROM folders WHERE parentItemId IS NOT NULL")
    suspend fun getAllNestedFolders(): List<FolderEntity>

    // ============================================================
    // РЕМОНТ БАЗЫ: ОСИРОТЕВШИЕ ВЛОЖЕННЫЕ ПАПКИ
    // ============================================================

    @Query("""
        SELECT * FROM folders 
        WHERE parentItemId IS NOT NULL 
          AND parentItemId NOT IN (SELECT id FROM items)
    """)
    suspend fun getOrphanNestedFolders(): List<FolderEntity>

    @Query("""
        UPDATE folders 
        SET parentId = NULL, parentItemId = NULL 
        WHERE parentItemId IS NOT NULL 
          AND parentItemId NOT IN (SELECT id FROM items)
    """)
    suspend fun fixOrphanNestedFolders()
}
