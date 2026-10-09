package com.family.base.data.repository

import android.content.Context
import com.family.base.AppUser
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.TaskEntity
import com.family.base.data.local.entity.FolderEntity
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
 *  - Soft-delete
 *
 * НЕ отвечает за синхронизацию — это в CatalogRepository.
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
     * Вызывается при старте приложения (BaseApplication).
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
        createdBy: String
    ): TaskEntity = withContext(Dispatchers.IO) {
        val task = TaskEntity(
            title = title.trim(),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            priority = priority,
            dueDate = dueDate,
            recurrence = recurrence,
            createdBy = createdBy,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        taskDao.insert(task)
        task
    }

    // ============================================================
    // ОБНОВЛЕНИЕ
    // ============================================================

    suspend fun updateTask(task: TaskEntity): TaskEntity = withContext(Dispatchers.IO) {
        val updated = task.copy(updatedAt = System.currentTimeMillis())
        taskDao.update(updated)
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
     * Возвращает пару: (обновлённая задача, созданный клон или null).
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
            updatedAt = now
        )
        taskDao.update(done)

        val clone = if (task.recurrence != TaskEntity.RECURRENCE_NONE) {
            createRecurrenceClone(task, doneBy)
        } else null

        done to clone
    }

    /**
     * Снять отметку «выполнено».
     * Клон НЕ удаляем — он самостоятельная задача.
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
        updated
    }

    /**
     * Создаёт клон повторяющейся задачи со сдвигом dueDate.
     */
    private suspend fun createRecurrenceClone(
        original: TaskEntity,
        createdBy: String
    ): TaskEntity {
        val baseDate = original.dueDate ?: System.currentTimeMillis()
        val nextDate = addPeriod(baseDate, original.recurrence)

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
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        taskDao.insert(clone)
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
    // УДАЛЕНИЕ (soft)
    // ============================================================

    /**
     * Soft-delete: помечает isDeleted = true.
     * Задача остаётся в БД и синкается как удалённая,
     * чтобы удаление разошлось по всем устройствам.
     */
    suspend fun softDelete(taskId: String) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(taskId) ?: return@withContext
        val updated = task.copy(
            isDeleted = true,
            updatedAt = System.currentTimeMillis()
        )
        taskDao.update(updated)
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

    suspend fun insertAllIgnore(tasks: List<TaskEntity>) = withContext(Dispatchers.IO) {
        taskDao.insertAllIgnore(tasks)
    }

    suspend fun insertAll(tasks: List<TaskEntity>) = withContext(Dispatchers.IO) {
        taskDao.insertAll(tasks)
    }
}
