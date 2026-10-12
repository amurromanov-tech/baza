package com.family.base.data.sync

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.DeviceIdProvider
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.ProcessedChangelogEntity
import com.family.base.data.local.entity.TaskEntity
import com.family.base.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Применяет входящий пакет changelog к локальной БД.
 *
 * Правила:
 *  1. Свои пакеты (deviceId == наш) НЕ применяем — только фиксируем в processed_changelogs.
 *  2. Разрешение конфликтов по updatedAt / updatedDate:
 *       - если remote новее локального → применяем;
 *       - если локальный новее → пропускаем (наш выигрыш).
 *  3. При применении принудительно ставим syncStatus = "synced",
 *     чтобы не зациклить пересылку (иначе получим ping-pong между устройствами).
 *  4. Для item/folder delete — физическое удаление.
 *     Для task delete — ставим isDeleted = true (soft-delete).
 *  5. Пакет фиксируется в processed_changelogs всегда (кроме явных ошибок).
 *
 * Парсер НЕ пишет в outbox — входящие изменения не надо отправлять обратно.
 */
class ChangelogParser(
    private val db: AppDatabase,
    private val deviceId: String
) {

    private val gson = Gson()
    private val TAG = "ChangelogParser"

    /**
     * Результат применения одного пакета.
     */
    data class ApplyResult(
        val batchId: String,
        val totalEntries: Int,
        val appliedCount: Int,
        val skippedCount: Int,
        val errorsCount: Int,
        val isOwn: Boolean,
        val success: Boolean
    )

    // ============================================================
    // ПУБЛИЧНЫЙ ВХОД
    // ============================================================

    /**
     * Применить один пакет.
     * Возвращает ApplyResult. Никогда не бросает исключение —
     * ошибки логируются и возвращаются в errorsCount.
     */
    suspend fun apply(batch: ChangelogBatch): ApplyResult {
        val isOwn = batch.deviceId == deviceId

        if (isOwn) {
            Logger.log(TAG, "apply: OWN batch ${batch.batchId}, marking processed, skipping entries")
            markProcessed(batch, appliedCount = 0, isOwn = true)
            return ApplyResult(
                batchId = batch.batchId,
                totalEntries = batch.entries.size,
                appliedCount = 0,
                skippedCount = batch.entries.size,
                errorsCount = 0,
                isOwn = true,
                success = true
            )
        }

        Logger.log(TAG, "apply: batch ${batch.batchId} from device ${batch.deviceId}, entries=${batch.entries.size}")

        var applied = 0
        var skipped = 0
        var errors = 0

        for (entry in batch.entries) {
            try {
                val didApply = applyEntry(entry)
                if (didApply) applied++ else skipped++
            } catch (e: JsonSyntaxException) {
                errors++
                Logger.log(TAG, "apply: JSON parse error for entry type=${entry.entityType} id=${entry.entityId}: ${e.message}")
            } catch (e: Exception) {
                errors++
                Logger.log(TAG, "apply: error applying entry type=${entry.entityType} id=${entry.entityId}: ${e.message}", e)
            }
        }

        markProcessed(batch, appliedCount = applied, isOwn = false)

        Logger.log(
            TAG,
            "apply: batch ${batch.batchId} DONE — applied=$applied, skipped=$skipped, errors=$errors"
        )

        return ApplyResult(
            batchId = batch.batchId,
            totalEntries = batch.entries.size,
            appliedCount = applied,
            skippedCount = skipped,
            errorsCount = errors,
            isOwn = false,
            success = true
        )
    }

    // ============================================================
    // ДИСПЕТЧЕР
    // ============================================================

    private suspend fun applyEntry(entry: ChangelogEntry): Boolean {
        return when (entry.entityType) {
            OutboxEntity.ENTITY_ITEM -> applyItemEntry(entry)
            OutboxEntity.ENTITY_FOLDER -> applyFolderEntry(entry)
            OutboxEntity.ENTITY_TASK -> applyTaskEntry(entry)
            else -> {
                Logger.log(TAG, "applyEntry: unknown entityType '${entry.entityType}', skipping")
                false
            }
        }
    }

    // ============================================================
    // ITEM
    // ============================================================

    private suspend fun applyItemEntry(entry: ChangelogEntry): Boolean {
        val local = db.itemDao().getItemById(entry.entityId)

        // DELETE: физически удаляем, если локальный не новее
        if (entry.action == OutboxEntity.ACTION_DELETE) {
            if (local == null) {
                Logger.log(TAG, "applyItem DELETE: id=${entry.entityId} not found locally, skip")
                return false
            }
            // Сравниваем время: удалять, только если изменение пришло позже
            // последнего локального обновления
            if (entry.changedAt < local.updatedDate) {
                Logger.log(
                    TAG,
                    "applyItem DELETE SKIP: local newer (local=${local.updatedDate}, remote=${entry.changedAt})"
                )
                return false
            }
            db.itemDao().deleteItem(local)
            Logger.log(TAG, "applyItem DELETE: id=${entry.entityId} '${local.name}' removed")
            return true
        }

        // CREATE / UPDATE: десериализуем
        val remote: ItemEntity = try {
            gson.fromJson(entry.payloadJson, ItemEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyItem: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        // Принудительно synced, чтобы не переслать обратно
        val remoteSynced = remote.copy(syncStatus = ItemEntity.SYNC_STATUS_SYNCED)

        if (local == null) {
            db.itemDao().insertItem(remoteSynced)
            Logger.log(TAG, "applyItem INSERT: id=${entry.entityId} '${remoteSynced.name}' (action=${entry.action})")
            return true
        }

        // Конфликт: побеждает тот, у кого updatedDate больше
        if (remoteSynced.updatedDate > local.updatedDate) {
            // Сохраняем локальные imageUrl и parentItemId, если у remote пусто
            val merged = remoteSynced.copy(
                imageUrl = remoteSynced.imageUrl ?: local.imageUrl,
                thumbnailUrl = remoteSynced.thumbnailUrl ?: local.thumbnailUrl,
                parentItemId = remoteSynced.parentItemId ?: local.parentItemId
            )
            db.itemDao().updateItem(merged)
            Logger.log(
                TAG,
                "applyItem UPDATE: id=${entry.entityId} '${merged.name}' (remote newer: ${remoteSynced.updatedDate} > ${local.updatedDate})"
            )
            return true
        } else {
            Logger.log(
                TAG,
                "applyItem UPDATE SKIP: local newer (local=${local.updatedDate} >= remote=${remoteSynced.updatedDate})"
            )
            return false
        }
    }

    // ============================================================
    // FOLDER
    // ============================================================

    private suspend fun applyFolderEntry(entry: ChangelogEntry): Boolean {
        val local = db.folderDao().getFolderById(entry.entityId)

        if (entry.action == OutboxEntity.ACTION_DELETE) {
            if (local == null) {
                Logger.log(TAG, "applyFolder DELETE: id=${entry.entityId} not found locally, skip")
                return false
            }
            if (local.isSystem) {
                Logger.log(TAG, "applyFolder DELETE SKIP: system folder ${entry.entityId} protected")
                return false
            }
            if (entry.changedAt < local.updatedAt) {
                Logger.log(
                    TAG,
                    "applyFolder DELETE SKIP: local newer (local=${local.updatedAt}, remote=${entry.changedAt})"
                )
                return false
            }
            db.folderDao().deleteFolderById(entry.entityId)
            Logger.log(TAG, "applyFolder DELETE: id=${entry.entityId} '${local.name}' removed")
            return true
        }

        val remote: FolderEntity = try {
            gson.fromJson(entry.payloadJson, FolderEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyFolder: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        val remoteSynced = remote.copy(syncStatus = FolderEntity.SYNC_STATUS_SYNCED)

        if (local == null) {
            db.folderDao().insertFolder(remoteSynced)
            Logger.log(TAG, "applyFolder INSERT: id=${entry.entityId} '${remoteSynced.name}' (action=${entry.action})")
            return true
        }

        if (remoteSynced.updatedAt > local.updatedAt) {
            // Системную папку не трогаем по name/parentId/systemKey,
            // но разрешаем обновить updatedAt (вдруг что-то менялось).
            val merged = if (local.isSystem || remoteSynced.isSystem) {
                remoteSynced.copy(
                    name = local.name,
                    parentId = local.parentId,
                    parentItemId = local.parentItemId,
                    isSystem = true,
                    systemKey = local.systemKey ?: remoteSynced.systemKey,
                    path = local.path
                )
            } else {
                remoteSynced.copy(
                    iconUrl = remoteSynced.iconUrl ?: local.iconUrl,
                    iconThumbnailUrl = remoteSynced.iconThumbnailUrl ?: local.iconThumbnailUrl,
                    parentItemId = remoteSynced.parentItemId ?: local.parentItemId
                )
            }
            db.folderDao().updateFolder(merged)
            Logger.log(
                TAG,
                "applyFolder UPDATE: id=${entry.entityId} '${merged.name}' (remote newer: ${remoteSynced.updatedAt} > ${local.updatedAt})"
            )
            return true
        } else {
            Logger.log(
                TAG,
                "applyFolder UPDATE SKIP: local newer (local=${local.updatedAt} >= remote=${remoteSynced.updatedAt})"
            )
            return false
        }
    }

    // ============================================================
    // TASK
    // ============================================================

    private suspend fun applyTaskEntry(entry: ChangelogEntry): Boolean {
        val local = db.taskDao().getById(entry.entityId)

        // Для задач delete — soft-delete (isDeleted = true)
        if (entry.action == OutboxEntity.ACTION_DELETE) {
            if (local == null) {
                Logger.log(TAG, "applyTask DELETE: id=${entry.entityId} not found locally, skip")
                return false
            }
            if (entry.changedAt < local.updatedAt) {
                Logger.log(
                    TAG,
                    "applyTask DELETE SKIP: local newer (local=${local.updatedAt}, remote=${entry.changedAt})"
                )
                return false
            }
            val softDeleted = local.copy(
                isDeleted = true,
                updatedAt = System.currentTimeMillis(),
                syncStatus = TaskEntity.SYNC_STATUS_SYNCED
            )
            db.taskDao().update(softDeleted)
            Logger.log(TAG, "applyTask SOFT-DELETE: id=${entry.entityId} '${local.title}'")
            return true
        }

        val remote: TaskEntity = try {
            gson.fromJson(entry.payloadJson, TaskEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyTask: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        val remoteSynced = remote.copy(syncStatus = TaskEntity.SYNC_STATUS_SYNCED)

        if (local == null) {
            db.taskDao().insert(remoteSynced)
            Logger.log(TAG, "applyTask INSERT: id=${entry.entityId} '${remoteSynced.title}' (action=${entry.action})")
            return true
        }

        if (remoteSynced.updatedAt > local.updatedAt) {
            db.taskDao().update(remoteSynced)
            Logger.log(
                TAG,
                "applyTask UPDATE: id=${entry.entityId} '${remoteSynced.title}' (remote newer: ${remoteSynced.updatedAt} > ${local.updatedAt})"
            )
            return true
        } else {
            Logger.log(
                TAG,
                "applyTask UPDATE SKIP: local newer (local=${local.updatedAt} >= remote=${remoteSynced.updatedAt})"
            )
            return false
        }
    }

    // ============================================================
    // ФИКСАЦИЯ ОБРАБОТКИ
    // ============================================================

    private suspend fun markProcessed(batch: ChangelogBatch, appliedCount: Int, isOwn: Boolean) {
        try {
            db.processedChangelogDao().insert(
                ProcessedChangelogEntity(
                    batchId = batch.batchId,
                    deviceId = batch.deviceId,
                    changedBy = batch.changedBy,
                    createdAt = batch.createdAt,
                    processedAt = System.currentTimeMillis(),
                    entriesCount = batch.entries.size,
                    appliedCount = appliedCount,
                    isOwn = isOwn
                )
            )
        } catch (e: Exception) {
            Logger.log(TAG, "markProcessed error for ${batch.batchId}: ${e.message}")
        }
    }
}
