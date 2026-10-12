package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.TaskEntity

@Dao
interface TaskDao {

    // ============================================================
    // ЧТЕНИЕ
    // ============================================================

    /**
     * Все НЕ удалённые задачи (soft-delete фильтруется).
     * Сортировка: сначала невыполненные, потом по приоритету (URGENT сверху),
     * потом по дедлайну (ближайшие сверху, NULL в конце),
     * потом по sortOrder, потом по дате создания.
     */
    @Query("""
        SELECT * FROM tasks
        WHERE isDeleted = 0
        ORDER BY
            isDone ASC,
            priority DESC,
            CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END ASC,
            dueDate ASC,
            sortOrder ASC,
            createdAt ASC
    """)
    suspend fun getAllActiveSorted(): List<TaskEntity>

    /**
     * Все задачи (включая выполненные, исключая soft-deleted).
     * Для фильтра «Все».
     */
    @Query("""
        SELECT * FROM tasks
        WHERE isDeleted = 0
        ORDER BY
            isDone ASC,
            priority DESC,
            CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END ASC,
            dueDate ASC,
            sortOrder ASC,
            createdAt ASC
    """)
    suspend fun getAllSorted(): List<TaskEntity>

    /**
     * Только активные (не выполненные).
     */
    @Query("""
        SELECT * FROM tasks
        WHERE isDeleted = 0 AND isDone = 0
        ORDER BY
            priority DESC,
            CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END ASC,
            dueDate ASC,
            sortOrder ASC,
            createdAt ASC
    """)
    suspend fun getActiveSorted(): List<TaskEntity>

    /**
     * Только выполненные.
     */
    @Query("""
        SELECT * FROM tasks
        WHERE isDeleted = 0 AND isDone = 1
        ORDER BY doneAt DESC
    """)
    suspend fun getDoneSorted(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    /**
     * Счётчик активных задач — для бейджа на системной папке.
     */
    @Query("SELECT COUNT(*) FROM tasks WHERE isDeleted = 0 AND isDone = 0")
    suspend fun getActiveCount(): Int

    /**
     * Все задачи без фильтров — для синка (включая soft-deleted).
     */
    @Query("SELECT * FROM tasks")
    suspend fun getAllForSync(): List<TaskEntity>

    /**
     * Только id — для merge-then-upload (union по id).
     */
    @Query("SELECT id FROM tasks")
    suspend fun getAllIds(): List<String>

    // ============================================================
    // 🆕 v15: СИНХРОНИЗАЦИЯ
    // ============================================================

    /**
     * Все задачи со статусом pending — для SyncManager.
     */
    @Query("SELECT * FROM tasks WHERE syncStatus = 'pending'")
    suspend fun getTasksBySyncStatusPending(): List<TaskEntity>

    /**
     * Обновить syncStatus у одной задачи.
     */
    @Query("UPDATE tasks SET syncStatus = :status WHERE id = :taskId")
    suspend fun updateSyncStatus(taskId: String, status: String)

    /**
     * Пометить все переданные задачи как synced.
     */
    @Query("UPDATE tasks SET syncStatus = 'synced' WHERE id IN (:ids)")
    suspend fun markTasksSynced(ids: List<String>)

    /**
     * Пометить все переданные задачи как pending.
     */
    @Query("UPDATE tasks SET syncStatus = 'pending' WHERE id IN (:ids)")
    suspend fun markTasksPending(ids: List<String>)

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<TaskEntity>)

    /**
     * Вставка с игнорированием конфликтов — для синка
     * (не перезаписываем локальные более свежие версии).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(tasks: List<TaskEntity>)

    @Update
    suspend fun update(task: TaskEntity)

    @Update
    suspend fun updateAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()

    // ============================================================
    // ПОВТОРЫ
    // ============================================================

    /**
     * Клоны, созданные от указанной задачи-родителя.
     */
    @Query("SELECT * FROM tasks WHERE recurrenceSource = :sourceId")
    suspend fun getClonesOf(sourceId: String): List<TaskEntity>
}
