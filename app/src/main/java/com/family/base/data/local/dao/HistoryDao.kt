package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.HistoryEntry

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE itemId = :itemId ORDER BY changedAt DESC")
    suspend fun getHistoryForItem(itemId: String): List<HistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: HistoryEntry)
}