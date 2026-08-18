package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.ItemEntity

@Dao
interface ItemDao {
    /**
     * Получение предметов по parentId
     * Исправлено: корректно обрабатывает null (IS NULL)
     */
    @Query("SELECT * FROM items WHERE (parentId IS NULL AND :parentId IS NULL) OR parentId = :parentId ORDER BY name ASC")
    suspend fun getItemsByParent(parentId: String?): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItemById(id: String): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ItemEntity)

    @Update
    suspend fun updateItem(item: ItemEntity)

    @Delete
    suspend fun deleteItem(item: ItemEntity)

    @Query("UPDATE items SET quantity = :newQty, updatedDate = :timestamp WHERE id = :itemId")
    suspend fun updateQuantity(itemId: String, newQty: Int, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM items WHERE isExpired = 1")
    suspend fun getExpiredItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE daysUntilExpiry BETWEEN 0 AND :daysThreshold AND isExpired = 0")
    suspend fun getSoonExpiredItems(daysThreshold: Int): List<ItemEntity>

    @Query("SELECT * FROM items")
    suspend fun getAllItems(): List<ItemEntity>
}
