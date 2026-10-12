package com.family.base.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.family.base.data.local.entity.OutboxEntity

@Dao
interface OutboxDao {

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    /**
     * Добавить одно изменение в очередь.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: OutboxEntity)

    /**
     * Добавить пачку изменений (например, при массовых операциях).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<OutboxEntity>)

    @Update
    suspend fun update(entry: OutboxEntity)

    // ============================================================
    // ЧТЕНИЕ
    // ============================================================

    /**
     * Все несинхронизированные изменения, по возрастанию времени.
     * SyncManager берёт их для формирования пакета.
     */
    @Query("SELECT * FROM outbox WHERE isSynced = 0 ORDER BY createdAt ASC")
    suspend fun getPending(): List<OutboxEntity>

    /**
     * Количество несинхронизированных изменений.
     * Для индикатора в UI.
     */
    @Query("SELECT COUNT(*) FROM outbox WHERE isSynced = 0")
    suspend fun getPendingCount(): Int

    /**
     * Синхронный счётчик для LiveData-обёртки.
     */
    @Query("SELECT COUNT(*) FROM outbox WHERE isSynced = 0")
    fun getPendingCountLive(): Int

    /**
     * Все изменения по конкретной сущности (для отладки/схлопывания).
     */
    @Query("SELECT * FROM outbox WHERE entityType = :type AND entityId = :entityId ORDER BY createdAt ASC")
    suspend fun getByEntity(type: String, entityId: String): List<OutboxEntity>

    /**
     * Все записи, попавшие в конкретный пакет changelog.
     */
    @Query("SELECT * FROM outbox WHERE batchId = :batchId ORDER BY createdAt ASC")
    suspend fun getByBatch(batchId: String): List<OutboxEntity>

    // ============================================================
    // ОТПРАВКА
    // ============================================================

    /**
     * Пометить одну запись как отправленную.
     */
    @Query("UPDATE outbox SET isSynced = 1, syncedAt = :syncedAt, batchId = :batchId WHERE id = :id")
    suspend fun markSynced(id: String, syncedAt: Long, batchId: String)

    /**
     * Пометить пачку записей как отправленные (по списку id).
     * Room не поддерживает IN с List<String> автоматически —
     * используем отдельные вызовы или @Transaction в репозитории.
     */
    @Query("UPDATE outbox SET isSynced = 1, syncedAt = :syncedAt, batchId = :batchId WHERE id IN (:ids)")
    suspend fun markSyncedBatch(ids: List<String>, syncedAt: Long, batchId: String)

    // ============================================================
    // ОЧИСТКА
    // ============================================================

    /**
     * Удалить все отправленные записи старше указанного времени.
     * Вызывается после успешного синка, чтобы не копить мусор.
     */
    @Query("DELETE FROM outbox WHERE isSynced = 1 AND syncedAt IS NOT NULL AND syncedAt < :olderThan")
    suspend fun deleteSyncedOlderThan(olderThan: Long)

    /**
     * Полная очистка (для отладки / сброса).
     */
    @Query("DELETE FROM outbox")
    suspend fun clear()

    /**
     * Удалить конкретную запись (например, если операция отменилась).
     */
    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: String)
}
