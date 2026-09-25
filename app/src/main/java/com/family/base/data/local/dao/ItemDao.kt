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

    /** Массовая вставка (для чеков и импорта) */
    @Insert
    suspend fun insertItems(items: List<ItemEntity>)

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

    @Query("SELECT * FROM items WHERE isArchived = 1 ORDER BY archivedDate DESC")
    suspend fun getArchivedItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE isArchived = 1 AND archivedReason = :reason ORDER BY archivedDate DESC")
    suspend fun getArchivedItemsByReason(reason: String): List<ItemEntity>

    // ============================================================
    // ЗАЙМ (ВЫДАЧА)
    // ============================================================

    @Query("SELECT * FROM items WHERE isLent = 1 AND isArchived = 0 ORDER BY lentDate DESC")
    suspend fun getLentItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE isLent = 1 AND lentTo = :personName ORDER BY lentDate DESC")
    suspend fun getLentItemsByPerson(personName: String): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE isLent = 1 AND isArchived = 0")
    suspend fun getLentItemsCount(): Int

    // ============================================================
    // СТАТИСТИКА АРХИВА
    // ============================================================

    @Query("SELECT SUM(price * quantity) FROM items WHERE isArchived = 1 AND price IS NOT NULL")
    suspend fun getTotalArchivedSum(): Double?

    @Query("SELECT SUM(price * quantity) FROM items WHERE isArchived = 0 AND price IS NOT NULL")
    suspend fun getTotalActiveSum(): Double?

    @Query("""
        SELECT archivedReason, SUM(price * quantity) as total
        FROM items 
        WHERE isArchived = 1 AND price IS NOT NULL 
        GROUP BY archivedReason
    """)
    suspend fun getArchivedStatsByReason(): List<ArchiveStatsRow>

    @Query("SELECT COUNT(*) FROM items WHERE isArchived = 1")
    suspend fun getArchivedItemsCount(): Int

    @Query("SELECT COUNT(*) FROM items WHERE isArchived = 0")
    suspend fun getActiveItemsCount(): Int

    // ============================================================
    // СТАТИСТИКА ЗАЙМА
    // ============================================================

    @Query("SELECT SUM(price * quantity) FROM items WHERE isLent = 1 AND isArchived = 0 AND price IS NOT NULL")
    suspend fun getTotalLentSum(): Double?

    @Query("SELECT DISTINCT lentTo FROM items WHERE isLent = 1 AND lentTo IS NOT NULL AND isArchived = 0")
    suspend fun getLentPersons(): List<String>

    // ============================================================
    // ПОИСК ПО ШТРИХ-КОДУ / QR-КОДУ
    // ============================================================

    @Query("SELECT * FROM items WHERE barcode = :barcode AND isArchived = 0")
    suspend fun getItemsByBarcode(barcode: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE barcode = :barcode")
    suspend fun getItemsByBarcodeRaw(barcode: String): List<ItemEntity>

    // ============================================================
    // АУДИТ БАЗЫ
    // ============================================================

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND (price IS NULL OR price = 0)
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutPrice(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND expiryDate IS NULL
          AND (itemType = 'food' OR itemType = 'medicine')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutExpiry(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND (barcode IS NULL OR barcode = '')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutBarcode(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND (description IS NULL OR description = '')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutDescription(): List<ItemEntity>

    /**
     * Без типа: только NULL или пустая строка.
     * «thing», «other», «food», «medicine» — все считаются указанными.
     */
    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND (itemType IS NULL OR itemType = '')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutType(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND isLent = 1 
          AND lentDate IS NOT NULL 
          AND lentDate < :thresholdDate
        ORDER BY lentDate ASC
    """)
    suspend fun getItemsLentLongAgo(thresholdDate: Long): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND expiryDate IS NOT NULL 
          AND expiryDate < :now
        ORDER BY expiryDate ASC
    """)
    suspend fun getExpiredItems(now: Long): List<ItemEntity>

    // -------- ДУБЛИКАТЫ --------

    @Query("""
        SELECT barcode FROM items
        WHERE isArchived = 0
          AND barcode IS NOT NULL
          AND barcode != ''
        GROUP BY barcode
        HAVING COUNT(*) > 1
    """)
    suspend fun getDuplicateBarcodes(): List<String>

    @Query("""
        SELECT * FROM items
        WHERE isArchived = 0
          AND barcode = :barcode
        ORDER BY addedDate ASC
    """)
    suspend fun getItemsBySameBarcode(barcode: String): List<ItemEntity>

    // -------- СЧЁТЧИКИ --------

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND (price IS NULL OR price = 0)
    """)
    suspend fun countItemsWithoutPrice(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND expiryDate IS NULL
          AND (itemType = 'food' OR itemType = 'medicine')
    """)
    suspend fun countItemsWithoutExpiry(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND (barcode IS NULL OR barcode = '')
    """)
    suspend fun countItemsWithoutBarcode(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND (description IS NULL OR description = '')
    """)
    suspend fun countItemsWithoutDescription(): Int

    /**
     * Без типа: только NULL или пустая строка.
     */
    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND (itemType IS NULL OR itemType = '')
    """)
    suspend fun countItemsWithoutType(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND isLent = 1 
          AND lentDate IS NOT NULL 
          AND lentDate < :thresholdDate
    """)
    suspend fun countItemsLentLongAgo(thresholdDate: Long): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND expiryDate IS NOT NULL 
          AND expiryDate < :now
    """)
    suspend fun countExpiredItems(now: Long): Int

    @Query("""
        SELECT COUNT(*) FROM items
        WHERE isArchived = 0
          AND barcode IS NOT NULL
          AND barcode != ''
          AND barcode IN (
              SELECT barcode FROM items
              WHERE isArchived = 0
                AND barcode IS NOT NULL
                AND barcode != ''
              GROUP BY barcode
              HAVING COUNT(*) > 1
          )
    """)
    suspend fun countDuplicateBarcodeItems(): Int
}

// ============================================================
// МОДЕЛЬ ДЛЯ СТАТИСТИКИ АРХИВА
// ============================================================
data class ArchiveStatsRow(
    val archivedReason: String?,
    val total: Double?
)
