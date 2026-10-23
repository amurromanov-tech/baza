package com.family.base.data.repository

import android.content.Context
import com.family.base.AppUser
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
 * 🆕 v20.3.9: softDelete блокируется для Римы и Димы.
 *  - Проверка через AppUser.canDelete(deletedBy).
 *  - UI (TaskListActivity) дополнительно скрывает пункт «Удалить»
 *    и показывает Toast — это файл 6.
 *  - Здесь — только блокировка + лог (без Toast: у репозитория
 *    нет UI-контекста).
 */
class TaskRepository(
    private val context: Context,
    private val db: AppDatabase
) {

    private val taskDao = db.taskDao()
    private val folderDao = db.folderDao()

    private val TAG = "TaskRepository"

    // ============================================================
    // СИСТЕМНАЯ ПАПКА
    // ============================================================

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

    suspend fun getById(id: String): TaskEntity? = withContext(Dispatchers.IO) {
        taskDao.getById(id)
    }

    suspend fun getLiveById(id: String): TaskEntity? = withContext(Dispatchers.IO) {
        taskDao.getLiveById(id)
    }

    suspend fun getActiveCount(): Int = withContext(Dispatchers.IO) {
        taskDao.getActiveCount()
    }

    // ============================================================
    // СОЗДАНИЕ
    // ============================================================

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

    suspend fun updateRemindAt(
        taskId: String,
        remindAt: Long?,
        changedBy: String
    ): TaskEntity? = withContext(Dispatchers.IO) {
        val task = taskDao.getLiveById(taskId) ?: return@withContext null
        val now = System.currentTimeMillis()
        taskDao.updateRemindAt(taskId, remindAt, now)

        val updated = taskDao.getById(taskId) ?: return@withContext null
        OutboxLogger.taskChanged(
            db = db,
            task = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = changedBy
        )
        updated
    }

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
    // ВЫПОЛНЕНИЕ
    // ============================================================

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
            remindAt = null
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
     * 🆕 v20.3.9: softDelete заблокирован для Римы и Димы.
     *
     * Проверка через AppUser.canDelete(deletedBy). UI дополнительно
     * скрывает кнопку и показывает Toast (см. TaskListActivity).
     *
     * @param taskId     id удаляемой задачи.
     * @param deletedBy  кто удалил (AppUser.name на момент удаления).
     */
    suspend fun softDelete(taskId: String, deletedBy: String) = withContext(Dispatchers.IO) {
        if (!AppUser.canDelete(deletedBy)) {
            Logger.log(TAG, "softDelete: DENIED for $deletedBy (only Alexey can delete)")
            return@withContext
        }
        val task = taskDao.getById(taskId) ?: return@withContext
        if (task.isDeleted) return@withContext

        val now = System.currentTimeMillis()
        taskDao.markTaskDeleted(taskId, now, deletedBy)

        val updated = taskDao.getById(taskId) ?: return@withContext
        OutboxLogger.taskChanged(
            db = db,
            task = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = deletedBy
        )
    }

    suspend fun confirmTaskDeleted(taskId: String) = withContext(Dispatchers.IO) {
        taskDao.confirmTaskDeleted(taskId)
    }

    suspend fun cleanupOldTombstones(thresholdMs: Long): Int = withContext(Dispatchers.IO) {
        val ids = taskDao.getConfirmedTasksOlderThan(thresholdMs)
        if (ids.isEmpty()) return@withContext 0
        taskDao.hardDeleteConfirmedTasks(ids)
        ids.size
    }

    // ============================================================
    // ВЫБОРКИ ДЛЯ ПЛАНИРОВЩИКА
    // ============================================================

    suspend fun getTasksWithRemindAtBefore(beforeMs: Long): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getTasksWithRemindAtBefore(beforeMs)
        }

    suspend fun getTasksWithRemindAt(): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getTasksWithRemindAt()
        }

    suspend fun getTasksNeedingSmartPing(nowMs: Long): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getTasksNeedingSmartPing(nowMs)
        }

    suspend fun getOverdueTasks(nowMs: Long): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getOverdueTasks(nowMs)
        }

    suspend fun getTodayTasks(nowMs: Long, endOfDayMs: Long): List<TaskEntity> =
        withContext(Dispatchers.IO) {
            taskDao.getTodayTasks(nowMs, endOfDayMs)
        }

    // ============================================================
    // ДЛЯ СИНКА
    // ============================================================

    suspend fun getAllForSync(): List<TaskEntity> = withContext(Dispatchers.IO) {
        taskDao.getAllForSync()
    }

    suspend fun getAllIds(): List<String> = withContext(Dispatchers.IO) {
        taskDao.getAllIds()
    }

    suspend fun insertAllIgnore(tasks: List<TaskEntity>) = withContext(Dispatchers.IO) {
        taskDao.insertAllIgnore(tasks)
    }

    suspend fun insertAll(tasks: List<TaskEntity>) = withContext(Dispatchers.IO) {
        taskDao.insertAll(tasks)
    }
}
