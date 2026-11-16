package com.family.base.data.sync

import com.family.base.BaseApplication
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.DeviceIdProvider
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeFolderEntity
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.data.local.entity.TaskEntity
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Главный класс синхронизации через changelog.
 *
 * 🆕 v21.1.0 (Шаг 1.6-4): рецепты и медиа рецептов.
 *  - markEntitiesSynced: добавить ENTITY_RECIPE / ENTITY_RECIPE_MEDIA;
 *  - confirmTombstones: добавить ветки для рецептов и медиа;
 *  - cleanupConfirmedTombstones: удалять рецепты и медиа (локальные файлы +
 *    файлы на Диске через SyncManagerApi.deleteRecipeMediaFile).
 *
 * 🆕 v21.1.0+ (Шаг 1.7-5): папки рецептов.
 *  - markEntitiesSynced: добавить ENTITY_RECIPE_FOLDER;
 *  - confirmTombstones: добавить ветку для папок рецептов;
 *  - cleanupConfirmedTombstones: физическое удаление подтверждённых
 *    tombstones папок рецептов (без файлов на Диске — папки живут
 *    только в БД + changelog, см. DiskPaths: RECIPE_MEDIA не для папок).
 *
 *  Рецепты, медиа и папки приходят через changelog PULL (ChangelogParser).
 *  mergeData для них НЕ используется (items.json/folders.json их не содержат).
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

        /** Сколько хранить подтверждённые tombstones перед физическим удалением. 1 год. */
        private const val TOMBSTONE_RETENTION_MS = 365L * 24 * 60 * 60 * 1000
    }

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

    suspend fun syncChangelogs(): SyncResult {
        val deviceId: String = try {
            val ctx = BaseApplication.getAppContext()
            DeviceIdProvider.get(ctx)
        } catch (e: Exception) {
            Logger.log(TAG, "syncChangelogs: cannot get app context: ${e.message}")
            return SyncResult(0, 0, 0, 0, 0, 0, pullFailed = true, pushFailed = true)
        }

        Logger.log(TAG, "syncChangelogs: START, deviceId=$deviceId")

        val folderReady = try {
            api.ensureChangelogFolder()
        } catch (e: Exception) {
            Logger.log(TAG, "syncChangelogs: ensureChangelogFolder threw: ${e.message}")
            false
        }

        if (!folderReady) {
            Logger.log(TAG, "syncChangelogs: changelog folder not ready, push will be skipped")
        }

        val pushResult: Triple<Int, Int, Boolean> = if (folderReady) {
            pushPendingChanges(deviceId)
        } else {
            Triple(0, 0, true)
        }

        val pullResult = pullIncomingChanges(deviceId)

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
                "skipped=${result.pulledEntriesSkipped} errors=${result.pulledErrors} " +
                "pushFailed=${result.pushFailed} pullFailed=${result.pullFailed}"
        )
        return result
    }

    // ============================================================
    // PUSH
    // ============================================================

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

        val changedBy = pending.firstOrNull()?.changedBy ?: "unknown"

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

        try {
            val ids = pending.map { it.id }
            db.outboxDao().markSyncedBatch(ids, now, batchId)
        } catch (e: Exception) {
            Logger.log(TAG, "pushPendingChanges: markSyncedBatch failed: ${e.message}")
        }

        markEntitiesSynced(pending)

        confirmTombstones(pending)

        Logger.log(TAG, "pushPendingChanges: uploaded $fileName with ${entries.size} entries")
        return Triple(1, entries.size, false)
    }

    /**
     * Помечает синхронизированные сущности как synced в БД.
     *
     * 🆕 v21.1.0 (Шаг 1.6-4): добавлены ENTITY_RECIPE / ENTITY_RECIPE_MEDIA.
     * 🆕 v21.1.0+ (Шаг 1.7-5): добавлен ENTITY_RECIPE_FOLDER.
     */
    private suspend fun markEntitiesSynced(entries: List<OutboxEntity>) {
        val itemIds = entries.filter { it.entityType == OutboxEntity.ENTITY_ITEM }.map { it.entityId }
        val folderIds = entries.filter { it.entityType == OutboxEntity.ENTITY_FOLDER }.map { it.entityId }
        val taskIds = entries.filter { it.entityType == OutboxEntity.ENTITY_TASK }.map { it.entityId }
        val recipeIds = entries.filter { it.entityType == OutboxEntity.ENTITY_RECIPE }.map { it.entityId }
        val recipeMediaIds = entries.filter { it.entityType == OutboxEntity.ENTITY_RECIPE_MEDIA }.map { it.entityId }
        // 🆕 1.7-5: папки рецептов.
        val recipeFolderIds = entries.filter { it.entityType == OutboxEntity.ENTITY_RECIPE_FOLDER }.map { it.entityId }

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
            // 🆕 1.6-4: рецепты и медиа.
            if (recipeIds.isNotEmpty()) {
                for (id in recipeIds) {
                    db.recipeDao().updateSyncStatus(id, RecipeEntity.SYNC_STATUS_SYNCED)
                }
            }
            if (recipeMediaIds.isNotEmpty()) {
                for (id in recipeMediaIds) {
                    db.recipeMediaDao().updateSyncStatus(id, RecipeMediaEntity.SYNC_STATUS_SYNCED)
                }
            }
            // 🆕 1.7-5: папки рецептов.
            if (recipeFolderIds.isNotEmpty()) {
                for (id in recipeFolderIds) {
                    db.recipeFolderDao().updateSyncStatus(id, RecipeFolderEntity.SYNC_STATUS_SYNCED)
                }
            }
            Logger.log(
                TAG,
                "markEntitiesSynced: items=${itemIds.size}, folders=${folderIds.size}, tasks=${taskIds.size}, " +
                    "recipes=${recipeIds.size}, recipeMedia=${recipeMediaIds.size}, " +
                    "recipeFolders=${recipeFolderIds.size}"
            )
        } catch (e: Exception) {
            Logger.log(TAG, "markEntitiesSynced error: ${e.message}")
        }
    }

    /**
     * После успешного PUSH подтверждаем tombstones.
     *
     * 🆕 v21.1.0 (Шаг 1.6-4): добавлены ветки для рецептов и медиа.
     * 🆕 v21.1.0+ (Шаг 1.7-5): добавлена ветка для папок рецептов.
     */
    private suspend fun confirmTombstones(entries: List<OutboxEntity>) {
        var itemsConfirmed = 0
        var foldersConfirmed = 0
        var tasksConfirmed = 0
        var recipesConfirmed = 0
        var recipeMediaConfirmed = 0
        var recipeFoldersConfirmed = 0

        try {
            for (out in entries) {
                when (out.entityType) {
                    OutboxEntity.ENTITY_ITEM -> {
                        val item = db.itemDao().getItemById(out.entityId)
                        if (item != null && item.isDeleted && !item.deleteConfirmed) {
                            db.itemDao().confirmItemDeleted(out.entityId)
                            itemsConfirmed++
                        }
                    }
                    OutboxEntity.ENTITY_FOLDER -> {
                        val folder = db.folderDao().getFolderById(out.entityId)
                        if (folder != null && folder.isDeleted && !folder.deleteConfirmed) {
                            db.folderDao().confirmFolderDeleted(out.entityId)
                            foldersConfirmed++
                        }
                    }
                    OutboxEntity.ENTITY_TASK -> {
                        val task = db.taskDao().getById(out.entityId)
                        if (task != null && task.isDeleted && !task.deleteConfirmed) {
                            db.taskDao().confirmTaskDeleted(out.entityId)
                            tasksConfirmed++
                        }
                    }
                    // 🆕 1.6-4: рецепты и медиа.
                    OutboxEntity.ENTITY_RECIPE -> {
                        val recipe = db.recipeDao().getRecipeById(out.entityId)
                        if (recipe != null && recipe.isDeleted && !recipe.deleteConfirmed) {
                            db.recipeDao().confirmRecipeDeleted(out.entityId)
                            recipesConfirmed++
                        }
                    }
                    OutboxEntity.ENTITY_RECIPE_MEDIA -> {
                        val media = db.recipeMediaDao().getMediaById(out.entityId)
                        if (media != null && media.isDeleted && !media.deleteConfirmed) {
                            db.recipeMediaDao().confirmMediaDeleted(out.entityId)
                            recipeMediaConfirmed++
                        }
                    }
                    // 🆕 1.7-5: папки рецептов.
                    OutboxEntity.ENTITY_RECIPE_FOLDER -> {
                        val folder = db.recipeFolderDao().getFolderById(out.entityId)
                        if (folder != null && folder.isDeleted && !folder.deleteConfirmed) {
                            db.recipeFolderDao().confirmFolderDeleted(out.entityId)
                            recipeFoldersConfirmed++
                        }
                    }
                }
            }
            if (itemsConfirmed + foldersConfirmed + tasksConfirmed +
                recipesConfirmed + recipeMediaConfirmed + recipeFoldersConfirmed > 0
            ) {
                Logger.log(
                    TAG,
                    "confirmTombstones: items=$itemsConfirmed, folders=$foldersConfirmed, " +
                        "tasks=$tasksConfirmed, recipes=$recipesConfirmed, " +
                        "recipeMedia=$recipeMediaConfirmed, recipeFolders=$recipeFoldersConfirmed"
                )
            }
        } catch (e: Exception) {
            Logger.log(TAG, "confirmTombstones error: ${e.message}")
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

        val processedIds: Set<String> = try {
            db.processedChangelogDao().getAllBatchIds().toSet()
        } catch (e: Exception) {
            Logger.log(TAG, "pullIncomingChanges: cannot read processed_changelogs: ${e.message}")
            return PullResult(0, 0, 0, 0, failed = true)
        }

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

    private suspend fun markProcessedBad(fileName: String, reason: String) {
        try {
            val batchId = fileName.removeSuffix(".json")
            val now = System.currentTimeMillis()
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

        // 3. Файлы changelog на Диске
        try {
            val files = api.listChangelogFiles()
            if (files != null) {
                val ownCutoff = now - OWN_BATCH_TTL_MS
                val foreignCutoff = now - PROCESSED_TTL_MS

                val oldProcessedBatchIds: Set<String> = try {
                    db.processedChangelogDao()
                        .getProcessedOlderThan(foreignCutoff)
                        .toSet()
                } catch (e: Exception) {
                    Logger.log(TAG, "cleanup: cannot read getProcessedOlderThan: ${e.message}")
                    emptySet()
                }

                val toDelete = files.filter { fileName ->
                    val batchId = fileName.removeSuffix(".json")
                    val isOwn = batchId.startsWith(deviceId)
                    val timestamp = batchId.substringAfterLast('_').toLongOrNull()

                    val ownOld = isOwn && timestamp != null && timestamp < ownCutoff
                    val foreignOld = !isOwn && oldProcessedBatchIds.contains(batchId)

                    ownOld || foreignOld
                }

                var deletedCount = 0
                var failedCount = 0
                for (fileName in toDelete) {
                    val ok = api.deleteChangelogFile(fileName)
                    if (ok) deletedCount++ else failedCount++
                }

                if (toDelete.isNotEmpty()) {
                    Logger.log(
                        TAG,
                        "cleanup: disk files — candidates=${toDelete.size}, " +
                            "deleted=$deletedCount, failed=$failedCount, " +
                            "(own TTL=$OWN_BATCH_TTL_MS ms, foreign TTL=$PROCESSED_TTL_MS ms)"
                    )
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanup disk files error: ${e.message}")
        }

        // 4. 🆕 1.6-4: физическое удаление подтверждённых tombstones
        //    (items, folders, tasks + recipes, recipe_media + recipe_folders).
        cleanupConfirmedTombstones(now)
    }

    /**
     * 🆕 1.6-4: физически удаляет подтверждённые tombstones рецептов
     * и медиа вместе с items/folders/tasks.
     * 🆕 1.7-5: удаляет подтверждённые tombstones папок рецептов.
     *
     * Для рецептов и медиа:
     *  1. Удаляем файлы с Диска (/BAZA/data/recipe_media/<recipeId>/...):
     *     - для медиа: <mediaId>.jpg / <mediaId>.mp4 + <mediaId>_thumb.jpg;
     *  2. Локальные файлы удалит SyncViewModel.cleanupRecipeTombstonesLocal.
     *  3. hardDeleteConfirmedMedia / hardDeleteConfirmedRecipes.
     *
     * Для папок рецептов: файлов на Диске НЕТ (папки живут только
     * в БД + changelog). Поэтому только hardDeleteConfirmedFolders.
     */
    private suspend fun cleanupConfirmedTombstones(now: Long) {
        val cutoff = now - TOMBSTONE_RETENTION_MS
        val appContext = try {
            BaseApplication.getAppContext()
        } catch (e: Exception) {
            Logger.log(TAG, "cleanupConfirmedTombstones: cannot get app context: ${e.message}")
            return
        }

        // --- ITEMS ---
        try {
            val itemIds = db.itemDao().getConfirmedItemsOlderThan(cutoff)
            if (itemIds.isNotEmpty()) {
                var diskDeleted = 0
                var diskFailed = 0
                for (id in itemIds) {
                    if (api.deleteItemImageFile(id)) diskDeleted++ else diskFailed++
                    try {
                        ImageUtils.deleteLocalImage(appContext, id)
                    } catch (e: Exception) {
                        Logger.log(TAG, "cleanupConfirmedTombstones: local image delete error for $id: ${e.message}")
                    }
                }
                db.itemDao().hardDeleteConfirmedItems(itemIds)
                Logger.log(
                    TAG,
                    "cleanupConfirmedTombstones: removed ${itemIds.size} items " +
                        "(disk images: deleted=$diskDeleted, failed=$diskFailed)"
                )
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanupConfirmedTombstones items error: ${e.message}")
        }

        // --- FOLDERS ---
        try {
            val folderIds = db.folderDao().getConfirmedFoldersOlderThan(cutoff)
            if (folderIds.isNotEmpty()) {
                var diskDeleted = 0
                var diskFailed = 0
                for (id in folderIds) {
                    if (api.deleteFolderImageFile(id)) diskDeleted++ else diskFailed++
                    try {
                        ImageUtils.deleteLocalImage(appContext, "folder_$id")
                    } catch (e: Exception) {
                        Logger.log(TAG, "cleanupConfirmedTombstones: local folder image delete error for $id: ${e.message}")
                    }
                }
                db.folderDao().hardDeleteConfirmedFolders(folderIds)
                Logger.log(
                    TAG,
                    "cleanupConfirmedTombstones: removed ${folderIds.size} folders " +
                        "(disk icons: deleted=$diskDeleted, failed=$diskFailed)"
                )
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanupConfirmedTombstones folders error: ${e.message}")
        }

        // --- TASKS (без фото) ---
        try {
            val taskIds = db.taskDao().getConfirmedTasksOlderThan(cutoff)
            if (taskIds.isNotEmpty()) {
                db.taskDao().hardDeleteConfirmedTasks(taskIds)
                Logger.log(TAG, "cleanupConfirmedTombstones: removed ${taskIds.size} tasks")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanupConfirmedTombstones tasks error: ${e.message}")
        }

        // --- 🆕 1.6-4: RECIPE MEDIA ---
        try {
            val mediaIds = db.recipeMediaDao().getConfirmedMediaOlderThan(cutoff)
            if (mediaIds.isNotEmpty()) {
                val allMedia = db.recipeMediaDao().getAllRaw()
                val byId = allMedia.associateBy { it.id }
                var diskDeleted = 0
                var diskFailed = 0
                for (id in mediaIds) {
                    val m = byId[id] ?: continue
                    // Удаляем основной файл с Диска.
                    val mainName = m.imageUrl
                    if (!mainName.isNullOrBlank()) {
                        if (api.deleteRecipeMediaFile(m.recipeId, mainName)) diskDeleted++ else diskFailed++
                    }
                    // Удаляем thumb с Диска.
                    val thumbName = m.thumbnailUrl
                    if (!thumbName.isNullOrBlank()) {
                        if (api.deleteRecipeMediaFile(m.recipeId, thumbName)) diskDeleted++ else diskFailed++
                    }
                    // Локальные файлы удалим в SyncViewModel.cleanupRecipeTombstonesLocal.
                }
                db.recipeMediaDao().hardDeleteConfirmedMedia(mediaIds)
                Logger.log(
                    TAG,
                    "cleanupConfirmedTombstones: removed ${mediaIds.size} recipe media " +
                        "(disk files: deleted=$diskDeleted, failed=$diskFailed)"
                )
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanupConfirmedTombstones recipe media error: ${e.message}")
        }

        // --- 🆕 1.6-4: RECIPES ---
        try {
            val recipeIds = db.recipeDao().getConfirmedRecipesOlderThan(cutoff)
            if (recipeIds.isNotEmpty()) {
                // Медиа этих рецептов (если ещё не удалены выше) — тоже hard-delete.
                db.recipeMediaDao().hardDeleteByRecipeIds(recipeIds)
                db.recipeDao().hardDeleteConfirmedRecipes(recipeIds)
                Logger.log(TAG, "cleanupConfirmedTombstones: removed ${recipeIds.size} recipes (with their media)")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanupConfirmedTombstones recipes error: ${e.message}")
        }

        // --- 🆕 1.7-5: RECIPE FOLDERS ---
        try {
            val folderIds = db.recipeFolderDao().getConfirmedFoldersOlderThan(cutoff)
            if (folderIds.isNotEmpty()) {
                // Файлов на Диске у папок рецептов НЕТ — только БД.
                // Локальных файлов тоже нет (в отличие от папок каталога,
                // у которых есть иконки folder_<id>.jpg).
                db.recipeFolderDao().hardDeleteConfirmedFolders(folderIds)
                Logger.log(TAG, "cleanupConfirmedTombstones: removed ${folderIds.size} recipe folders")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanupConfirmedTombstones recipe folders error: ${e.message}")
        }
    }
}
