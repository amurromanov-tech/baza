package com.family.base.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.family.base.data.local.entity.ProcessedChangelogEntity

@Dao
interface ProcessedChangelogDao {

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    /**
     * Отметить пакет как обработанный.
     * REPLACE — на случай повторной вставки (не страшно).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ProcessedChangelogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ProcessedChangelogEntity>)

    // ============================================================
    // ПРОВЕРКА
    // ============================================================

    /**
     * Был ли этот пакет уже обработан?
     */
    @Query("SELECT EXISTS(SELECT 1 FROM processed_changelogs WHERE batchId = :batchId)")
    suspend fun isProcessed(batchId: String): Boolean

    /**
     * Получить запись об обработке (для отладки).
     */
    @Query("SELECT * FROM processed_changelogs WHERE batchId = :batchId")
    suspend fun getByBatchId(batchId: String): ProcessedChangelogEntity?

    /**
     * Все batchId, которые уже обработаны — для быстрой фильтрации
     * списка файлов с Диска.
     */
    @Query("SELECT batchId FROM processed_changelogs")
    suspend fun getAllBatchIds(): List<String>

    /**
     * Все обработанные пакеты с конкретного устройства.
     */
    @Query("SELECT * FROM processed_changelogs WHERE deviceId = :deviceId ORDER BY processedAt DESC")
    suspend fun getByDeviceId(deviceId: String): List<ProcessedChangelogEntity>

    // ============================================================
    // СТАТИСТИКА
    // ============================================================

    /**
     * Сколько всего обработано.
     */
    @Query("SELECT COUNT(*) FROM processed_changelogs")
    suspend fun getCount(): Int

    /**
     * Время последнего обработчика (max processedAt).
     * NULL — если ничего не обрабатывалось.
     */
    @Query("SELECT MAX(processedAt) FROM processed_changelogs")
    suspend fun getLastProcessedAt(): Long?

    // ============================================================
    // 🆕 v17.3.0: ОЧИСТКА ЧУЖИХ ФАЙЛОВ НА ДИСКЕ
    // ============================================================

    /**
     * 🆕 v17.3.0 (cleanup): batchId записей, обработанных РАНЬШЕ cutoff.
     *
     * Используется в SyncManager.cleanup, чтобы безопасно удалять
     * с Диска файлы ЧУЖИХ устройств:
     *  - файл старше PROCESSED_TTL_MS (30 дней);
     *  - его batchId уже есть в processed_changelogs (значит, мы его
     *    применили и он нам больше не нужен);
     *  - значит, его можно удалить и с Диска — другие устройства
     *    его тоже давно обработали (или он им не нужен).
     *
     * ⚠️ Удаляем только те, что обработаны локально. Если файл есть
     * на Диске, но мы его ещё не обработали (например, не докачали
     * из-за 500) — он НЕ попадёт в этот список, и cleanup его не тронет.
     */
    @Query("SELECT batchId FROM processed_changelogs WHERE processedAt < :cutoff")
    suspend fun getProcessedOlderThan(cutoff: Long): List<String>

    // ============================================================
    // ОЧИСТКА
    // ============================================================

    /**
     * Удалить старые записи об обработке.
     * Вызывается редко (например, при старте раз в неделю).
     */
    @Query("DELETE FROM processed_changelogs WHERE processedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    /**
     * Полная очистка (для отладки / сброса).
     */
    @Query("DELETE FROM processed_changelogs")
    suspend fun clear()
}
