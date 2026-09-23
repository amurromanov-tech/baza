package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.ItemEntity

@Dao
interface ItemDao {

    // ============================================================
    // ОСНОВНЫЕ ОПЕРАЦИИ
    // ============================================================

    @Insert
    suspend fun insertItem(item: ItemEntity)

    @Update
    suspend fun updateItem(item: ItemEntity)

    @Delete
    suspend fun deleteItem(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItemById(id: String): ItemEntity?

    // ============================================================
    // ЗАПРОСЫ БЕЗ АРХИВА (ДЛЯ ОСНОВНОГО СПИСКА)
    // ============================================================

    @Query("SELECT * FROM items WHERE isArchived = 0")
    suspend fun getAllItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE parentId = :parentId AND isArchived = 0")
    suspend fun getItemsByParent(parentId: String?): List<ItemEntity>

    @Query("SELECT * FROM items WHERE addedDate BETWEEN :startDate AND :endDate AND isArchived = 0")
    suspend fun getItemsByDateRange(startDate: Long, endDate: Long): List<ItemEntity>

    // ============================================================
    // ЗАПРОСЫ БЕЗ ФИЛЬТРА АРХИВА (ДЛЯ СИНХРОНИЗАЦИИ)
    // ============================================================

    @Query("SELECT * FROM items")
    suspend fun getAllItemsRaw(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE parentId = :parentId")
    suspend fun getItemsByParentRaw(parentId: String?): List<ItemEntity>

    // ============================================================
    // ОБНОВЛЕНИЕ КОЛИЧЕСТВА
    // ============================================================

    @Query("UPDATE items SET quantity = :qty WHERE id = :itemId")
    suspend fun updateQuantity(itemId: String, qty: Int)

    // ============================================================
    // АРХИВАЦИЯ
    // ============================================================

    // Заархивировать предмет
    @Query("""
        UPDATE items SET 
            isArchived = 1, 
            archivedReason = :reason, 
            archivedDate = :date, 
            archivedNote = :note,
            updatedDate = :date
        WHERE id = :itemId
    """)
    suspend fun archiveItem(
        itemId: String,
        reason: String,
        date: Long,
        note: String?
    )

    // Разархивировать предмет (вернуть из архива)
    @Query("""
        UPDATE items SET 
            isArchived = 0, 
            archivedReason = NULL, 
            archivedDate = NULL, 
            archivedNote = NULL,
            updatedDate = :date
        WHERE id = :itemId
    """)
    suspend fun unarchiveItem(itemId: String, date: Long)

    // ============================================================
    // ЗАПРОСЫ К АРХИВУ
    // ============================================================

    // Все архивные предметы
    @Query("SELECT * FROM items WHERE isArchived = 1 ORDER BY archivedDate DESC")
    suspend fun getArchivedItems(): List<ItemEntity>

    // Архивные предметы по причине
    @Query("SELECT * FROM items WHERE isArchived = 1 AND archivedReason = :reason ORDER BY archivedDate DESC")
    suspend fun getArchivedItemsByReason(reason: String): List<ItemEntity>

    // ============================================================
    // СТАТИСТИКА АРХИВА
    // ============================================================

    // Общая сумма архива (все категории)
    @Query("SELECT SUM(price * quantity) FROM items WHERE isArchived = 1 AND price IS NOT NULL")
    suspend fun getTotalArchivedSum(): Double?

    // Сумма текущих предметов (не в архиве)
    @Query("SELECT SUM(price * quantity) FROM items WHERE isArchived = 0 AND price IS NOT NULL")
    suspend fun getTotalActiveSum(): Double?

    // Сумма архива по причинам
    @Query("""
        SELECT archivedReason, SUM(price * quantity) as total
        FROM items 
        WHERE isArchived = 1 AND price IS NOT NULL 
        GROUP BY archivedReason
    """)
    suspend fun getArchivedStatsByReason(): List<ArchiveStatsRow>

    // Количество архивных предметов
    @Query("SELECT COUNT(*) FROM items WHERE isArchived = 1")
    suspend fun getArchivedItemsCount(): Int

    // Количество текущих предметов
    @Query("SELECT COUNT(*) FROM items WHERE isArchived = 0")
    suspend fun getActiveItemsCount(): Int
}

// ============================================================
// МОДЕЛЬ ДЛЯ СТАТИСТИКИ АРХИВА
// ============================================================
data class ArchiveStatsRow(
    val archivedReason: String?,
    val total: Double?
)
