package com.family.base.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.family.base.data.local.entity.SyncQueueEntity

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToQueue(entry: SyncQueueEntity)

    @Delete
    suspend fun removeFromQueue(entry: SyncQueueEntity)

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun removeFromQueueById(id: String)

    @Query("DELETE FROM sync_queue WHERE entityId = :entityId")
    suspend fun removeByEntityId(entityId: String)

    @Query("SELECT * FROM sync_queue ORDER BY timestamp ASC")
    suspend fun getAllPending(): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE entityType = :type ORDER BY timestamp ASC")
    suspend fun getPendingByType(type: String): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue")
    suspend fun getPendingCount(): Int

    @Query("DELETE FROM sync_queue")
    suspend fun clearAll()
}
