package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.TaskEntity

@Dao
interface TaskDao {

    // ============================================================
    // ЧТЕНИЕ
    // Фильтр isDeleted = 0 обязателен — tombstones не показываем.
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

    /**
     * Задача по id БЕЗ фильтра isDeleted.
     * Нужна для:
     *  - TaskReminderReceiver (должен увидеть, что задача уже tombstone);
     *  - mergeData при сравнении версий.
     * Если нужна именно «живая» — используй getLiveTaskById.
     */
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    /**
     * Задача по id, только если она не tombstone.
     * Для UI-операций.
     */
    @Query("SELECT * FROM tasks WHERE id = :id AND isDeleted = 0")
    suspend fun getLiveById(id: String): TaskEntity?

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
     * Включает tombstones, чтобы merge не «воскрешал» их на чужой стороне.
     */
    @Query("SELECT id FROM tasks")
    suspend fun getAllIds(): List<String>

    /**
     * AlarmManager: ВСЕ задачи, блокирующий вызов (не suspend).
     *
     * Нужен BootReceiver.onReceive — там нельзя запускать корутину,
     * а runBlocking с suspend-функцией Room может дать ANR.
     * Возвращает задачи без фильтров, включая soft-deleted и выполненные —
     * фильтрацию делает вызывающий код (rescheduleAll).
     */
    @Query("SELECT * FROM tasks")
    fun getAllTasksBlocking(): List<TaskEntity>

    // ============================================================
    // СИНХРОНИЗАЦИЯ
    // ============================================================

    /**
     * Все задачи со статусом pending — для SyncManager.
     * Включает tombstones (они тоже pending, пока не уехали).
     */
    @Query("SELECT * FROM tasks WHERE syncStatus = 'pending'")
    suspend fun getTasksBySyncStatusPending(): List<TaskEntity>

    @Query("UPDATE tasks SET syncStatus = :status WHERE id = :taskId")
    suspend fun updateSyncStatus(taskId: String, status: String)

    @Query("UPDATE tasks SET syncStatus = 'synced' WHERE id IN (:ids)")
    suspend fun markTasksSynced(ids: List<String>)

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

    /** Прямое удаление — только для cleanup tombstones. */
    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()

    // ============================================================
    // ПОВТОРЫ
    // ============================================================

    /**
     * Клоны, созданные от указанной задачи-родителя.
     * Tombstones-клоны не возвращаем — они удалены.
     */
    @Query("SELECT * FROM tasks WHERE recurrenceSource = :sourceId AND isDeleted = 0")
    suspend fun getClonesOf(sourceId: String): List<TaskEntity>

    // ============================================================
    // 🆕 v19 (№10): TOMBSTONES
    // ============================================================

    /**
     * Пометить задачу как удалённую (tombstone).
     *
     * syncStatus = 'pending' → уедет через outbox.
     * deleteConfirmed = 0 → ещё не подтверждено PUSH'ем.
     */
    @Query("""
        UPDATE tasks SET
            isDeleted = 1,
            deletedAt = :date,
            deletedBy = :user,
            deleteConfirmed = 0,
            updatedAt = :date,
            syncStatus = 'pending'
        WHERE id = :taskId
    """)
    suspend fun markTaskDeleted(taskId: String, date: Long, user: String?)

    /**
     * Подтвердить tombstone после успешного PUSH в changelog.
     */
    @Query("UPDATE tasks SET deleteConfirmed = 1 WHERE id = :taskId")
    suspend fun confirmTaskDeleted(taskId: String)

    /**
     * Подтверждённые tombstones старше указанного времени — для cleanup.
     */
    @Query("""
        SELECT id FROM tasks
        WHERE isDeleted = 1
          AND deleteConfirmed = 1
          AND deletedAt IS NOT NULL
          AND deletedAt < :thresholdMs
    """)
    suspend fun getConfirmedTasksOlderThan(thresholdMs: Long): List<String>

    /**
     * Физическое удаление подтверждённых tombstones по списку id.
     */
    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun hardDeleteConfirmedTasks(ids: List<String>)

    /**
     * Все tombstones — для диагностики.
     */
    @Query("SELECT * FROM tasks WHERE isDeleted = 1")
    suspend fun getAllTombstones(): List<TaskEntity>
}
