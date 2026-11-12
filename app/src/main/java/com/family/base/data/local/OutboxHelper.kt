package com.family.base.data.local

import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.data.local.entity.TaskEntity
import com.family.base.util.Logger
import com.google.gson.Gson

/**
 * Единая точка записи изменений в outbox (changelog).
 *
 * Вызывается из MainViewModel / TaskRepository / RecipesRepository
 * при каждом локальном изменении Item / Folder / Task / History /
 * Recipe / RecipeMedia.
 *
 * Отвечает за:
 *  - Сериализацию entity в JSON.
 *  - Формирование OutboxEntity.
 *  - Вставку в БД (через OutboxDao).
 *
 * ⚠️ НЕ вызывает сеть. Только локальная запись.
 * Отправка на Диск — задача SyncManager.
 */
object OutboxHelper {

    private const val TAG = "OutboxHelper"
    private val gson = Gson()

    /**
     * Записать изменение Item в outbox.
     */
    suspend fun logItem(
        db: AppDatabase,
        item: ItemEntity,
        action: String,
        changedBy: String
    ) {
        try {
            val json = gson.toJson(item)
            val entry = OutboxEntity(
                entityType = OutboxEntity.ENTITY_ITEM,
                entityId = item.id,
                action = action,
                payloadJson = json,
                changedBy = changedBy,
                createdAt = System.currentTimeMillis()
            )
            db.outboxDao().insert(entry)
            Logger.log(TAG, "logItem: action=$action, id=${item.id}, name='${item.name}'")
        } catch (e: Exception) {
            Logger.log(TAG, "logItem ERROR: action=$action, id=${item.id}, ${e.message}")
        }
    }

    /**
     * Записать изменение Folder в outbox.
     */
    suspend fun logFolder(
        db: AppDatabase,
        folder: FolderEntity,
        action: String,
        changedBy: String
    ) {
        try {
            val json = gson.toJson(folder)
            val entry = OutboxEntity(
                entityType = OutboxEntity.ENTITY_FOLDER,
                entityId = folder.id,
                action = action,
                payloadJson = json,
                changedBy = changedBy,
                createdAt = System.currentTimeMillis()
            )
            db.outboxDao().insert(entry)
            Logger.log(TAG, "logFolder: action=$action, id=${folder.id}, name='${folder.name}'")
        } catch (e: Exception) {
            Logger.log(TAG, "logFolder ERROR: action=$action, id=${folder.id}, ${e.message}")
        }
    }

    /**
     * Записать изменение Task в outbox.
     */
    suspend fun logTask(
        db: AppDatabase,
        task: TaskEntity,
        action: String,
        changedBy: String
    ) {
        try {
            val json = gson.toJson(task)
            val entry = OutboxEntity(
                entityType = OutboxEntity.ENTITY_TASK,
                entityId = task.id,
                action = action,
                payloadJson = json,
                changedBy = changedBy,
                createdAt = System.currentTimeMillis()
            )
            db.outboxDao().insert(entry)
            Logger.log(TAG, "logTask: action=$action, id=${task.id}, title='${task.title}'")
        } catch (e: Exception) {
            Logger.log(TAG, "logTask ERROR: action=$action, id=${task.id}, ${e.message}")
        }
    }

    /**
     * 🆕 v16.2: Записать новую запись истории в outbox.
     *
     * История — append-only. Поэтому action всегда "create".
     * entityId = HistoryEntry.id.
     */
    suspend fun logHistory(
        db: AppDatabase,
        entry: HistoryEntry,
        changedBy: String
    ) {
        try {
            val json = gson.toJson(entry)
            val outbox = OutboxEntity(
                entityType = OutboxEntity.ENTITY_HISTORY,
                entityId = entry.id,
                action = OutboxEntity.ACTION_CREATE,
                payloadJson = json,
                changedBy = changedBy,
                createdAt = System.currentTimeMillis()
            )
            db.outboxDao().insert(outbox)
            Logger.log(TAG, "logHistory: id=${entry.id}, action=${entry.action}, itemId=${entry.itemId}")
        } catch (e: Exception) {
            Logger.log(TAG, "logHistory ERROR: id=${entry.id}, ${e.message}")
        }
    }

    // ============================================================
    // 🆕 v21.1.0: РЕЦЕПТЫ И МЕДИА РЕЦЕПТОВ
    // ============================================================

    /**
     * Записать изменение рецепта в outbox.
     *
     * RecipeEntity сериализуется целиком (title, description, source,
     * syncStatus, isDeleted, deletedAt, deletedBy, deleteConfirmed и т.д.)
     * — значит новые поля уезжают в changelog автоматически, если
     * завтра добавим что-то ещё в сущность. Не переписывать payload
     * вручную — потеряются поля.
     */
    suspend fun logRecipe(
        db: AppDatabase,
        recipe: RecipeEntity,
        action: String,
        changedBy: String
    ) {
        try {
            val json = gson.toJson(recipe)
            val entry = OutboxEntity(
                entityType = OutboxEntity.ENTITY_RECIPE,
                entityId = recipe.id,
                action = action,
                payloadJson = json,
                changedBy = changedBy,
                createdAt = System.currentTimeMillis()
            )
            db.outboxDao().insert(entry)
            Logger.log(
                TAG,
                "logRecipe: action=$action, id=${recipe.id}, source='${recipe.source}', title='${recipe.title}'"
            )
        } catch (e: Exception) {
            Logger.log(TAG, "logRecipe ERROR: action=$action, id=${recipe.id}, ${e.message}")
        }
    }

    /**
     * Записать изменение медиа рецепта в outbox.
     *
     * RecipeMediaEntity сериализуется целиком (recipeId, mediaType,
     * orderIndex, imageUrl, thumbnailUrl, imageSynced, mimeType,
     * isDeleted и т.д.). Одна строка = один файл = одна запись
     * в changelog.
     */
    suspend fun logRecipeMedia(
        db: AppDatabase,
        media: RecipeMediaEntity,
        action: String,
        changedBy: String
    ) {
        try {
            val json = gson.toJson(media)
            val entry = OutboxEntity(
                entityType = OutboxEntity.ENTITY_RECIPE_MEDIA,
                entityId = media.id,
                action = action,
                payloadJson = json,
                changedBy = changedBy,
                createdAt = System.currentTimeMillis()
            )
            db.outboxDao().insert(entry)
            Logger.log(
                TAG,
                "logRecipeMedia: action=$action, id=${media.id}, recipeId=${media.recipeId}, " +
                    "type=${media.mediaType}, order=${media.orderIndex}"
            )
        } catch (e: Exception) {
            Logger.log(TAG, "logRecipeMedia ERROR: action=$action, id=${media.id}, ${e.message}")
        }
    }

    /**
     * Записать только факт удаления (payload — минимальный JSON с id).
     * Используется для hard-delete случаев, когда entity уже нет в БД.
     */
    suspend fun logDeletion(
        db: AppDatabase,
        entityType: String,
        entityId: String,
        changedBy: String,
        nameHint: String? = null
    ) {
        try {
            val minimalPayload = buildString {
                append("{")
                append("\"id\":\"${escape(entityId)}\"")
                if (nameHint != null) {
                    append(",\"name\":\"${escape(nameHint)}\"")
                }
                append("}")
            }
            val entry = OutboxEntity(
                entityType = entityType,
                entityId = entityId,
                action = OutboxEntity.ACTION_DELETE,
                payloadJson = minimalPayload,
                changedBy = changedBy,
                createdAt = System.currentTimeMillis()
            )
            db.outboxDao().insert(entry)
            Logger.log(TAG, "logDeletion: type=$entityType, id=$entityId")
        } catch (e: Exception) {
            Logger.log(TAG, "logDeletion ERROR: type=$entityType, id=$entityId, ${e.message}")
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
