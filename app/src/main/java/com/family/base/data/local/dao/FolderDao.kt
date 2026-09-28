package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.FolderEntity

@Dao
interface FolderDao {
    /**
     * Получение корневых папок (parentId IS NULL)
     */
    @Query("SELECT * FROM folders WHERE parentId IS NULL ORDER BY name ASC")
    suspend fun getRootFolders(): List<FolderEntity>

    /**
     * Получение папок по parentId.
     * Поддерживает null (IS NULL) и конкретный parentId.
     */
    @Query("SELECT * FROM folders WHERE (parentId IS NULL AND :parentId IS NULL) OR parentId = :parentId ORDER BY name ASC")
    suspend fun getFoldersByParent(parentId: String?): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolderById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE path = :path LIMIT 1")
    suspend fun getFolderByPath(path: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderById(id: String)

    /**
     * ВАЖНО: считаем только НЕархивные предметы.
     * Архивные «висят» с тем же parentId, но в папке их не видно.
     */
    @Query("SELECT COUNT(*) FROM items WHERE parentId = :folderId AND isArchived = 0")
    suspend fun getItemCountInFolder(folderId: String): Int

    @Query("SELECT COUNT(*) FROM folders WHERE parentId = :folderId")
    suspend fun getSubfolderCountInFolder(folderId: String): Int

    /**
     * Перенос всех подпапок из папки в корень.
     * Используется при удалении папки (защита от «висячих» подпапок).
     */
    @Query("UPDATE folders SET parentId = NULL WHERE parentId = :folderId")
    suspend fun moveSubfoldersToRoot(folderId: String)

    @Query("SELECT * FROM folders")
    suspend fun getAllFolders(): List<FolderEntity>
}
