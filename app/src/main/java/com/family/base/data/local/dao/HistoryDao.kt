package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.HistoryEntry

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertEntry(entry: HistoryEntry)

    @Query("SELECT * FROM history WHERE itemId = :itemId ORDER BY changedAt DESC")
    suspend fun getHistoryForItem(itemId: String): List<HistoryEntry>

    @Query("SELECT * FROM history ORDER BY changedAt DESC")
    suspend fun getAllEntries(): List<HistoryEntry>  // <-- ДОБАВЛЕНО

    @Delete
    suspend fun deleteEntry(entry: HistoryEntry)      // <-- ДОБАВЛЕНО

    @Query("DELETE FROM history")
    suspend fun deleteAllEntries()                    // <-- ДОБАВЛЕНО
}
