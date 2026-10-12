package com.family.base.data.local

import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
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
 *    у самой сущности в БД (items/folders/tasks), когда это применимо.
 *
 * Для History никакого syncStatus нет — история append-only,
 * поэтому метод historyAdded просто пишет в outbox.
 */
object OutboxLogger {

    private const val TAG = "OutboxLogger"

    // ============================================================
    // ITEM
    // ============================================================

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
    // 🆕 v16.2: HISTORY
    // ============================================================

    /**
     * Записать новую запись истории в outbox.
     *
     * История append-only: каждая новая запись уходит в changelog
     * как отдельный "create". Удаления и обновления истории
     * не поддерживаются (это лог).
     *
     * ВАЖНО: не нужно предварительно вызывать db.historyDao().insertEntry() —
     * делаем это здесь, чтобы не забыть.
     */
    suspend fun historyAdded(
        db: AppDatabase,
        entry: HistoryEntry,
        changedBy: String
    ) {
        try {
            // Вставляем запись в историю
            db.historyDao().insertEntry(entry)
            // И пишем в outbox для отправки в changelog
            OutboxHelper.logHistory(db, entry, changedBy)
        } catch (e: Exception) {
            Logger.log(TAG, "historyAdded ERROR: id=${entry.id}, ${e.message}")
        }
    }

    // ============================================================
    // БАТЧ-ОПЕРАЦИИ
    // ============================================================

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
