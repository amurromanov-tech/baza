package com.family.base.data.local

import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.TaskEntity
import com.family.base.util.Logger

/**
 * Удобная обёртка над OutboxHelper.
 *
 * Отличие от OutboxHelper:
 *  - OutboxHelper только пишет в outbox.
 *  - OutboxLogger — пишет в outbox И помечает syncStatus = "pending"
 *    у самой сущности в БД (items/folders/tasks).
 *
 * Так гарантируется, что:
 *  1. Любое изменение попадает в outbox.
 *  2. У сущности в БД выставлен признак "не синхронизировано".
 *
 * Использование:
 *   OutboxLogger.itemChanged(db, item, ACTION_UPDATE, currentUser)
 *   OutboxLogger.folderDeleted(db, folderId, currentUser, nameHint)
 *   OutboxLogger.taskChanged(db, task, ACTION_CREATE, currentUser)
 *
 * ⚠️ Сетевых вызовов нет — только локальная запись.
 */
object OutboxLogger {

    private const val TAG = "OutboxLogger"

    // ============================================================
    // ITEM
    // ============================================================

    /**
     * Item изменён (create / update).
     * Помечает syncStatus = pending и логирует в outbox.
     */
    suspend fun itemChanged(
        db: AppDatabase,
        item: ItemEntity,
        action: String,
        changedBy: String
    ) {
        try {
            val marked = item.copy(syncStatus = ItemEntity.SYNC_STATUS_PENDING)
            db.itemDao().updateItem(marked)
            OutboxHelper.logItem(db, marked, action, changedBy)
        } catch (e: Exception) {
            Logger.log(TAG, "itemChanged ERROR: action=$action, id=${item.id}, ${e.message}")
        }
    }

    /**
     * Item удалён (hard-delete).
     * Сущности уже нет в БД — пишем минимальный delete-пакет.
     */
    suspend fun itemDeleted(
        db: AppDatabase,
        itemId: String,
        changedBy: String,
        nameHint: String? = null
    ) {
        try {
            OutboxHelper.logDeletion(
                db = db,
                entityType = OutboxEntity.ENTITY_ITEM,
                entityId = itemId,
                changedBy = changedBy,
                nameHint = nameHint
            )
        } catch (e: Exception) {
            Logger.log(TAG, "itemDeleted ERROR: id=$itemId, ${e.message}")
        }
    }

    // ============================================================
    // FOLDER
    // ============================================================

    suspend fun folderChanged(
        db: AppDatabase,
        folder: FolderEntity,
        action: String,
        changedBy: String
    ) {
        try {
            val marked = folder.copy(syncStatus = FolderEntity.SYNC_STATUS_PENDING)
            db.folderDao().updateFolder(marked)
            OutboxHelper.logFolder(db, marked, action, changedBy)
        } catch (e: Exception) {
            Logger.log(TAG, "folderChanged ERROR: action=$action, id=${folder.id}, ${e.message}")
        }
    }

    suspend fun folderDeleted(
        db: AppDatabase,
        folderId: String,
        changedBy: String,
        nameHint: String? = null
    ) {
        try {
            OutboxHelper.logDeletion(
                db = db,
                entityType = OutboxEntity.ENTITY_FOLDER,
                entityId = folderId,
                changedBy = changedBy,
                nameHint = nameHint
            )
        } catch (e: Exception) {
            Logger.log(TAG, "folderDeleted ERROR: id=$folderId, ${e.message}")
        }
    }

    // ============================================================
    // TASK
    // ============================================================

    suspend fun taskChanged(
        db: AppDatabase,
        task: TaskEntity,
        action: String,
        changedBy: String
    ) {
        try {
            val marked = task.copy(syncStatus = TaskEntity.SYNC_STATUS_PENDING)
            db.taskDao().update(marked)
            OutboxHelper.logTask(db, marked, action, changedBy)
        } catch (e: Exception) {
            Logger.log(TAG, "taskChanged ERROR: action=$action, id=${task.id}, ${e.message}")
        }
    }

    suspend fun taskDeleted(
        db: AppDatabase,
        taskId: String,
        changedBy: String,
        titleHint: String? = null
    ) {
        try {
            OutboxHelper.logDeletion(
                db = db,
                entityType = OutboxEntity.ENTITY_TASK,
                entityId = taskId,
                changedBy = changedBy,
                nameHint = titleHint
            )
        } catch (e: Exception) {
            Logger.log(TAG, "taskDeleted ERROR: id=$taskId, ${e.message}")
        }
    }

    // ============================================================
    // БАТЧ-ОПЕРАЦИИ
    // ============================================================

    /**
     * Массовая запись items (например, после импорта из чека).
     * Помечает все как pending и логирует каждую.
     */
    suspend fun itemsBatchCreated(
        db: AppDatabase,
        items: List<ItemEntity>,
        changedBy: String
    ) {
        try {
            val marked = items.map {
                it.copy(syncStatus = ItemEntity.SYNC_STATUS_PENDING)
            }
            db.itemDao().updateItems(marked)
            marked.forEach { item ->
                OutboxHelper.logItem(db, item, OutboxEntity.ACTION_CREATE, changedBy)
            }
            Logger.log(TAG, "itemsBatchCreated: logged ${marked.size} items")
        } catch (e: Exception) {
            Logger.log(TAG, "itemsBatchCreated ERROR: count=${items.size}, ${e.message}")
        }
    }
}
