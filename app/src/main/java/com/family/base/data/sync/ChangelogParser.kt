package com.family.base.data.sync

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
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
 *  4. 🆕 v19 (№10): удаления приходят как tombstones (isDeleted = true).
 *     Для старых клиентов, которые шлют ACTION_DELETE — переводим их
 *     в tombstone локально (не hard-delete), чтобы удаление
 *     не «воскресло» при следующем merge.
 *     Локальный tombstone с более новым updatedAt побеждает —
 *     живая remote-запись его не перезаписывает.
 *  5. Для history — append-only, просто INSERT IGNORE по id.
 *     Никаких merge: если запись с таким id уже есть, пропускаем.
 *  6. Пакет фиксируется в processed_changelogs всегда (кроме явных ошибок).
 *
 * Парсер НЕ пишет в outbox — входящие изменения не надо отправлять обратно.
 * deleteConfirmed = 1 у применённых tombstones ставим сразу: раз запись
 * пришла с Диска, значит PUSH уже прошёл, и cleanup может её удалить
 * через CLEANUP_RETENTION_MS (1 год).
 *
 * 🆕 v18.2.0 (шаг 2): TaskEntity получил поля remindAt и autoRemindEnabled.
 * При applyTaskEntry(...) payload парсится через
 * gson.fromJson(entry.payloadJson, TaskEntity::class.java) — новые поля
 * подхватываются автоматически (их сериализует OutboxHelper.logTask на
 * стороне-отправителе). Никаких ручных копирований полей в этом файле
 * делать НЕ нужно: remoteSynced = remote.copy(syncStatus=...) сохраняет
 * remindAt и autoRemindEnabled как есть.
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
            OutboxEntity.ENTITY_HISTORY -> applyHistoryEntry(entry)
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

        // ── ACTION_DELETE (старый формат: hard-delete с удалённого устройства) ──
        // Переводим в tombstone локально, не hard-delete. Иначе при следующем
        // merge remote-запись с третьего устройства «воскресит» предмет.
        if (entry.action == OutboxEntity.ACTION_DELETE) {
            if (local == null) {
                // Локально записи нет — удалять нечего.
                Logger.log(TAG, "applyItem DELETE: id=${entry.entityId} not found locally, skip")
                return false
            }
            if (entry.changedAt < local.updatedDate) {
                Logger.log(
                    TAG,
                    "applyItem DELETE SKIP: local newer (local=${local.updatedDate}, remote=${entry.changedAt})"
                )
                return false
            }
            if (local.isDeleted) {
                Logger.log(TAG, "applyItem DELETE SKIP: already tombstone ${entry.entityId}")
                return false
            }
            db.itemDao().markItemDeleted(entry.entityId, entry.changedAt, entry.changedBy)
            // Раз tombstone пришёл с Диска — он уже разошёлся, подтверждаем.
            db.itemDao().confirmItemDeleted(entry.entityId)
            Logger.log(TAG, "applyItem DELETE → tombstone: id=${entry.entityId} '${local.name}'")
            return true
        }

        // ── PARSE PAYLOAD ──
        val remote: ItemEntity = try {
            gson.fromJson(entry.payloadJson, ItemEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyItem: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        // Tombstone пришёл с Диска → он уже разошёлся, ставим deleteConfirmed = true.
        val remoteSynced = if (remote.isDeleted) {
            remote.copy(
                syncStatus = ItemEntity.SYNC_STATUS_SYNCED,
                deleteConfirmed = true
            )
        } else {
            remote.copy(syncStatus = ItemEntity.SYNC_STATUS_SYNCED)
        }

        // ── INSERT (локально нет) ──
        if (local == null) {
            db.itemDao().insertItem(remoteSynced)
            Logger.log(
                TAG,
                "applyItem INSERT: id=${entry.entityId} '${remoteSynced.name}' " +
                    "(action=${entry.action}, isDeleted=${remoteSynced.isDeleted})"
            )
            return true
        }

        // ── ЗАЩИТА ОТ ВОСКРЕШЕНИЯ ──
        // Локальный tombstone + remote живой + локальный не старше → skip.
        if (local.isDeleted && !remoteSynced.isDeleted && local.updatedDate >= remoteSynced.updatedDate) {
            Logger.log(
                TAG,
                "applyItem SKIP: local tombstone wins (local.updatedDate=${local.updatedDate} " +
                    ">= remote.updatedDate=${remoteSynced.updatedDate}) for id=${entry.entityId}"
            )
            return false
        }

        // ── UPDATE (remote новее) ──
        if (remoteSynced.updatedDate > local.updatedDate) {
            val merged = remoteSynced.copy(
                imageUrl = remoteSynced.imageUrl ?: local.imageUrl,
                thumbnailUrl = remoteSynced.thumbnailUrl ?: local.thumbnailUrl,
                parentItemId = remoteSynced.parentItemId ?: local.parentItemId
            )
            db.itemDao().updateItem(merged)
            Logger.log(
                TAG,
                "applyItem UPDATE: id=${entry.entityId} '${merged.name}' " +
                    "(remote newer: ${remoteSynced.updatedDate} > ${local.updatedDate}, isDeleted=${merged.isDeleted})"
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

        // ── ACTION_DELETE (старый формат) → tombstone локально ──
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
            if (local.isDeleted) {
                Logger.log(TAG, "applyFolder DELETE SKIP: already tombstone ${entry.entityId}")
                return false
            }
            db.folderDao().markFolderDeleted(entry.entityId, entry.changedAt, entry.changedBy)
            db.folderDao().confirmFolderDeleted(entry.entityId)
            Logger.log(TAG, "applyFolder DELETE → tombstone: id=${entry.entityId} '${local.name}'")
            return true
        }

        // ── PARSE PAYLOAD ──
        val remote: FolderEntity = try {
            gson.fromJson(entry.payloadJson, FolderEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyFolder: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        val remoteSynced = if (remote.isDeleted) {
            remote.copy(
                syncStatus = FolderEntity.SYNC_STATUS_SYNCED,
                deleteConfirmed = true
            )
        } else {
            remote.copy(syncStatus = FolderEntity.SYNC_STATUS_SYNCED)
        }

        // ── INSERT ──
        if (local == null) {
            db.folderDao().insertFolder(remoteSynced)
            Logger.log(
                TAG,
                "applyFolder INSERT: id=${entry.entityId} '${remoteSynced.name}' " +
                    "(action=${entry.action}, isDeleted=${remoteSynced.isDeleted})"
            )
            return true
        }

        // ── ЗАЩИТА СИСТЕМНОЙ ПАПКИ ──
        if (local.isSystem && remoteSynced.isDeleted) {
            Logger.log(TAG, "applyFolder SKIP: cannot tombstone system folder ${entry.entityId}")
            return false
        }

        // ── ЗАЩИТА ОТ ВОСКРЕШЕНИЯ ──
        if (local.isDeleted && !remoteSynced.isDeleted && local.updatedAt >= remoteSynced.updatedAt) {
            Logger.log(
                TAG,
                "applyFolder SKIP: local tombstone wins (local.updatedAt=${local.updatedAt} " +
                    ">= remote.updatedAt=${remoteSynced.updatedAt}) for id=${entry.entityId}"
            )
            return false
        }

        // ── UPDATE (remote новее) ──
        if (remoteSynced.updatedAt > local.updatedAt) {
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
                "applyFolder UPDATE: id=${entry.entityId} '${merged.name}' " +
                    "(remote newer: ${remoteSynced.updatedAt} > ${local.updatedAt}, isDeleted=${merged.isDeleted})"
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

    /**
     * Применить входящее изменение задачи.
     *
     * 🆕 v18.2.0 (шаг 2): TaskEntity получил remindAt и autoRemindEnabled.
     * Парсинг через gson.fromJson(payloadJson, TaskEntity::class.java)
     * подхватывает новые поля автоматически. remoteSynced = remote.copy(...)
     * сохраняет remindAt и autoRemindEnabled как есть — никаких ручных
     * копирований полей в этом методе не требуется.
     *
     * Важно: при UPDATE задача с Диска перезаписывает локальную ЦЕЛИКОМ
     * (включая remindAt и autoRemindEnabled). Это правильно: если на другом
     * устройстве пользователь поменял «Когда напомнить» или выключил умный
     * пинг, это должно разойтись.
     */
    private suspend fun applyTaskEntry(entry: ChangelogEntry): Boolean {
        val local = db.taskDao().getById(entry.entityId)

        // ── ACTION_DELETE (старый формат) → tombstone локально ──
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
            if (local.isDeleted) {
                Logger.log(TAG, "applyTask DELETE SKIP: already tombstone ${entry.entityId}")
                return false
            }
            db.taskDao().markTaskDeleted(entry.entityId, entry.changedAt, entry.changedBy)
            db.taskDao().confirmTaskDeleted(entry.entityId)
            Logger.log(TAG, "applyTask DELETE → tombstone: id=${entry.entityId} '${local.title}'")
            return true
        }

        // ── PARSE PAYLOAD ──
        val remote: TaskEntity = try {
            gson.fromJson(entry.payloadJson, TaskEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyTask: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        val remoteSynced = if (remote.isDeleted) {
            remote.copy(
                syncStatus = TaskEntity.SYNC_STATUS_SYNCED,
                deleteConfirmed = true
            )
        } else {
            remote.copy(syncStatus = TaskEntity.SYNC_STATUS_SYNCED)
        }

        // ── INSERT ──
        if (local == null) {
            db.taskDao().insert(remoteSynced)
            Logger.log(
                TAG,
                "applyTask INSERT: id=${entry.entityId} '${remoteSynced.title}' " +
                    "(action=${entry.action}, isDeleted=${remoteSynced.isDeleted}, " +
                    "remindAt=${remoteSynced.remindAt}, autoRemind=${remoteSynced.autoRemindEnabled})"
            )
            return true
        }

        // ── ЗАЩИТА ОТ ВОСКРЕШЕНИЯ ──
        if (local.isDeleted && !remoteSynced.isDeleted && local.updatedAt >= remoteSynced.updatedAt) {
            Logger.log(
                TAG,
                "applyTask SKIP: local tombstone wins (local.updatedAt=${local.updatedAt} " +
                    ">= remote.updatedAt=${remoteSynced.updatedAt}) for id=${entry.entityId}"
            )
            return false
        }

        // ── UPDATE (remote новее) ──
        if (remoteSynced.updatedAt > local.updatedAt) {
            db.taskDao().update(remoteSynced)
            Logger.log(
                TAG,
                "applyTask UPDATE: id=${entry.entityId} '${remoteSynced.title}' " +
                    "(remote newer: ${remoteSynced.updatedAt} > ${local.updatedAt}, " +
                    "isDeleted=${remoteSynced.isDeleted}, " +
                    "remindAt=${remoteSynced.remindAt}, autoRemind=${remoteSynced.autoRemindEnabled})"
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
    // HISTORY (append-only)
    // ============================================================

    /**
     * История — append-only.
     *
     * Правила:
     *  - Только "create" (update / delete для истории не бывает).
     *  - Если запись с таким id уже есть — пропускаем (INSERT IGNORE).
     *  - Никакого merge по времени: у HistoryEntry нет updatedAt.
     *  - Поля oldValue / newValue / itemName приходят полностью из payload.
     */
    private suspend fun applyHistoryEntry(entry: ChangelogEntry): Boolean {
        if (entry.action != OutboxEntity.ACTION_CREATE) {
            Logger.log(
                TAG,
                "applyHistory: action=${entry.action} not supported for history, skipping id=${entry.entityId}"
            )
            return false
        }

        val remote: HistoryEntry = try {
            gson.fromJson(entry.payloadJson, HistoryEntry::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyHistory: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        val existingIds = db.historyDao().getAllIds()
        if (remote.id in existingIds) {
            Logger.log(TAG, "applyHistory SKIP: id=${remote.id} already exists")
            return false
        }

        db.historyDao().insertAll(listOf(remote))
        Logger.log(
            TAG,
            "applyHistory INSERT: id=${remote.id}, action=${remote.action}, itemId=${remote.itemId}, by=${remote.changedBy}"
        )
        return true
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
