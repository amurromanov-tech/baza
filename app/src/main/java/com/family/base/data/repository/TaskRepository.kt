package com.family.base.data.repository

import android.content.Context
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxLogger
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Репозиторий для работы с задачами «🏠 Домашние дела».
 *
 * Отвечает за:
 *  - CRUD задач
 *  - Автосоздание системной папки при старте
 *  - Логику повторов (создание клона при выполнении)
 *  - Счётчик активных задач
 *  - Soft-delete (tombstone, №10 v19)
 *  - 🆕 v15: логирование изменений в outbox (changelog)
 *  - 🆕 v18.2.0 (шаг 2): напоминания — Путь 2 (remindAt) + Путь 3 (умный пинг)
 *
 * НЕ отвечает за синхронизацию — это в CatalogRepository / SyncManager.
 */
class TaskRepository(
    private val context: Context,
    private val db: AppDatabase
) {

    private val taskDao = db.taskDao()
    private val folderDao = db.folderDao()

    // ============================================================
    // СИСТЕМНАЯ ПАПКА
    // ============================================================

    /**
     * Гарантирует, что системная папка «🏠 Домашние дела» существует.
     * Если нет — создаёт с фиксированным id и systemKey = home_tasks.
     *
     * 🆕 v15: при создании папки пишем в outbox, чтобы она ушла
     * на Диск и появилась на других устройствах.
     */
    suspend fun ensureSystemFolder(createdBy: String) = withContext(Dispatchers.IO) {
        val existing = folderDao.getFolderById(FolderEntity.SYSTEM_ID_HOME_TASKS)
        if (existing == null) {
            val folder = FolderEntity(
                id = FolderEntity.SYSTEM_ID_HOME_TASKS,
                name = "🏠 Домашние дела",
                parentId = null,
                parentItemId = null,
                createdBy = createdBy,
                path = "/🏠 Домашние дела",
                isSystem = true,
                systemKey = FolderEntity.SYSTEM_KEY_HOME_TASKS
            )
            folderDao.insertFolder(folder)
            OutboxLogger.folderChanged(
                db = db,
                folder = folder,
                action = OutboxEntity.ACTION_CREATE,
                changedBy = createdBy
            )
        }
    }

    suspend fun getSystemFolder(): FolderEntity? = withContext(Dispatchers.IO) {
        folderDao.getFolderById(FolderEntity.SYSTEM_ID_HOME_TASKS)
    }

    // ============================================================
    // ЧТЕНИЕ
    // ============================================================

    suspend fun getAllSorted(): List<TaskEntity> = withContext(Dispatchers.IO) {
        taskDao.getAllSorted()
    }

    suspend fun getActiveSorted(): List<TaskEntity> = withContext(Dispatchers.IO) {
        taskDao.getActiveSorted()
    }

    suspend fun getDoneSorted(): List<TaskEntity> = withContext(Dispatchers.IO) {
        taskDao.getDoneSorted()
    }

    /**
     * Читает задачу по id БЕЗ фильтра isDeleted — включая tombstones.
     * Нужна для:
     *  - TaskReminderReceiver (должен увидеть, что задача уже tombstone);
     *  - mergeData при сравнении версий.
     */
    suspend fun getById(id: String): TaskEntity? = withContext(Dispatchers.IO) {
        taskDao.getById(id)
    }

    /**
     * Читает «живую» задачу (не tombstone). Для UI-операций.
     */
    suspend fun getLiveById(id: String): TaskEntity? = withContext(Dispatchers.IO) {
        taskDao.getLiveById(id)
    }

    suspend fun getActiveCount(): Int = withContext(Dispatchers.IO) {
        taskDao.getActiveCount()
    }

    // ============================================================
    // СОЗДАНИЕ
    // ============================================================

    /**
     * Создать задачу.
     *
     * 🆕 v18.2.0 (шаг 2): параметры напоминаний.
     *  - remindAt: точный момент напоминания (Путь 2). null = нет точного.
     *  - autoRemindEnabled: умный пинг «пока не сделаю» (Путь 3). По умолчанию true.
     *
     * Вызывающий код (диалог создания) должен передать:
     *  - remindAt = выбранное «Когда напомнить» (по умолчанию «сегодня 19:00»);
     *  - autoRemindEnabled = чекбокс «Напоминать, пока не сделаю» (по умолчанию true).
     *
     * Если remindAt не передан — задача всё равно получит умный пинг
     * (autoRemindEnabled = true), т.е. не останется совсем без напоминаний.
     */
    suspend fun addTask(
        title: String,
        note: String?,
        priority: Int,
        dueDate: Long?,
        recurrence: Int,
        createdBy: String,
        remindAt: Long? = null,
        autoRemindEnabled: Boolean = true
    ): TaskEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            title = title.trim(),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            priority = priority,
            dueDate = dueDate,
            recurrence = recurrence,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
            remindAt = remindAt,
            autoRemindEnabled = autoRemindEnabled
        )
        taskDao.insert(task)
        OutboxLogger.taskChanged(
            db = db,
            task = task,
            action = OutboxEntity.ACTION_CREATE,
            changedBy = createdBy
        )
        task
    }

    // ============================================================
    // ОБНОВЛЕНИЕ
    // ============================================================

    suspend fun updateTask(task: TaskEntity): TaskEntity = withContext(Dispatchers.IO) {
        val updated = task.copy(updatedAt = System.currentTimeMillis())
        taskDao.update(updated)
        OutboxLogger.taskChanged(
            db = db,
            task = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = updated.createdBy
        )
        updated
    }

    /**
     * 🆕 v18.2.0 (шаг 2): точечно обновить «Когда напомнить» (Путь 2).
     *
     * Читает задачу, ставит remindAt, помечает pending, логирует в outbox
     * и возвращает обновлённую задачу (или null, если задачи нет).
     *
     * @param remindAt null — снять точное напоминание (тогда останется
     *                 только умный пинг, если autoRemindEnabled = true).
     */
    suspend fun updateRemindAt(
        taskId: String,
        remindAt: Long?,
        changedBy: String
    ): TaskEntity? = withContext(Dispatchers.IO) {
        val task = taskDao.getLiveById(taskId) ?: return@withContext null
        val now = System.currentTimeMillis()
        taskDao.updateRemindAt(taskId, remindAt, now)

        // Перечитываем, чтобы outbox уехал с актуальными полями
        val updated = taskDao.getById(taskId) ?: return@withContext null
        OutboxLogger.taskChanged(
            db = db,
            task = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = changedBy
        )
        updated
    }

    /**
     * 🆕 v18.2.0 (шаг 2): включить/выключить умный пинг у конкретной задачи (Путь 3).
     */
    suspend fun setAutoRemindEnabled(
        taskId: String,
        enabled: Boolean,
        changedBy: String
    ): TaskEntity? = withContext(Dispatchers.IO) {
        val task = taskDao.getLiveById(taskId) ?: return@withContext null
        val now = System.currentTimeMillis()
        taskDao.updateAutoRemindEnabled(taskId, enabled, now)

        val updated = taskDao.getById(taskId) ?: return@withContext null
        OutboxLogger.taskChanged(
            db = db,
            task = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = changedBy
        )
        updated
    }

    /**
     * 🆕 v18.2.0 (шаг 2): сбросить точный remindAt после показа уведомления.
     *
     * Вызывается из TaskReminderReceiver, когда точный пинг уже сработал.
     * Дальше задача либо уйдёт в умный пинг (autoRemindEnabled = true),
     * либо замолчит (autoRemindEnabled = false).
     *
     * dueDate при этом НЕ трогаем — задача остаётся с тем же дедлайном.
     */
    suspend fun clearRemindAtAfterNotification(
        taskId: String,
        changedBy: String
    ): TaskEntity? = withContext(Dispatchers.IO) {
        val task = taskDao.getLiveById(taskId) ?: return@withContext null
        val now = System.currentTimeMillis()
        taskDao.clearRemindAt(taskId, now)

        val updated = taskDao.getById(taskId) ?: return@withContext null
        OutboxLogger.taskChanged(
            db = db,
            task = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = changedBy
        )
        updated
    }

    // ============================================================
    // ВЫПОЛНЕНИЕ (с логикой повторов)
    // ============================================================

    /**
     * Отметить задачу как выполненную.
     *
     * Если у задачи recurrence != NONE — автоматически создаётся
     * новая задача-клон с dueDate + period и recurrenceSource = originalId.
     *
     * 🆕 v15: обе задачи (выполненная + клон) уходят в outbox.
     *
     * 🆕 v18.2.0 (шаг 2): при выполнении сбрасываем remindAt у выполненной
     * задачи (точный пинг больше не нужен), а клон получает remindAt
     * со сдвигом на тот же период, что и dueDate.
     */
    suspend fun markDone(
        taskId: String,
        doneBy: String
    ): Pair<TaskEntity, TaskEntity?>? = withContext(Dispatchers.IO) {
        val task = taskDao.getById(taskId) ?: return@withContext null
        if (task.isDone) return@withContext task to null

        val now = System.currentTimeMillis()
        val done = task.copy(
            isDone = true,
            doneAt = now,
            doneBy = doneBy,
            updatedAt = now,
            remindAt = null  // 🆕 точный пинг больше не нужен
        )
        taskDao.update(done)
        OutboxLogger.taskChanged(
            db = db,
            task = done,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = doneBy
        )

        val clone = if (task.recurrence != TaskEntity.RECURRENCE_NONE) {
            createRecurrenceClone(task, doneBy)
        } else null

        done to clone
    }

    /**
     * Снять отметку «выполнено».
     * Клон НЕ удаляем — он самостоятельная задача.
     *
     * 🆕 v18.2.0 (шаг 2): remindAt не восстанавливаем автоматически —
     * если нужно, пользователь выставит его заново. Умный пинг
     * (autoRemindEnabled) продолжит работать как обычно.
     */
    suspend fun markUndone(taskId: String): TaskEntity? = withContext(Dispatchers.IO) {
        val task = taskDao.getById(taskId) ?: return@withContext null
        if (!task.isDone) return@withContext task

        val updated = task.copy(
            isDone = false,
            doneAt = null,
            doneBy = null,
            updatedAt = System.currentTimeMillis()
        )
        taskDao.update(updated)
        OutboxLogger.taskChanged(
            db = db,
            task = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = updated.createdBy
        )
        updated
    }

    /**
     * Создаёт клон повторяющейся задачи со сдвигом dueDate.
     * 🆕 v15: клон тоже пишется в outbox.
     *
     * 🆕 v18.2.0 (шаг 2): если у оригинала был remindAt, сдвигаем его
     * на тот же период, что и dueDate (чтобы точный пинг клона
     * сработал вовремя).
     */
    private suspend fun createRecurrenceClone(
        original: TaskEntity,
        createdBy: String
    ): TaskEntity {
        val baseDate = original.dueDate ?: System.currentTimeMillis()
        val nextDate = addPeriod(baseDate, original.recurrence)

        val nextRemindAt = original.remindAt?.let { addPeriod(it, original.recurrence) }

        val now = System.currentTimeMillis()
        val clone = TaskEntity(
            title = original.title,
            note = original.note,
            priority = original.priority,
            dueDate = nextDate,
            isDone = false,
            recurrence = original.recurrence,
            recurrenceSource = original.id,
            sortOrder = original.sortOrder,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
            remindAt = nextRemindAt,
            autoRemindEnabled = original.autoRemindEnabled
        )
        taskDao.insert(clone)
        OutboxLogger.taskChanged(
            db = db,
            task = clone,
            action = OutboxEntity.ACTION_CREATE,
            changedBy = createdBy
        )
        return clone
    }

    /**
     * Прибавляет период к дате.
     * DAILY   = +1 день
     * WEEKLY  = +7 дней
     * MONTHLY = +1 месяц (через Calendar)
     */
    private fun addPeriod(date: Long, recurrence: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = date
        when (recurrence) {
            TaskEntity.RECURRENCE_DAILY -> cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            TaskEntity.RECURRENCE_WEEKLY -> cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
            TaskEntity.RECURRENCE_MONTHLY -> cal.add(java.util.Calendar.MONTH, 1)
            else -> return date
        }
        return cal.timeInMillis
    }

    // ============================================================
    // УДАЛЕНИЕ (TOMBSTONE, №10 v19)
    // ============================================================

    /**
     * Помечает задачу как tombstone (soft-delete).
     *
     * Задача остаётся в БД и синкается как удалённая через changelog,
     * чтобы удаление разошлось по всем устройствам и не «воскресло»
     * при merge со старой версией с Диска.
     *
     * В UI такие задачи не показываются (фильтр isDeleted = 0 в DAO).
     *
     * deleteConfirmed = 0 — PUSH ещё не подтверждён. Как только
     * SyncManager успешно отправит changelog — выставит 1 через
     * confirmTaskDeleted(). Через год cleanup физически удалит запись.
     *
     * @param taskId     id удаляемой задачи.
     * @param deletedBy  кто удалил (AppUser.name на момент удаления).
     */
    suspend fun softDelete(taskId: String, deletedBy: String) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(taskId) ?: return@withContext
        if (task.isDeleted) return@withContext

        val now = System.currentTimeMillis()
        taskDao.markTaskDeleted(taskId, now, deletedBy)

        // Перечитываем, чтобы в outbox уехали актуальные поля
        // (isDeleted=1, deletedAt, deletedBy, deleteConfirmed=0, updatedAt).
        val updated = taskDao.getById(taskId) ?: return@withContext
        OutboxLogger.taskChanged(
            db = db,
            task = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = deletedBy
        )
    }

    /**
     * Подтвердить tombstone после успешного PUSH в changelog.
     * Вызывается из SyncManager.
     */
    suspend fun confirmTaskDeleted(taskId: String) = withContext(Dispatchers.IO) {
        taskDao.confirmTaskDeleted(taskId)
    }

    /**
     * Физическое удаление подтверждённых tombstones старше указанного времени.
     * Вызывается из cleanup-логики SyncManager (раз в N дней).
     *
     * @param thresholdMs timestamp, раньше которого tombstones считаются старыми.
     * @return количество физически удалённых записей.
     */
    suspend fun cleanupOldTombstones(thresholdMs: Long): Int = withContext(Dispatchers.IO) {
        val ids = taskDao.getConfirmedTasksOlderThan(thresholdMs)
        if (ids.isEmpty()) return@withContext 0
        taskDao.hardDeleteConfirmedTasks(ids)
        ids.size
    }

    // ============================================================
    // 🆕 v18.2.0 (шаг 2): ВЫБОРКИ ДЛЯ ПЛАНИРОВЩИКА
    // ============================================================

    /**
     * 🆕 Путь 2: задачи с точным напоминанием, которое наступит до `beforeMs`.
     * Нужно планировщику, чтобы перепланировать ближайшие alarms.
     */
    suspend fun getTasksWithRemindAtBefore(beforeMs: Long): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getTasksWithRemindAtBefore(beforeMs)
        }

    /**
     * 🆕 Путь 2: все задачи с заданным точным напоминанием.
     * Используется rescheduleAll, чтобы не потерять будущие remindAt.
     */
    suspend fun getTasksWithRemindAt(): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getTasksWithRemindAt()
        }

    /**
     * 🆕 Путь 3: задачи, которым нужен умный пинг (нет remindAt,
     * autoRemindEnabled = 1, dueDate NULL или в прошлом).
     */
    suspend fun getTasksNeedingSmartPing(nowMs: Long): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getTasksNeedingSmartPing(nowMs)
        }

    /**
     * 🆕 Путь 3: только просроченные задачи (dueDate < now).
     */
    suspend fun getOverdueTasks(nowMs: Long): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getOverdueTasks(nowMs)
        }

    /**
     * 🆕 v20.2.3: задачи для окна «На сегодня» при запуске MainActivity.
     *
     * Прокси к taskDao.getTodayTasks(...). Логика фильтра и сортировки —
     * в DAO (см. комментарий к TaskDao.getTodayTasks).
     *
     * @param nowMs       текущий timestamp (для определения просрочки).
     * @param endOfDayMs  конец сегодняшнего дня (23:59:59.999).
     */
    suspend fun getTodayTasks(nowMs: Long, endOfDayMs: Long): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getTodayTasks(nowMs, endOfDayMs)
        }

    // ============================================================
    // ДЛЯ СИНКА (используется CatalogRepository)
    // ============================================================

    suspend fun getAllForSync(): List<TaskEntity> = withContext(Dispatchers.IO) {
        taskDao.getAllForSync()
    }

    suspend fun getAllIds(): List<String> = withContext(Dispatchers.IO) {
        taskDao.getAllIds()
    }

    /**
     * ⚠️ НЕ логируется в outbox: это входящие данные с Диска,
     * их обратно отправлять не надо.
     */
    suspend fun insertAllIgnore(tasks: List<TaskEntity>) = withContext(Dispatchers.IO) {
        taskDao.insertAllIgnore(tasks)
    }

    /**
     * ⚠️ НЕ логируется в outbox: используется при merge.
     */
    suspend fun insertAll(tasks: List<TaskEntity>) = withContext(Dispatchers.IO) {
        taskDao.insertAll(tasks)
    }
}
