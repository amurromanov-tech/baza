package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.HistoryEntry

@Dao
interface HistoryDao {

    @Insert
    suspend fun insertEntry(entry: HistoryEntry)

    @Delete
    suspend fun deleteEntry(entry: HistoryEntry)

    @Query("DELETE FROM history")
    suspend fun deleteAllEntries()

    @Query("SELECT * FROM history WHERE itemId = :itemId ORDER BY changedAt DESC")
    suspend fun getHistoryForItem(itemId: String): List<HistoryEntry>

    @Query("SELECT * FROM history ORDER BY changedAt DESC")
    suspend fun getAllEntries(): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE changedBy = :user ORDER BY changedAt DESC")
    suspend fun getEntriesByUser(user: String): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE action = :action ORDER BY changedAt DESC")
    suspend fun getEntriesByAction(action: String): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE changedBy = :user AND action = :action ORDER BY changedAt DESC")
    suspend fun getEntriesByUserAndAction(user: String, action: String): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE changedAt >= :since ORDER BY changedAt DESC")
    suspend fun getEntriesSince(since: Long): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE changedBy = :user AND changedAt >= :since ORDER BY changedAt DESC")
    suspend fun getEntriesByUserSince(user: String, since: Long): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE action = :action AND changedAt >= :since ORDER BY changedAt DESC")
    suspend fun getEntriesByActionSince(action: String, since: Long): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE changedBy = :user AND action = :action AND changedAt >= :since ORDER BY changedAt DESC")
    suspend fun getEntriesByUserAndActionSince(user: String, action: String, since: Long): List<HistoryEntry>

    @Query("SELECT DISTINCT changedBy FROM history ORDER BY changedBy ASC")
    suspend fun getDistinctUsers(): List<String>

    @Query("SELECT DISTINCT action FROM history ORDER BY action ASC")
    suspend fun getDistinctActions(): List<String>

    @Query("SELECT COUNT(*) FROM history")
    suspend fun getTotalCount(): Int

    @Query("SELECT * FROM history WHERE itemId = :itemId ORDER BY changedAt DESC")
    suspend fun getEntriesForItem(itemId: String): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE itemName IS NULL ORDER BY changedAt DESC")
    suspend fun getEntriesWithoutItemName(): List<HistoryEntry>

    @Query("""
        SELECT * FROM history 
        WHERE (oldValue LIKE '%Тип: %' OR newValue LIKE '%Тип: %')
          AND (oldValue LIKE '%thing%' OR oldValue LIKE '%food%' OR oldValue LIKE '%medicine%' OR oldValue LIKE '%other%'
            OR newValue LIKE '%thing%' OR newValue LIKE '%food%' OR newValue LIKE '%medicine%' OR newValue LIKE '%other%')
        ORDER BY changedAt DESC
    """)
    suspend fun getEntriesWithRawTypeCodes(): List<HistoryEntry>

    @Query("""
        UPDATE history 
        SET itemName = :itemName, 
            oldValue = :oldValue, 
            newValue = :newValue 
        WHERE id = :entryId
    """)
    suspend fun updateEntryFields(
        entryId: String,
        itemName: String?,
        oldValue: String?,
        newValue: String?
    )

    @Query("UPDATE history SET itemName = :itemName WHERE id = :entryId")
    suspend fun updateItemName(entryId: String, itemName: String)

    @Query("SELECT COUNT(*) FROM history WHERE itemName IS NULL")
    suspend fun countEntriesWithoutItemName(): Int

    // ============================================================
    // 🆕 v13.2.0 (B-8): синхронизация истории
    // ============================================================

    /**
     * Пакетная вставка с игнорированием дубликатов по id.
     * Используется при синхронизации — добавляем только новые записи,
     * существующие не перезаписываем.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<HistoryEntry>): List<Long>

    /**
     * Получить все id — для быстрой проверки «есть ли уже такая запись».
     */
    @Query("SELECT id FROM history")
    suspend fun getAllIds(): List<String>
}
