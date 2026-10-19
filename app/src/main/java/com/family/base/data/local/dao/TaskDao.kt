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
     * Если нужна именно «живая» — используй getLiveById.
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
     * ⚠️ v18.2.0 (шаг 1): НЕ вызывать с главного потока!
     * Раньше это было причиной "Cannot access database on the main thread"
     * в BaseApplication.onCreate и BootReceiver.onReceive — rescheduleAll
     * падал в try/catch, и ни одна задача не планировалась.
     *
     * Теперь TaskReminderScheduler оборачивает вызов в
     * runBlocking(Dispatchers.IO). Если вызываешь напрямую — тоже
     * оборачивай в IO-контекст. Предпочтительна suspend-версия
     * getAllTasksSuspend() из корутины.
     */
    @Query("SELECT * FROM tasks")
    fun getAllTasksBlocking(): List<TaskEntity>

    /**
     * 🆕 v18.2.0 (шаг 1): suspend-версия getAllTasksBlocking.
     * Использовать из корутин — не блокирует поток, Room сам
     * выполнит запрос на IO-диспетчере.
     */
    @Query("SELECT * FROM tasks")
    suspend fun getAllTasksSuspend(): List<TaskEntity>

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
    // 🆕 v18.2.0 (шаг 2): НАПОМИНАНИЯ (Путь 2 + Путь 3)
    // ============================================================

    /**
     * 🆕 Путь 2: задачи с точным напоминанием, которое уже наступило
     * (или наступит до `beforeMs`), и которые ещё не выполнены.
     *
     * Используется планировщиком, чтобы понять, какие задачи нужно
     * перепланировать/показать при следующем проходе.
     *
     * @param beforeMs верхняя граница remindAt (обычно now + окно).
     */
    @Query("""
        SELECT * FROM tasks
        WHERE isDeleted = 0
          AND isDone = 0
          AND remindAt IS NOT NULL
          AND remindAt <= :beforeMs
        ORDER BY remindAt ASC
    """)
    suspend fun getTasksWithRemindAtBefore(beforeMs: Long): List<TaskEntity>

    /**
     * 🆕 Путь 2: все задачи с заданным точным напоминанием (для диагностики
     * и для rescheduleAll — чтобы не потерять будущие remindAt).
     */
    @Query("""
        SELECT * FROM tasks
        WHERE isDeleted = 0
          AND isDone = 0
          AND remindAt IS NOT NULL
        ORDER BY remindAt ASC
    """)
    suspend fun getTasksWithRemindAt(): List<TaskEntity>

    /**
     * 🆕 Путь 3: задачи, которым нужен умный пинг.
     *
     * Условия:
     *  - не удалены и не выполнены;
     *  - autoRemindEnabled = 1;
     *  - нет точного remindAt (иначе приоритет у Пути 2);
     *  - нет dueDate ИЛИ dueDate уже в прошлом (просрочка = пингуем ежедневно).
     *
     * Сортировка: сначала просроченные (dueDate != NULL), потом важные.
     * Дальше по createdAt — старые задачи должны пинговаться первыми.
     */
    @Query("""
        SELECT * FROM tasks
        WHERE isDeleted = 0
          AND isDone = 0
          AND autoRemindEnabled = 1
          AND remindAt IS NULL
          AND (dueDate IS NULL OR dueDate < :nowMs)
        ORDER BY
            CASE WHEN dueDate IS NOT NULL THEN 0 ELSE 1 END ASC,
            priority DESC,
            createdAt ASC
    """)
    suspend fun getTasksNeedingSmartPing(nowMs: Long): List<TaskEntity>

    /**
     * 🆕 Путь 3: только просроченные задачи (dueDate < now, не выполнены,
     * не удалены, autoRemindEnabled = 1). Для секции «Просрочено» в UI
     * и для ежедневного пинга.
     */
    @Query("""
        SELECT * FROM tasks
        WHERE isDeleted = 0
          AND isDone = 0
          AND autoRemindEnabled = 1
          AND dueDate IS NOT NULL
          AND dueDate < :nowMs
        ORDER BY
            priority DESC,
            dueDate ASC
    """)
    suspend fun getOverdueTasks(nowMs: Long): List<TaskEntity>

    /**
     * 🆕 Путь 2: обновить только remindAt (не трогая остальные поля).
     * Используется, когда пользователь меняет «Когда напомнить» без
     * полного редактирования задачи.
     *
     * ⚠️ НЕ логируется в outbox автоматически — вызывающий код должен
     * перечитать задачу и вызвать OutboxLogger.taskChanged(...).
     */
    @Query("""
        UPDATE tasks SET
            remindAt = :remindAt,
            updatedAt = :updatedAt,
            syncStatus = 'pending'
        WHERE id = :taskId
    """)
    suspend fun updateRemindAt(taskId: String, remindAt: Long?, updatedAt: Long)

    /**
     * 🆕 Путь 3: включить/выключить умный пинг у конкретной задачи.
     *
     * ⚠️ НЕ логируется в outbox автоматически — вызывающий код должен
     * перечитать задачу и вызвать OutboxLogger.taskChanged(...).
     */
    @Query("""
        UPDATE tasks SET
            autoRemindEnabled = :enabled,
            updatedAt = :updatedAt,
            syncStatus = 'pending'
        WHERE id = :taskId
    """)
    suspend fun updateAutoRemindEnabled(taskId: String, enabled: Boolean, updatedAt: Long)

    /**
     * 🆕 Путь 3: сбросить точный remindAt после показа уведомления,
     * чтобы задача перешла на умный пинг (если autoRemindEnabled = 1)
     * или замолчала (если 0). dueDate при этом НЕ трогаем.
     *
     * ⚠️ НЕ логируется в outbox автоматически — вызывающий код должен
     * перечитать задачу и вызвать OutboxLogger.taskChanged(...).
     */
    @Query("""
        UPDATE tasks SET
            remindAt = NULL,
            updatedAt = :updatedAt,
            syncStatus = 'pending'
        WHERE id = :taskId
    """)
    suspend fun clearRemindAt(taskId: String, updatedAt: Long)

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
