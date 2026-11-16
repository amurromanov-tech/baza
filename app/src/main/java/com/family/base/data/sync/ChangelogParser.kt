package com.family.base.data.sync

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.ProcessedChangelogEntity
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeFolderEntity
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.data.local.entity.TaskEntity
import com.family.base.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Применяет входящий пакет changelog к локальной БД.
 *
 * 🆕 v21.1.0 (Шаг 1.6-5): рецепты и медиа рецептов.
 *  - applyRecipeEntry: INSERT/UPDATE/tombstone для recipes.
 *  - applyRecipeMediaEntry: INSERT/UPDATE/tombstone для recipe_media.
 *  - Защита от воскрешения: локальный tombstone с updatedAt >= remote
 *    не перезаписывается живой remote-записью.
 *  - При применении принудительно syncStatus = "synced" (иначе ping-pong).
 *  - Tombstone с Диска = уже разошёлся → deleteConfirmed = true.
 *
 * 🆕 v21.1.0+ (Шаг 1.7-5): папки рецептов.
 *  - applyRecipeFolderEntry: INSERT/UPDATE/tombstone для recipe_folders.
 *  - Логика идентична applyFolderEntry, но без isSystem (у папок рецептов
 *    системных нет — только пользовательские).
 *  - Защита от воскрешения по updatedAt (как у папок каталога).
 *
 * Правила (общие):
 *  1. Свои пакеты не применяем — только processed_changelogs.
 *  2. Конфликты по updatedAt / updatedDate.
 *  3. ACTION_DELETE (старый формат) → tombstone локально, не hard-delete.
 *  4. History — append-only.
 */
class ChangelogParser(
    private val db: AppDatabase,
    private val deviceId: String
) {

    private val gson = Gson()
    private val TAG = "ChangelogParser"

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
            // 🆕 1.6-5: рецепты и медиа.
            OutboxEntity.ENTITY_RECIPE -> applyRecipeEntry(entry)
            OutboxEntity.ENTITY_RECIPE_MEDIA -> applyRecipeMediaEntry(entry)
            // 🆕 1.7-5: папки рецептов.
            OutboxEntity.ENTITY_RECIPE_FOLDER -> applyRecipeFolderEntry(entry)
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

        if (entry.action == OutboxEntity.ACTION_DELETE) {
            if (local == null) {
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
            db.itemDao().confirmItemDeleted(entry.entityId)
            Logger.log(TAG, "applyItem DELETE → tombstone: id=${entry.entityId} '${local.name}'")
            return true
        }

        val remote: ItemEntity = try {
            gson.fromJson(entry.payloadJson, ItemEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyItem: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        val remoteSynced = if (remote.isDeleted) {
            remote.copy(
                syncStatus = ItemEntity.SYNC_STATUS_SYNCED,
                deleteConfirmed = true
            )
        } else {
            remote.copy(syncStatus = ItemEntity.SYNC_STATUS_SYNCED)
        }

        if (local == null) {
            db.itemDao().insertItem(remoteSynced)
            Logger.log(
                TAG,
                "applyItem INSERT: id=${entry.entityId} '${remoteSynced.name}' " +
                    "(action=${entry.action}, isDeleted=${remoteSynced.isDeleted})"
            )
            return true
        }

        if (local.isDeleted && !remoteSynced.isDeleted && local.updatedDate >= remoteSynced.updatedDate) {
            Logger.log(
                TAG,
                "applyItem SKIP: local tombstone wins (local.updatedDate=${local.updatedDate} " +
                    ">= remote.updatedDate=${remoteSynced.updatedDate}) for id=${entry.entityId}"
            )
            return false
        }

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

        if (local == null) {
            db.folderDao().insertFolder(remoteSynced)
            Logger.log(
                TAG,
                "applyFolder INSERT: id=${entry.entityId} '${remoteSynced.name}' " +
                    "(action=${entry.action}, isDeleted=${remoteSynced.isDeleted})"
            )
            return true
        }

        if (local.isSystem && remoteSynced.isDeleted) {
            Logger.log(TAG, "applyFolder SKIP: cannot tombstone system folder ${entry.entityId}")
            return false
        }

        if (local.isDeleted && !remoteSynced.isDeleted && local.updatedAt >= remoteSynced.updatedAt) {
            Logger.log(
                TAG,
                "applyFolder SKIP: local tombstone wins (local.updatedAt=${local.updatedAt} " +
                    ">= remote.updatedAt=${remoteSynced.updatedAt}) for id=${entry.entityId}"
            )
            return false
        }

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

    private suspend fun applyTaskEntry(entry: ChangelogEntry): Boolean {
        val local = db.taskDao().getById(entry.entityId)

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

        if (local.isDeleted && !remoteSynced.isDeleted && local.updatedAt >= remoteSynced.updatedAt) {
            Logger.log(
                TAG,
                "applyTask SKIP: local tombstone wins (local.updatedAt=${local.updatedAt} " +
                    ">= remote.updatedAt=${remoteSynced.updatedAt}) for id=${entry.entityId}"
            )
            return false
        }

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
    // 🆕 v21.1.0 (Шаг 1.6-5): RECIPE
    // ============================================================

    /**
     * Применить входящее изменение рецепта.
     *
     * Логика идентична item/folder:
     *  - ACTION_DELETE (старый формат) → tombstone локально;
     *  - парсим payload в RecipeEntity;
     *  - tombstone → deleteConfirmed = true;
     *  - INSERT если локально нет;
     *  - защита от воскрешения (локальный tombstone новее);
     *  - UPDATE если remote.updatedAt > local.updatedAt.
     *
     * ⚠️ RecipeEntity.source ("ai"/"user") и createdBy приходят из payload
     * как есть — не переписываем их. Если на другом устройстве рецепт
     * создан в «Моих», он и здесь попадёт в «Мои».
     *
     * 🆕 Шаг 1.7-5: folderId приходит из payload. Если рецепт перенесли
     * в папку на другом устройстве — folderId обновится здесь.
     * Если папка ещё не пришла (её пакет позже) — рецепт будет с folderId,
     * указывающим на несуществующую папку. UI покажет его в корне
     * (getRootRecipes фильтрует folderId IS NULL — такой рецепт не покажется
     * ни в корне, ни в папке, пока папка не приедет). Это редкий случай
     * (порядок пакетов), лечится следующим PULL.
     */
    private suspend fun applyRecipeEntry(entry: ChangelogEntry): Boolean {
        val local = db.recipeDao().getRecipeById(entry.entityId)

        // ── ACTION_DELETE (старый формат) → tombstone локально ──
        if (entry.action == OutboxEntity.ACTION_DELETE) {
            if (local == null) {
                Logger.log(TAG, "applyRecipe DELETE: id=${entry.entityId} not found locally, skip")
                return false
            }
            if (entry.changedAt < local.updatedAt) {
                Logger.log(
                    TAG,
                    "applyRecipe DELETE SKIP: local newer (local=${local.updatedAt}, remote=${entry.changedAt})"
                )
                return false
            }
            if (local.isDeleted) {
                Logger.log(TAG, "applyRecipe DELETE SKIP: already tombstone ${entry.entityId}")
                return false
            }
            db.recipeDao().markRecipeDeleted(entry.entityId, entry.changedAt, entry.changedBy)
            db.recipeDao().confirmRecipeDeleted(entry.entityId)
            Logger.log(TAG, "applyRecipe DELETE → tombstone: id=${entry.entityId} '${local.title}'")
            return true
        }

        // ── PARSE PAYLOAD ──
        val remote: RecipeEntity = try {
            gson.fromJson(entry.payloadJson, RecipeEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyRecipe: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        val remoteSynced = if (remote.isDeleted) {
            remote.copy(
                syncStatus = RecipeEntity.SYNC_STATUS_SYNCED,
                deleteConfirmed = true
            )
        } else {
            remote.copy(syncStatus = RecipeEntity.SYNC_STATUS_SYNCED)
        }

        // ── INSERT ──
        if (local == null) {
            db.recipeDao().insertRecipe(remoteSynced)
            Logger.log(
                TAG,
                "applyRecipe INSERT: id=${entry.entityId} '${remoteSynced.title}' " +
                    "(action=${entry.action}, source=${remoteSynced.source}, " +
                    "folderId=${remoteSynced.folderId}, isDeleted=${remoteSynced.isDeleted})"
            )
            return true
        }

        // ── ЗАЩИТА ОТ ВОСКРЕШЕНИЯ ──
        if (local.isDeleted && !remoteSynced.isDeleted && local.updatedAt >= remoteSynced.updatedAt) {
            Logger.log(
                TAG,
                "applyRecipe SKIP: local tombstone wins (local.updatedAt=${local.updatedAt} " +
                    ">= remote.updatedAt=${remoteSynced.updatedAt}) for id=${entry.entityId}"
            )
            return false
        }

        // ── UPDATE (remote новее) ──
        if (remoteSynced.updatedAt > local.updatedAt) {
            val merged = remoteSynced.copy(
                // description не мержим — это свободный текст, remote побеждает целиком
                description = remoteSynced.description,
                // source не перетираем, если remote пустой (на всякий случай)
                source = remoteSynced.source.ifBlank { local.source },
                // 🆕 1.7-5: folderId приходит из payload как есть.
                // null — значит, рецепт в корне (или его вынули из папки).
                folderId = remoteSynced.folderId,
            )
            db.recipeDao().updateRecipe(merged)
            Logger.log(
                TAG,
                "applyRecipe UPDATE: id=${entry.entityId} '${merged.title}' " +
                    "(remote newer: ${remoteSynced.updatedAt} > ${local.updatedAt}, " +
                    "folderId=${merged.folderId}, isDeleted=${merged.isDeleted})"
            )
            return true
        } else {
            Logger.log(
                TAG,
                "applyRecipe UPDATE SKIP: local newer (local=${local.updatedAt} >= remote=${remoteSynced.updatedAt})"
            )
            return false
        }
    }

    // ============================================================
    // 🆕 v21.1.0 (Шаг 1.6-5): RECIPE_MEDIA
    // ============================================================

    /**
     * Применить входящее изменение медиа рецепта.
     *
     * Логика идентична recipe:
     *  - ACTION_DELETE → tombstone локально;
     *  - парсим payload в RecipeMediaEntity;
     *  - tombstone → deleteConfirmed = true;
     *  - INSERT если локально нет;
     *  - защита от воскрешения;
     *  - UPDATE если remote.createdAt > local.createdAt.
     *
     * ⚠️ imageSynced: если с Диска пришло imageSynced = true — значит,
     * файл уже залит (с другого устройства). Ставим как есть, чтобы
     * uploadUnsyncedRecipeMedia не пытался заливать повторно.
     *
     * У RecipeMediaEntity нет поля updatedAt (только createdAt).
     * Для разрешения конфликтов используем createdAt.
     */
    private suspend fun applyRecipeMediaEntry(entry: ChangelogEntry): Boolean {
        val local = db.recipeMediaDao().getMediaById(entry.entityId)

        // ── ACTION_DELETE (старый формат) → tombstone локально ──
        if (entry.action == OutboxEntity.ACTION_DELETE) {
            if (local == null) {
                Logger.log(TAG, "applyRecipeMedia DELETE: id=${entry.entityId} not found locally, skip")
                return false
            }
            if (entry.changedAt < local.createdAt) {
                Logger.log(
                    TAG,
                    "applyRecipeMedia DELETE SKIP: local newer (local=${local.createdAt}, remote=${entry.changedAt})"
                )
                return false
            }
            if (local.isDeleted) {
                Logger.log(TAG, "applyRecipeMedia DELETE SKIP: already tombstone ${entry.entityId}")
                return false
            }
            db.recipeMediaDao().markMediaDeleted(entry.entityId, entry.changedAt, entry.changedBy)
            db.recipeMediaDao().confirmMediaDeleted(entry.entityId)
            Logger.log(TAG, "applyRecipeMedia DELETE → tombstone: id=${entry.entityId}")
            return true
        }

        // ── PARSE PAYLOAD ──
        val remote: RecipeMediaEntity = try {
            gson.fromJson(entry.payloadJson, RecipeMediaEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyRecipeMedia: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        val remoteSynced = if (remote.isDeleted) {
            remote.copy(
                syncStatus = RecipeMediaEntity.SYNC_STATUS_SYNCED,
                deleteConfirmed = true
            )
        } else {
            remote.copy(syncStatus = RecipeMediaEntity.SYNC_STATUS_SYNCED)
        }

        // ── INSERT ──
        if (local == null) {
            db.recipeMediaDao().insertMedia(remoteSynced)
            Logger.log(
                TAG,
                "applyRecipeMedia INSERT: id=${entry.entityId} recipeId=${remoteSynced.recipeId} " +
                    "type=${remoteSynced.mediaType} (action=${entry.action}, " +
                    "isDeleted=${remoteSynced.isDeleted}, imageSynced=${remoteSynced.imageSynced})"
            )
            return true
        }

        // ── ЗАЩИТА ОТ ВОСКРЕШЕНИЯ ──
        if (local.isDeleted && !remoteSynced.isDeleted && local.createdAt >= remoteSynced.createdAt) {
            Logger.log(
                TAG,
                "applyRecipeMedia SKIP: local tombstone wins (local.createdAt=${local.createdAt} " +
                    ">= remote.createdAt=${remoteSynced.createdAt}) for id=${entry.entityId}"
            )
            return false
        }

        // ── UPDATE (remote новее по createdAt) ──
        if (remoteSynced.createdAt > local.createdAt) {
            db.recipeMediaDao().updateMedia(remoteSynced)
            Logger.log(
                TAG,
                "applyRecipeMedia UPDATE: id=${entry.entityId} recipeId=${remoteSynced.recipeId} " +
                    "(remote newer: ${remoteSynced.createdAt} > ${local.createdAt}, " +
                    "isDeleted=${remoteSynced.isDeleted}, imageSynced=${remoteSynced.imageSynced})"
            )
            return true
        } else {
            Logger.log(
                TAG,
                "applyRecipeMedia UPDATE SKIP: local newer/equal (local=${local.createdAt} " +
                    ">= remote=${remoteSynced.createdAt})"
            )
            return false
        }
    }

    // ============================================================
    // 🆕 v21.1.0+ (Шаг 1.7-5): RECIPE_FOLDER
    // ============================================================

    /**
     * Применить входящее изменение папки рецептов.
     *
     * Логика идентична applyFolderEntry, но:
     *  - у папок рецептов НЕТ isSystem (только пользовательские);
     *  - нет iconUrl/iconThumbnailUrl/parentItemId/path;
     *  - есть parentId (вложенность) — приходит из payload как есть.
     *
     *  - ACTION_DELETE (старый формат) → tombstone локально;
     *  - парсим payload в RecipeFolderEntity;
     *  - tombstone → deleteConfirmed = true;
     *  - INSERT если локально нет;
     *  - защита от воскрешения (локальный tombstone новее);
     *  - UPDATE если remote.updatedAt > local.updatedAt.
     *
     * ⚠️ parentId: если папка приехала с parentId, указывающим на ещё
     * не приехавшую папку, — она будет висеть «в воздухе» (не видна
     * ни в корне, ни в родителе). Лечится следующим PULL, когда
     * родитель приедет. Это редкий случай (порядок пакетов).
     */
    private suspend fun applyRecipeFolderEntry(entry: ChangelogEntry): Boolean {
        val local = db.recipeFolderDao().getFolderById(entry.entityId)

        // ── ACTION_DELETE (старый формат) → tombstone локально ──
        if (entry.action == OutboxEntity.ACTION_DELETE) {
            if (local == null) {
                Logger.log(TAG, "applyRecipeFolder DELETE: id=${entry.entityId} not found locally, skip")
                return false
            }
            if (entry.changedAt < local.updatedAt) {
                Logger.log(
                    TAG,
                    "applyRecipeFolder DELETE SKIP: local newer (local=${local.updatedAt}, remote=${entry.changedAt})"
                )
                return false
            }
            if (local.isDeleted) {
                Logger.log(TAG, "applyRecipeFolder DELETE SKIP: already tombstone ${entry.entityId}")
                return false
            }
            db.recipeFolderDao().markFolderDeleted(entry.entityId, entry.changedAt, entry.changedBy)
            db.recipeFolderDao().confirmFolderDeleted(entry.entityId)
            Logger.log(TAG, "applyRecipeFolder DELETE → tombstone: id=${entry.entityId} '${local.name}'")
            return true
        }

        // ── PARSE PAYLOAD ──
        val remote: RecipeFolderEntity = try {
            gson.fromJson(entry.payloadJson, RecipeFolderEntity::class.java)
        } catch (e: Exception) {
            Logger.log(TAG, "applyRecipeFolder: cannot parse payload for id=${entry.entityId}: ${e.message}")
            return false
        }

        val remoteSynced = if (remote.isDeleted) {
            remote.copy(
                syncStatus = RecipeFolderEntity.SYNC_STATUS_SYNCED,
                deleteConfirmed = true
            )
        } else {
            remote.copy(syncStatus = RecipeFolderEntity.SYNC_STATUS_SYNCED)
        }

        // ── INSERT ──
        if (local == null) {
            db.recipeFolderDao().insertFolder(remoteSynced)
            Logger.log(
                TAG,
                "applyRecipeFolder INSERT: id=${entry.entityId} '${remoteSynced.name}' " +
                    "(action=${entry.action}, parentId=${remoteSynced.parentId}, " +
                    "isDeleted=${remoteSynced.isDeleted})"
            )
            return true
        }

        // ── ЗАЩИТА ОТ ВОСКРЕШЕНИЯ ──
        if (local.isDeleted && !remoteSynced.isDeleted && local.updatedAt >= remoteSynced.updatedAt) {
            Logger.log(
                TAG,
                "applyRecipeFolder SKIP: local tombstone wins (local.updatedAt=${local.updatedAt} " +
                    ">= remote.updatedAt=${remoteSynced.updatedAt}) for id=${entry.entityId}"
            )
            return false
        }

        // ── UPDATE (remote новее) ──
        if (remoteSynced.updatedAt > local.updatedAt) {
            db.recipeFolderDao().updateFolder(remoteSynced)
            Logger.log(
                TAG,
                "applyRecipeFolder UPDATE: id=${entry.entityId} '${remoteSynced.name}' " +
                    "(remote newer: ${remoteSynced.updatedAt} > ${local.updatedAt}, " +
                    "parentId=${remoteSynced.parentId}, isDeleted=${remoteSynced.isDeleted})"
            )
            return true
        } else {
            Logger.log(
                TAG,
                "applyRecipeFolder UPDATE SKIP: local newer (local=${local.updatedAt} >= remote=${remoteSynced.updatedAt})"
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
