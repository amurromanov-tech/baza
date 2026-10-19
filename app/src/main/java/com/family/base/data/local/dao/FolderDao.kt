package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.FolderEntity

@Dao
interface FolderDao {

    // ============================================================
    // ЧТЕНИЕ
    // Фильтр isDeleted = 0 обязателен — tombstones не показываем.
    // ============================================================

    /**
     * Корневые папки (parentId IS NULL, parentItemId IS NULL).
     * Tombstones исключены.
     */
    @Query("""
        SELECT * FROM folders 
        WHERE parentId IS NULL AND parentItemId IS NULL AND isDeleted = 0
        ORDER BY name ASC
    """)
    suspend fun getRootFolders(): List<FolderEntity>

    /**
     * Папки по parentId (родитель-папка).
     * Поддерживает null (IS NULL) и конкретный parentId.
     * Возвращает только те, у которых parentItemId IS NULL
     * (т.е. не вложенные в предмет).
     * Tombstones исключены.
     */
    @Query("""
        SELECT * FROM folders 
        WHERE ((parentId IS NULL AND :parentId IS NULL) OR parentId = :parentId)
          AND parentItemId IS NULL
          AND isDeleted = 0
        ORDER BY name ASC
    """)
    suspend fun getFoldersByParent(parentId: String?): List<FolderEntity>

    /**
     * Папка по id БЕЗ фильтра isDeleted — нужна для синка,
     * для merge и для проверок существования.
     * Если нужна именно «живая» папка — используй getLiveFolderById.
     */
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolderById(id: String): FolderEntity?

    /**
     * Папка по id, только если она не tombstone.
     * Для UI-операций, которым нельзя работать с удалённой папкой.
     */
    @Query("SELECT * FROM folders WHERE id = :id AND isDeleted = 0")
    suspend fun getLiveFolderById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE path = :path AND isDeleted = 0 LIMIT 1")
    suspend fun getFolderByPath(path: String): FolderEntity?

    /**
     * Все папки БЕЗ фильтра — для синка (должен видеть tombstones).
     */
    @Query("SELECT * FROM folders")
    suspend fun getAllFolders(): List<FolderEntity>

    /**
     * Все «живые» папки — для UI-агрегаций, которые не должны
     * спотыкаться о tombstones.
     */
    @Query("SELECT * FROM folders WHERE isDeleted = 0")
    suspend fun getAllLiveFolders(): List<FolderEntity>

    // ============================================================
    // СИНХРОНИЗАЦИЯ
    // ============================================================

    /**
     * Все папки со статусом pending — для SyncManager.
     * Включает tombstones (они тоже pending, пока не уехали).
     */
    @Query("SELECT * FROM folders WHERE syncStatus = 'pending'")
    suspend fun getFoldersBySyncStatusPending(): List<FolderEntity>

    @Query("UPDATE folders SET syncStatus = :status WHERE id = :folderId")
    suspend fun updateSyncStatus(folderId: String, status: String)

    @Query("UPDATE folders SET syncStatus = 'synced' WHERE id IN (:ids)")
    suspend fun markFoldersSynced(ids: List<String>)

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

    /** Прямое удаление — только для cleanup tombstones. */
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderById(id: String)

    // ============================================================
    // СЧЁТЧИКИ
    // ============================================================

    /**
     * Считаем только НЕархивные и не-tombstone предметы.
     * Архивные «висят» с тем же parentId, но в папке их не видно.
     */
    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE parentId = :folderId 
          AND isArchived = 0 
          AND isDeleted = 0
    """)
    suspend fun getItemCountInFolder(folderId: String): Int

    @Query("SELECT COUNT(*) FROM folders WHERE parentId = :folderId AND isDeleted = 0")
    suspend fun getSubfolderCountInFolder(folderId: String): Int

    // ============================================================
    // ПЕРЕМЕЩЕНИЯ
    // ============================================================

    /**
     * Перенос всех подпапок из папки в корень.
     * Tombstones НЕ трогаем — они и так удалены.
     */
    @Query("UPDATE folders SET parentId = NULL WHERE parentId = :folderId AND isDeleted = 0")
    suspend fun moveSubfoldersToRoot(folderId: String)

    // ============================================================
    // ВЛОЖЕННОСТЬ (parentItemId) — папка внутри предмета
    // ============================================================

    @Query("""
        SELECT * FROM folders 
        WHERE parentItemId = :parentItemId AND isDeleted = 0
        ORDER BY name ASC
    """)
    suspend fun getFoldersByParentItem(parentItemId: String): List<FolderEntity>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM folders 
            WHERE parentItemId = :itemId AND isDeleted = 0
        )
    """)
    suspend fun hasFolderChildren(itemId: String): Boolean

    @Query("""
        SELECT COUNT(*) FROM folders 
        WHERE parentItemId = :itemId AND isDeleted = 0
    """)
    suspend fun getFolderChildCount(itemId: String): Int

    /**
     * Отвязать все прямые дочерние папки от предмета-родителя.
     * Tombstones тоже отвязываются, чтобы не висели с битым parentItemId.
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

    @Query("SELECT * FROM folders WHERE parentItemId IS NOT NULL AND isDeleted = 0")
    suspend fun getAllNestedFolders(): List<FolderEntity>

    // ============================================================
    // РЕМОНТ БАЗЫ: ОСИРОТЕВШИЕ ВЛОЖЕННЫЕ ПАПКИ
    // Tombstones не «осиротевшие» — они удалены, ремонт их не трогает.
    // ============================================================

    @Query("""
        SELECT * FROM folders 
        WHERE parentItemId IS NOT NULL 
          AND isDeleted = 0
          AND parentItemId NOT IN (SELECT id FROM items WHERE isDeleted = 0)
    """)
    suspend fun getOrphanNestedFolders(): List<FolderEntity>

    @Query("""
        UPDATE folders 
        SET parentId = NULL, parentItemId = NULL 
        WHERE parentItemId IS NOT NULL 
          AND isDeleted = 0
          AND parentItemId NOT IN (SELECT id FROM items WHERE isDeleted = 0)
    """)
    suspend fun fixOrphanNestedFolders()

    // ============================================================
    // 🆕 v19 (№10): TOMBSTONES
    // ============================================================

    /**
     * Пометить папку как удалённую (tombstone).
     *
     * Нельзя вызывать для системных папок (isSystem = 1) — проверка
     * на уровне репозитория. Здесь только UPDATE.
     *
     * syncStatus = 'pending' → уедет через outbox.
     * deleteConfirmed = 0 → ещё не подтверждено PUSH'ем.
     */
    @Query("""
        UPDATE folders SET
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
    @Query("UPDATE folders SET deleteConfirmed = 1 WHERE id = :folderId")
    suspend fun confirmFolderDeleted(folderId: String)

    /**
     * Подтверждённые tombstones старше указанного времени — для cleanup.
     */
    @Query("""
        SELECT id FROM folders
        WHERE isDeleted = 1
          AND deleteConfirmed = 1
          AND deletedAt IS NOT NULL
          AND deletedAt < :thresholdMs
    """)
    suspend fun getConfirmedFoldersOlderThan(thresholdMs: Long): List<String>

    /**
     * Физическое удаление подтверждённых tombstones по списку id.
     */
    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun hardDeleteConfirmedFolders(ids: List<String>)

    /**
     * Все tombstones — для диагностики.
     */
    @Query("SELECT * FROM folders WHERE isDeleted = 1")
    suspend fun getAllTombstones(): List<FolderEntity>
}
