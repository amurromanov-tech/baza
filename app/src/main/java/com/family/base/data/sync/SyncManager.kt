package com.family.base.data.sync

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.DeviceIdProvider
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.TaskEntity
import com.family.base.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Главный класс синхронизации через changelog.
 *
 * Задача №2: локальная база — источник правды, Яндекс.Диск — транспорт
 * для пакетов изменений.
 *
 * Алгоритм:
 *  1. PUSH: сформировать пакет из outbox → залить на Диск → пометить synced.
 *  2. PULL: список файлов в /BAZA/changelog/ → отфильтровать уже обработанные →
 *     скачать новые → применить через ChangelogParser.
 *  3. CLEANUP: удалить synced-записи старше N дней и устаревшие processed_changelogs.
 *
 * SyncManager НЕ трогает items.json / folders.json / tasks.json / history.json —
 * они продолжают работать параллельно как fallback для обратной совместимости.
 */
class SyncManager(
    private val db: AppDatabase,
    private val parser: ChangelogParser,
    private val api: SyncManagerApi
) {

    private val gson = Gson()
    private val TAG = "SyncManager"

    companion object {
        /** Свои пакеты старше этого возраста можно удалять с Диска */
        private const val OWN_BATCH_TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 дней

        /** Записи processed_changelogs старше этого возраста чистим */
        private const val PROCESSED_TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 дней

        /** Записи outbox со статусом synced старше этого возраста чистим */
        private const val OUTBOX_SYNCED_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 дней
    }

    /**
     * Результат одного цикла синхронизации.
     */
    data class SyncResult(
        val pushedBatches: Int,
        val pushedEntries: Int,
        val pulledBatches: Int,
        val pulledEntriesApplied: Int,
        val pulledEntriesSkipped: Int,
        val pulledErrors: Int,
        val pullFailed: Boolean,
        val pushFailed: Boolean
    ) {
        val hasChanges: Boolean get() = pushedBatches > 0 || pulledBatches > 0
    }

    // ============================================================
    // ГЛАВНЫЙ ВХОД
    // ============================================================

    /**
     * Полный цикл синхронизации changelog.
     * Возвращает SyncResult. Не бросает исключений.
     */
    suspend fun syncChangelogs(): SyncResult {
        val deviceId: String = try {
            val ctx = com.family.base.BaseApplication.getAppContext()
            DeviceIdProvider.get(ctx)
        } catch (e: Exception) {
            Logger.log(TAG, "syncChangelogs: cannot get app context: ${e.message}")
            return SyncResult(0, 0, 0, 0, 0, 0, pullFailed = true, pushFailed = true)
        }

        Logger.log(TAG, "syncChangelogs: START, deviceId=$deviceId")

        // Гарантируем папку /BAZA/changelog/
        if (!api.ensureChangelogFolder()) {
            Logger.log(TAG, "syncChangelogs: cannot ensure changelog folder, aborting")
            return SyncResult(0, 0, 0, 0, 0, 0, pullFailed = true, pushFailed = true)
        }

        // 1. PUSH
        val pushResult = pushPendingChanges(deviceId)

        // 2. PULL
        val pullResult = pullIncomingChanges(deviceId)

        // 3. CLEANUP
        cleanup(deviceId)

        val result = SyncResult(
            pushedBatches = pushResult.first,
            pushedEntries = pushResult.second,
            pulledBatches = pullResult.batches,
            pulledEntriesApplied = pullResult.applied,
            pulledEntriesSkipped = pullResult.skipped,
            pulledErrors = pullResult.errors,
            pullFailed = pullResult.failed,
            pushFailed = pushResult.third
        )

        Logger.log(
            TAG,
            "syncChangelogs: DONE — pushed batches=${result.pushedBatches} entries=${result.pushedEntries}, " +
                "pulled batches=${result.pulledBatches} applied=${result.pulledEntriesApplied} " +
                "skipped=${result.pulledEntriesSkipped} errors=${result.pulledErrors}"
        )
        return result
    }

    // ============================================================
    // PUSH
    // ============================================================

    /**
     * Формирует один пакет из всех несинхронизированных outbox-записей
     * и заливает его на Диск.
     *
     * @return Triple(batchesCount, entriesCount, failed)
     */
    private suspend fun pushPendingChanges(deviceId: String): Triple<Int, Int, Boolean> {
        val pending = try {
            db.outboxDao().getPending()
        } catch (e: Exception) {
            Logger.log(TAG, "pushPendingChanges: cannot read outbox: ${e.message}")
            return Triple(0, 0, true)
        }

        if (pending.isEmpty()) {
            Logger.log(TAG, "pushPendingChanges: nothing to push")
            return Triple(0, 0, false)
        }

        Logger.log(TAG, "pushPendingChanges: ${pending.size} entries to push")

        val now = System.currentTimeMillis()
        val batchId = "${deviceId}_$now"
        val fileName = "$batchId.json"

        // Определяем changedBy: берём из первой записи.
        val changedBy = pending.firstOrNull()?.changedBy ?: "unknown"

        // Формируем entries
        val entries = pending.map { out ->
            ChangelogEntry(
                entityType = out.entityType,
                entityId = out.entityId,
                action = out.action,
                payloadJson = out.payloadJson,
                changedAt = out.createdAt,
                changedBy = out.changedBy
            )
        }

        val batch = ChangelogBatch(
            schemaVersion = ChangelogBatch.SCHEMA_VERSION,
            deviceId = deviceId,
            batchId = batchId,
            createdAt = now,
            changedBy = changedBy,
            entries = entries
        )

        val json = gson.toJson(batch)
        val bytes = json.toByteArray(Charsets.UTF_8)

        val uploaded = api.uploadChangelogFile(fileName, bytes)
        if (!uploaded) {
            Logger.log(TAG, "pushPendingChanges: upload FAILED for $fileName, keeping entries in outbox")
            return Triple(0, 0, true)
        }

        // Помечаем outbox-записи как synced
        try {
            val ids = pending.map { it.id }
            db.outboxDao().markSyncedBatch(ids, now, batchId)
        } catch (e: Exception) {
            Logger.log(TAG, "pushPendingChanges: markSyncedBatch failed: ${e.message}")
        }

        // Помечаем сущности как synced
        markEntitiesSynced(pending)

        Logger.log(TAG, "pushPendingChanges: uploaded $fileName with ${entries.size} entries")
        return Triple(1, entries.size, false)
    }

    /**
     * Помечает синхронизированные сущности как synced в БД.
     * Группирует по типам для эффективности.
     */
    private suspend fun markEntitiesSynced(entries: List<OutboxEntity>) {
        val itemIds = entries.filter { it.entityType == OutboxEntity.ENTITY_ITEM }.map { it.entityId }
        val folderIds = entries.filter { it.entityType == OutboxEntity.ENTITY_FOLDER }.map { it.entityId }
        val taskIds = entries.filter { it.entityType == OutboxEntity.ENTITY_TASK }.map { it.entityId }

        try {
            if (itemIds.isNotEmpty()) {
                db.itemDao().markItemsSynced(itemIds)
            }
            if (folderIds.isNotEmpty()) {
                db.folderDao().markFoldersSynced(folderIds)
            }
            if (taskIds.isNotEmpty()) {
                db.taskDao().markTasksSynced(taskIds)
            }
            Logger.log(
                TAG,
                "markEntitiesSynced: items=${itemIds.size}, folders=${folderIds.size}, tasks=${taskIds.size}"
            )
        } catch (e: Exception) {
            Logger.log(TAG, "markEntitiesSynced error: ${e.message}")
        }
    }

    // ============================================================
    // PULL
    // ============================================================

    private data class PullResult(
        val batches: Int,
        val applied: Int,
        val skipped: Int,
        val errors: Int,
        val failed: Boolean
    )

    /**
     * Скачивает все новые пакеты из /BAZA/changelog/ и применяет их.
     */
    private suspend fun pullIncomingChanges(deviceId: String): PullResult {
        val files = api.listChangelogFiles()
        if (files == null) {
            Logger.log(TAG, "pullIncomingChanges: cannot list files (network error)")
            return PullResult(0, 0, 0, 0, failed = true)
        }

        if (files.isEmpty()) {
            Logger.log(TAG, "pullIncomingChanges: no files on disk")
            return PullResult(0, 0, 0, 0, failed = false)
        }

        Logger.log(TAG, "pullIncomingChanges: ${files.size} files on disk")

        // Получаем уже обработанные batchId
        val processedIds: Set<String> = try {
            db.processedChangelogDao().getAllBatchIds().toSet()
        } catch (e: Exception) {
            Logger.log(TAG, "pullIncomingChanges: cannot read processed_changelogs: ${e.message}")
            return PullResult(0, 0, 0, 0, failed = true)
        }

        // Фильтруем: имя файла = "{batchId}.json".
        // Без continue/break внутри лямбды — только чистый filter.
        val candidates: List<String> = files.filter { fileName ->
            val id = fileName.removeSuffix(".json")
            !processedIds.contains(id)
        }

        if (candidates.isEmpty()) {
            Logger.log(TAG, "pullIncomingChanges: no new files to process")
            return PullResult(0, 0, 0, 0, failed = false)
        }

        Logger.log(TAG, "pullIncomingChanges: ${candidates.size} new files to process")

        var batches = 0
        var applied = 0
        var skipped = 0
        var errors = 0

        for (fileName in candidates) {
            val bytes = api.downloadChangelogFile(fileName)
            if (bytes == null) {
                Logger.log(TAG, "pullIncomingChanges: cannot download $fileName, skipping")
                continue
            }

            val batch: ChangelogBatch? = try {
                val json = String(bytes, Charsets.UTF_8)
                gson.fromJson(json, ChangelogBatch::class.java)
            } catch (e: JsonSyntaxException) {
                Logger.log(TAG, "pullIncomingChanges: JSON syntax error in $fileName: ${e.message}")
                errors++
                markProcessedBad(fileName, "syntax_error")
                null
            } catch (e: Exception) {
                Logger.log(TAG, "pullIncomingChanges: parse error in $fileName: ${e.message}")
                errors++
                null
            }

            if (batch == null) {
                continue
            }

            try {
                val result = parser.apply(batch)
                batches++
                applied += result.appliedCount
                skipped += result.skippedCount
                errors += result.errorsCount

                Logger.log(
                    TAG,
                    "pullIncomingChanges: applied $fileName — entries=${result.totalEntries}, " +
                        "applied=${result.appliedCount}, skipped=${result.skippedCount}, errors=${result.errorsCount}"
                )
            } catch (e: Exception) {
                Logger.log(TAG, "pullIncomingChanges: apply failed for $fileName: ${e.message}", e)
                errors++
            }
        }

        return PullResult(batches, applied, skipped, errors, failed = false)
    }

    /**
     * Помечает битый пакет как обработанный, чтобы не пытаться снова.
     */
    private suspend fun markProcessedBad(fileName: String, reason: String) {
        try {
            val batchId = fileName.removeSuffix(".json")
            val now = System.currentTimeMillis()
            // deviceId берём из имени файла: "{deviceId}_{timestamp}"
            val deviceIdGuess = batchId.substringBeforeLast('_', batchId)
            db.processedChangelogDao().insert(
                com.family.base.data.local.entity.ProcessedChangelogEntity(
                    batchId = batchId,
                    deviceId = deviceIdGuess,
                    changedBy = null,
                    createdAt = now,
                    processedAt = now,
                    entriesCount = 0,
                    appliedCount = 0,
                    isOwn = false
                )
            )
            Logger.log(TAG, "markProcessedBad: $fileName marked as $reason")
        } catch (e: Exception) {
            Logger.log(TAG, "markProcessedBad error for $fileName: ${e.message}")
        }
    }

    // ============================================================
    // CLEANUP
    // ============================================================

    /**
     * Чистит устаревшие данные:
     *  - outbox (synced старше 7 дней);
     *  - processed_changelogs (старше 30 дней);
     *  - свои файлы changelog на Диске (старше 7 дней).
     *
     * ⚠️ Переписано без `continue` внутри лямбд, чтобы не упасть
     * на экспериментальной фиче Kotlin.
     */
    private suspend fun cleanup(deviceId: String) {
        val now = System.currentTimeMillis()

        // 1. Локальный outbox
        try {
            val cutoff = now - OUTBOX_SYNCED_TTL_MS
            db.outboxDao().deleteSyncedOlderThan(cutoff)
        } catch (e: Exception) {
            Logger.log(TAG, "cleanup outbox error: ${e.message}")
        }

        // 2. Локальный processed_changelogs
        try {
            val cutoff = now - PROCESSED_TTL_MS
            db.processedChangelogDao().deleteOlderThan(cutoff)
        } catch (e: Exception) {
            Logger.log(TAG, "cleanup processed_changelogs error: ${e.message}")
        }

        // 3. Свои старые файлы на Диске — необязательно, но полезно
        try {
            val files = api.listChangelogFiles()
            if (files == null) {
                return
            }

            val ownCutoff = now - OWN_BATCH_TTL_MS

            // Собираем список файлов для удаления БЕЗ continue внутри лямбды.
            val toDelete = files.filter { fileName ->
                val batchId = fileName.removeSuffix(".json")
                val isOwn = batchId.startsWith(deviceId)
                val timestamp = batchId.substringAfterLast('_').toLongOrNull()
                isOwn && timestamp != null && timestamp < ownCutoff
            }

            for (fileName in toDelete) {
                api.deleteChangelogFile(fileName)
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanup own disk files error: ${e.message}")
        }
    }
}
