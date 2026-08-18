package com.family.base.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * DAO для работы с информацией о синхронизации
 * Хранит last_modified — время последней синхронизации с Яндекс.Диском
 */
@Dao
interface SyncInfoDao {
    @Query("SELECT value FROM sync_info WHERE key = 'last_modified' LIMIT 1")
    suspend fun getLastModifiedRaw(): Long?

    suspend fun getLastModified(): Long {
        return getLastModifiedRaw() ?: 0L
    }

    @Query("INSERT OR REPLACE INTO sync_info (key, value) VALUES ('last_modified', :timestamp)")
    suspend fun setLastModified(timestamp: Long)
}
