package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.HistoryEntry

@Dao
interface HistoryDao {

    // ============================================================
    // БАЗОВЫЕ ОПЕРАЦИИ
    // ============================================================

    @Insert
    suspend fun insertEntry(entry: HistoryEntry)

    @Delete
    suspend fun deleteEntry(entry: HistoryEntry)

    @Query("DELETE FROM history")
    suspend fun deleteAllEntries()

    // ============================================================
    // ВЫБОРКИ ДЛЯ КАРТОЧКИ ПРЕДМЕТА
    // ============================================================

    @Query("SELECT * FROM history WHERE itemId = :itemId ORDER BY changedAt DESC")
    suspend fun getHistoryForItem(itemId: String): List<HistoryEntry>

    // ============================================================
    // ВЫБОРКИ ДЛЯ ЭКРАНА «ИСТОРИЯ ИЗМЕНЕНИЙ»
    // ============================================================

    /**
     * Вся история, новые сверху. Без лимита.
     */
    @Query("SELECT * FROM history ORDER BY changedAt DESC")
    suspend fun getAllEntries(): List<HistoryEntry>

    /**
     * Фильтр по пользователю.
     */
    @Query("""
        SELECT * FROM history 
        WHERE changedBy = :user 
        ORDER BY changedAt DESC
    """)
    suspend fun getEntriesByUser(user: String): List<HistoryEntry>

    /**
     * Фильтр по действию.
     */
    @Query("""
        SELECT * FROM history 
        WHERE action = :action 
        ORDER BY changedAt DESC
    """)
    suspend fun getEntriesByAction(action: String): List<HistoryEntry>

    /**
     * Фильтр по пользователю + действию.
     */
    @Query("""
        SELECT * FROM history 
        WHERE changedBy = :user AND action = :action 
        ORDER BY changedAt DESC
    """)
    suspend fun getEntriesByUserAndAction(user: String, action: String): List<HistoryEntry>

    /**
     * Фильтр по дате (changedAt >= since).
     */
    @Query("""
        SELECT * FROM history 
        WHERE changedAt >= :since 
        ORDER BY changedAt DESC
    """)
    suspend fun getEntriesSince(since: Long): List<HistoryEntry>

    /**
     * Фильтр по пользователю + дате.
     */
    @Query("""
        SELECT * FROM history 
        WHERE changedBy = :user AND changedAt >= :since 
        ORDER BY changedAt DESC
    """)
    suspend fun getEntriesByUserSince(user: String, since: Long): List<HistoryEntry>

    /**
     * Фильтр по действию + дате.
     */
    @Query("""
        SELECT * FROM history 
        WHERE action = :action AND changedAt >= :since 
        ORDER BY changedAt DESC
    """)
    suspend fun getEntriesByActionSince(action: String, since: Long): List<HistoryEntry>

    /**
     * Фильтр по пользователю + действию + дате.
     */
    @Query("""
        SELECT * FROM history 
        WHERE changedBy = :user AND action = :action AND changedAt >= :since 
        ORDER BY changedAt DESC
    """)
    suspend fun getEntriesByUserAndActionSince(
        user: String,
        action: String,
        since: Long
    ): List<HistoryEntry>

    // ============================================================
    // СПРАВОЧНЫЕ ЗАПРОСЫ
    // ============================================================

    /**
     * Список всех пользователей, которые когда-либо делали изменения.
     * Для dropdown «Пользователь».
     */
    @Query("SELECT DISTINCT changedBy FROM history ORDER BY changedBy ASC")
    suspend fun getDistinctUsers(): List<String>

    /**
     * Список всех action, которые встречаются в истории.
     * Для dropdown «Действие».
     */
    @Query("SELECT DISTINCT action FROM history ORDER BY action ASC")
    suspend fun getDistinctActions(): List<String>

    /**
     * Общее количество записей.
     */
    @Query("SELECT COUNT(*) FROM history")
    suspend fun getTotalCount(): Int

    /**
     * Записи по конкретному предмету (с учётом фильтров) — для будущего.
     */
    @Query("""
        SELECT * FROM history 
        WHERE itemId = :itemId 
        ORDER BY changedAt DESC
    """)
    suspend fun getEntriesForItem(itemId: String): List<HistoryEntry>
}
