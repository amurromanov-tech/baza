package com.family.base.data.repository

import android.content.Context
import com.family.base.Config.AI_RECIPE_PROXY_URL
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxHelper
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeFolderEntity
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
🆕 v21.1.0 (миграция 21→22): репозиторий для рецептов.
Две зоны ответственности:
СИДИРОВАНИЕ СИСТЕМНЫХ ПАПОК «Рецепты».
Метод ensureRecipesFolders(currentUser) вызывается из MainViewModel
при старте (по аналогии с ensureGalleryFolder / ensureSystemFolder).
Создаёт три папки, если их ещё нет:
 - «🍳 Рецепты»       (systemKey = "recipes",    parentId = null)
 - «🤖 Рецепты ИИ»    (systemKey = "recipes_ai", parentId = id «Рецептов»)
 - «📝 Мои рецепты»   (systemKey = "recipes_my", parentId = id «Рецептов»)
Идемпотентно: повторный вызов ничего не ломает. Если «Рецепты» есть,
а дочерней не хватает (например, пользователь как-то ухитрился её
удалить — хотя isSystem защищает), досоздаёт отсутствующую.
ВАЖНО: папки создаются с фиксированными id (константы из FolderEntity),
чтобы на всех устройствах это были одни и те же записи, и синк
через folders.json не плодил дубликаты.
CRUD РЕЦЕПТОВ.
Обёртки над RecipeDao / RecipeMediaDao, единая точка правды
для записи в outbox (changelog).
Стиль — как у CatalogRepository:
- createRecipe / updateRecipe / deleteRecipe — с outbox;
- addMedia / deleteMedia — с outbox;
- никакой работы с файлами на Диске здесь нет — это делает
  отдельный слой (SyncManager), потому что файлы рецептов
  грузятся отдельной очередью uploadUnsyncedRecipeMedia.
🆕 v21.1.0 (Шаг 1.5-2): addMedia получил параметр mediaId
(по умолчанию null → сгенерируется UUID внутри). Это нужно, чтобы
id строки recipe_media совпадал с именем файла в filesDir/recipe_media/
(<mediaId>.jpg / <mediaId>.mp4 / <mediaId>_thumb.jpg). См. RecipeMediaController.
Старые вызовы (без mediaId) продолжают работать как раньше.
🆕 v21.1.0+ (Шаг 1.7-2, миграция 22→23): ПАПКИ РЕЦЕПТОВ.
Добавлен блок «5. ПАПКИ РЕЦЕПТОВ» — CRUD папок (createFolder,
renameFolder, deleteFolder с проверкой «не пуста»), навигация
(getFolders(parentId)), перемещение рецептов (moveRecipeToFolder),
чтение рецептов уровня (getRecipesInFolder / getRootRecipes).
Папки рецептов живут ТОЛЬКО у «Моих рецептов» (source = "user").
У «Рецептов ИИ» (source = "ai") папок нет — folderId всегда null.
🆕 Шаг 2: AI-ГЕНЕРАЦИЯ РЕЦЕПТОВ.
Добавлен блок «6. AI ГЕНЕРАЦИЯ РЕЦЕПТОВ» — метод generateAiRecipe(prompt)
отправляет запрос на Cloudflare Worker AI-прокси (URL в Config.AI_RECIPE_PROXY_URL),
получает сгенерированный текст рецепта. Ключ API надёжно спрятан на сервере.
*/
class RecipesRepository(
    private val db: AppDatabase,
    private val appContext: Context
) {
    private val TAG = "RecipesRepository"

    // ============================================================
    // 1. СИДИРОВАНИЕ СИСТЕМНЫХ ПАПОК «РЕЦЕПТЫ»
    // ============================================================
    suspend fun ensureRecipesFolders(currentUser: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                var created = false

                // ---------- 1. Корневая «🍳 Рецепты» ----------
                val existingRoot = db.folderDao().getFolderById(FolderEntity.SYSTEM_ID_RECIPES)
                val rootId: String
                if (existingRoot == null) {
                    val now = System.currentTimeMillis()
                    val root = FolderEntity(
                        id = FolderEntity.SYSTEM_ID_RECIPES,
                        name = FolderEntity.SYSTEM_NAME_RECIPES,
                        parentId = null,
                        parentItemId = null,
                        createdAt = now,
                        createdBy = currentUser,
                        updatedAt = now,
                        path = FolderEntity.SYSTEM_NAME_RECIPES,
                        isSystem = true,
                        systemKey = FolderEntity.SYSTEM_KEY_RECIPES,
                        syncStatus = FolderEntity.SYNC_STATUS_SYNCED
                    )
                    db.folderDao().insertFolder(root)
                    rootId = root.id
                    created = true
                    Logger.log(TAG, "ensureRecipesFolders: created root '${root.name}' (id=${root.id})")
                } else {
                    rootId = existingRoot.id
                    if (!existingRoot.isSystem || existingRoot.systemKey != FolderEntity.SYSTEM_KEY_RECIPES) {
                        val fixed = existingRoot.copy(
                            isSystem = true,
                            systemKey = FolderEntity.SYSTEM_KEY_RECIPES,
                            parentId = null,
                            parentItemId = null
                        )
                        db.folderDao().updateFolder(fixed)
                        Logger.log(TAG, "ensureRecipesFolders: fixed root flags for '${fixed.name}'")
                    }
                }

                // ---------- 2. «🤖 Рецепты ИИ» ----------
                val existingAi = db.folderDao().getFolderById(FolderEntity.SYSTEM_ID_RECIPES_AI)
                if (existingAi == null) {
                    val now = System.currentTimeMillis()
                    val aiFolder = FolderEntity(
                        id = FolderEntity.SYSTEM_ID_RECIPES_AI,
                        name = FolderEntity.SYSTEM_NAME_RECIPES_AI,
                        parentId = rootId,
                        parentItemId = null,
                        createdAt = now,
                        createdBy = currentUser,
                        updatedAt = now,
                        path = "${FolderEntity.SYSTEM_NAME_RECIPES}/${FolderEntity.SYSTEM_NAME_RECIPES_AI}",
                        isSystem = true,
                        systemKey = FolderEntity.SYSTEM_KEY_RECIPES_AI,
                        syncStatus = FolderEntity.SYNC_STATUS_SYNCED
                    )
                    db.folderDao().insertFolder(aiFolder)
                    created = true
                    Logger.log(TAG, "ensureRecipesFolders: created '${aiFolder.name}' (id=${aiFolder.id}, parent=$rootId)")
                } else if (existingAi.parentId != rootId) {
                    val fixed = existingAi.copy(parentId = rootId)
                    db.folderDao().updateFolder(fixed)
                    Logger.log(TAG, "ensureRecipesFolders: re-parented '${existingAi.name}' → $rootId")
                }

                // ---------- 3. «📝 Мои рецепты» ----------
                val existingMy = db.folderDao().getFolderById(FolderEntity.SYSTEM_ID_RECIPES_MY)
                if (existingMy == null) {
                    val now = System.currentTimeMillis()
                    val myFolder = FolderEntity(
                        id = FolderEntity.SYSTEM_ID_RECIPES_MY,
                        name = FolderEntity.SYSTEM_NAME_RECIPES_MY,
                        parentId = rootId,
                        parentItemId = null,
                        createdAt = now,
                        createdBy = currentUser,
                        updatedAt = now,
                        path = "${FolderEntity.SYSTEM_NAME_RECIPES}/${FolderEntity.SYSTEM_NAME_RECIPES_MY}",
                        isSystem = true,
                        systemKey = FolderEntity.SYSTEM_KEY_RECIPES_MY,
                        syncStatus = FolderEntity.SYNC_STATUS_SYNCED
                    )
                    db.folderDao().insertFolder(myFolder)
                    created = true
                    Logger.log(TAG, "ensureRecipesFolders: created '${myFolder.name}' (id=${myFolder.id}, parent=$rootId)")
                } else if (existingMy.parentId != rootId) {
                    val fixed = existingMy.copy(parentId = rootId)
                    db.folderDao().updateFolder(fixed)
                    Logger.log(TAG, "ensureRecipesFolders: re-parented '${existingMy.name}' → $rootId")
                }

                Pair(created, rootId)
            } catch (e: Exception) {
                Logger.log(TAG, "ensureRecipesFolders ERROR: ${e.message}", e)
                Pair(false, null)
            }
        }

    // ============================================================
    // 2. РЕЦЕПТЫ — CRUD
    // ============================================================
    suspend fun createRecipe(
        title: String,
        description: String?,
        source: String,
        currentUser: String,
        folderId: String? = null
    ): RecipeEntity? = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val recipe = RecipeEntity(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                source = source,
                folderId = folderId,
                createdBy = currentUser,
                createdAt = now,
                updatedBy = currentUser,
                updatedAt = now,
                syncStatus = RecipeEntity.SYNC_STATUS_PENDING
            )
            db.recipeDao().insertRecipe(recipe)

            try {
                OutboxHelper.logRecipe(db, recipe, OutboxEntity.ACTION_CREATE, currentUser)
            } catch (e: Exception) {
                Logger.log(TAG, "createRecipe: outbox log error: ${e.message}", e)
            }

            Logger.log(
                TAG,
                "createRecipe: OK id=${recipe.id}, source=$source, folderId=$folderId, title='${recipe.title}'"
            )
            recipe
        } catch (e: Exception) {
            Logger.log(TAG, "createRecipe ERROR: ${e.message}", e)
            null
        }
    }

    suspend fun updateRecipe(
        recipeId: String,
        newTitle: String,
        newDescription: String?,
        currentUser: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = db.recipeDao().getRecipeById(recipeId)
            if (existing == null) {
                Logger.log(TAG, "updateRecipe: not found $recipeId")
                return@withContext false
            }
            if (existing.isDeleted) {
                Logger.log(TAG, "updateRecipe: already tombstone $recipeId")
                return@withContext false
            }

            val now = System.currentTimeMillis()
            val updated = existing.copy(
                title = newTitle.trim(),
                description = newDescription?.trim()?.takeIf { it.isNotEmpty() },
                updatedBy = currentUser,
                updatedAt = now,
                syncStatus = RecipeEntity.SYNC_STATUS_PENDING
            )
            db.recipeDao().updateRecipe(updated)

            try {
                OutboxHelper.logRecipe(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
            } catch (e: Exception) {
                Logger.log(TAG, "updateRecipe: outbox log error: ${e.message}", e)
            }

            Logger.log(TAG, "updateRecipe: OK id=$recipeId")
            true
        } catch (e: Exception) {
            Logger.log(TAG, "updateRecipe ERROR: ${e.message}", e)
            false
        }
    }

    suspend fun deleteRecipe(recipeId: String, currentUser: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val existing = db.recipeDao().getRecipeById(recipeId)
                if (existing == null) {
                    Logger.log(TAG, "deleteRecipe: not found $recipeId")
                    return@withContext false
                }
                if (existing.isDeleted) {
                    Logger.log(TAG, "deleteRecipe: already tombstone $recipeId")
                    return@withContext false
                }

                val now = System.currentTimeMillis()
                db.recipeDao().markRecipeDeleted(recipeId, now, currentUser)
                val after = db.recipeDao().getRecipeById(recipeId) ?: return@withContext false

                try {
                    OutboxHelper.logRecipe(db, after, OutboxEntity.ACTION_UPDATE, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "deleteRecipe: outbox log error: ${e.message}", e)
                }

                val affected = db.recipeMediaDao().markAllByRecipeDeleted(recipeId, now, currentUser)
                if (affected > 0) {
                    val allRaw = db.recipeMediaDao().getAllRaw().filter { it.recipeId == recipeId && it.isDeleted }
                    allRaw.forEach { m ->
                        try {
                            OutboxHelper.logRecipeMedia(db, m, OutboxEntity.ACTION_UPDATE, currentUser)
                        } catch (e: Exception) {
                            Logger.log(TAG, "deleteRecipe: outbox log media error (${m.id}): ${e.message}", e)
                        }
                    }
                    Logger.log(TAG, "deleteRecipe: media tombstoned count=$affected for recipe=$recipeId")
                }

                Logger.log(TAG, "deleteRecipe: OK id=$recipeId")
                true
            } catch (e: Exception) {
                Logger.log(TAG, "deleteRecipe ERROR: ${e.message}", e)
                false
            }
        }

    // ============================================================
    // 3. МЕДИА — CRUD
    // ============================================================
    suspend fun addMedia(
        recipeId: String,
        mediaType: String,
        localName: String,
        thumbName: String?,
        mimeType: String?,
        currentUser: String,
        mediaId: String? = null
    ): RecipeMediaEntity? = withContext(Dispatchers.IO) {
        try {
            val recipe = db.recipeDao().getRecipeById(recipeId)
            if (recipe == null) {
                Logger.log(TAG, "addMedia: recipe not found $recipeId")
                return@withContext null
            }
            if (recipe.isDeleted) {
                Logger.log(TAG, "addMedia: recipe is tombstone $recipeId")
                return@withContext null
            }

            val nextIndex = db.recipeMediaDao().nextOrderIndex(recipeId)
            val now = System.currentTimeMillis()
            val id = mediaId ?: UUID.randomUUID().toString()
            val media = RecipeMediaEntity(
                id = id,
                recipeId = recipeId,
                mediaType = mediaType,
                orderIndex = nextIndex,
                imageUrl = localName,
                thumbnailUrl = thumbName,
                imageSynced = false,
                mimeType = mimeType,
                createdAt = now,
                createdBy = currentUser,
                syncStatus = RecipeMediaEntity.SYNC_STATUS_PENDING
            )
            db.recipeMediaDao().insertMedia(media)

            try {
                OutboxHelper.logRecipeMedia(db, media, OutboxEntity.ACTION_CREATE, currentUser)
            } catch (e: Exception) {
                Logger.log(TAG, "addMedia: outbox log error: ${e.message}", e)
            }

            Logger.log(
                TAG,
                "addMedia: OK id=${media.id}, recipe=$recipeId, type=$mediaType, order=$nextIndex, localName=$localName"
            )
            media
        } catch (e: Exception) {
            Logger.log(TAG, "addMedia ERROR: ${e.message}", e)
            null
        }
    }

    suspend fun deleteMedia(mediaId: String, currentUser: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val media = db.recipeMediaDao().getMediaById(mediaId)
                if (media == null) {
                    Logger.log(TAG, "deleteMedia: not found $mediaId")
                    return@withContext false
                }
                if (media.isDeleted) {
                    Logger.log(TAG, "deleteMedia: already tombstone $mediaId")
                    return@withContext false
                }

                val now = System.currentTimeMillis()
                db.recipeMediaDao().markMediaDeleted(mediaId, now, currentUser)
                val after = db.recipeMediaDao().getMediaById(mediaId) ?: return@withContext false

                try {
                    OutboxHelper.logRecipeMedia(db, after, OutboxEntity.ACTION_UPDATE, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "deleteMedia: outbox log error: ${e.message}", e)
                }

                Logger.log(TAG, "deleteMedia: OK id=$mediaId")
                true
            } catch (e: Exception) {
                Logger.log(TAG, "deleteMedia ERROR: ${e.message}", e)
                false
            }
        }

    // ============================================================
    // 4. ЧТЕНИЕ ДЛЯ UI (тонкие обёртки)
    // ============================================================
    suspend fun getRecipesBySource(source: String, sortNewest: Boolean): List<RecipeEntity> =
        withContext(Dispatchers.IO) {
            try {
                if (sortNewest) db.recipeDao().getBySourceNewest(source)
                else db.recipeDao().getBySourceAlphabetical(source)
            } catch (e: Exception) {
                Logger.log(TAG, "getRecipesBySource ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun searchRecipes(source: String, query: String): List<RecipeEntity> =
        withContext(Dispatchers.IO) {
            try {
                if (query.isBlank()) db.recipeDao().getBySourceNewest(source)
                else db.recipeDao().searchBySource(source, query.trim())
            } catch (e: Exception) {
                Logger.log(TAG, "searchRecipes ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun getRecipeById(recipeId: String): RecipeEntity? =
        withContext(Dispatchers.IO) {
            try {
                db.recipeDao().getLiveRecipeById(recipeId)
            } catch (e: Exception) {
                Logger.log(TAG,"getRecipeById ERROR: ${e.message}", e)
                null
            }
        }

    suspend fun getPhotos(recipeId: String): List<RecipeMediaEntity> =
        withContext(Dispatchers.IO) {
            try {
                db.recipeMediaDao().getPhotos(recipeId)
            } catch (e: Exception) {
                Logger.log(TAG, "getPhotos ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun getVideos(recipeId: String): List<RecipeMediaEntity> =
        withContext(Dispatchers.IO) {
            try {
                db.recipeMediaDao().getVideos(recipeId)
            } catch (e: Exception) {
                Logger.log(TAG, "getVideos ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun getAllMedia(recipeId: String): List<RecipeMediaEntity> =
        withContext(Dispatchers.IO) {
            try {
                db.recipeMediaDao().getByRecipe(recipeId)
            } catch (e: Exception) {
                Logger.log(TAG, "getAllMedia ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun countBySource(source: String): Int =
        withContext(Dispatchers.IO) {
            try {
                db.recipeDao().countBySource(source)
            } catch (e: Exception) {
                Logger.log(TAG, "countBySource ERROR: ${e.message}", e)
                0
            }
        }

    // ============================================================
    // 🆕 5. ПАПКИ РЕЦЕПТОВ (Шаг 1.7-2, миграция 22→23)
    // ============================================================
    suspend fun createFolder(
        name: String,
        parentId: String?,
        currentUser: String
    ): RecipeFolderEntity? = withContext(Dispatchers.IO) {
        try {
            val cleanName = name.trim()
            if (cleanName.isEmpty()) {
                Logger.log(TAG, "createFolder: empty name, skipped")
                return@withContext null
            }

            if (parentId != null) {
                val parent = db.recipeFolderDao().getLiveFolderById(parentId)
                if (parent == null) {
                    Logger.log(TAG, "createFolder: parent not found or deleted $parentId")
                    return@withContext null
                }
            }

            val now = System.currentTimeMillis()
            val folder = RecipeFolderEntity(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                parentId = parentId,
                createdBy = currentUser,
                createdAt = now,
                updatedBy = currentUser,
                updatedAt = now,
                syncStatus = RecipeFolderEntity.SYNC_STATUS_PENDING
            )
            db.recipeFolderDao().insertFolder(folder)

            try {
                OutboxHelper.logRecipeFolder(db, folder, OutboxEntity.ACTION_CREATE, currentUser)
            } catch (e: Exception) {
                Logger.log(TAG, "createFolder: outbox log error: ${e.message}", e)
            }

            Logger.log(
                TAG,
                "createFolder: OK id=${folder.id}, name='${folder.name}', parentId=$parentId"
            )
            folder
        } catch (e: Exception) {
            Logger.log(TAG, "createFolder ERROR: ${e.message}", e)
            null
        }
    }

    suspend fun renameFolder(
        folderId: String,
        newName: String,
        currentUser: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanName = newName.trim()
            if (cleanName.isEmpty()) {
                Logger.log(TAG, "renameFolder: empty name, skipped")
                return@withContext false
            }

            val existing = db.recipeFolderDao().getFolderById(folderId)
            if (existing == null) {
                Logger.log(TAG, "renameFolder: not found $folderId")
                return@withContext false
            }
            if (existing.isDeleted) {
                Logger.log(TAG, "renameFolder: already tombstone $folderId")
                return@withContext false
            }
            if (existing.name == cleanName) {
                Logger.log(TAG, "renameFolder: same name, skipped $folderId")
                return@withContext true
            }

            val now = System.currentTimeMillis()
            val updated = existing.copy(
                name = cleanName,
                updatedBy = currentUser,
                updatedAt = now,
                syncStatus = RecipeFolderEntity.SYNC_STATUS_PENDING
            )
            db.recipeFolderDao().updateFolder(updated)

            try {
                OutboxHelper.logRecipeFolder(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
            } catch (e: Exception) {
                Logger.log(TAG, "renameFolder: outbox log error: ${e.message}", e)
            }

            Logger.log(TAG, "renameFolder: OK id=$folderId, newName='$cleanName'")
            true
        } catch (e: Exception) {
            Logger.log(TAG, "renameFolder ERROR: ${e.message}", e)
            false
        }
    }

    suspend fun deleteFolder(folderId: String, currentUser: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val existing = db.recipeFolderDao().getFolderById(folderId)
                if (existing == null) {
                    Logger.log(TAG, "deleteFolder: not found $folderId")
                    return@withContext false
                }
                if (existing.isDeleted) {
                    Logger.log(TAG, "deleteFolder: already tombstone $folderId")
                    return@withContext false
                }

                val recipesCount = db.recipeDao().countByFolder(folderId)
                val childrenCount = db.recipeFolderDao().countChildren(folderId)
                if (recipesCount > 0 || childrenCount > 0) {
                    Logger.log(
                        TAG,
                        "deleteFolder: not empty id=$folderId, recipes=$recipesCount, children=$childrenCount"
                    )
                    return@withContext false
                }

                val now = System.currentTimeMillis()
                db.recipeFolderDao().markFolderDeleted(folderId, now, currentUser)
                val after = db.recipeFolderDao().getFolderById(folderId) ?: return@withContext false

                try {
                    OutboxHelper.logRecipeFolder(db, after, OutboxEntity.ACTION_UPDATE, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "deleteFolder: outbox log error: ${e.message}", e)
                }

                Logger.log(TAG, "deleteFolder: OK id=$folderId")
                true
            } catch (e: Exception) {
                Logger.log(TAG, "deleteFolder ERROR: ${e.message}", e)
                false
            }
        }

    suspend fun moveRecipeToFolder(
        recipeId: String,
        folderId: String?,
        currentUser: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = db.recipeDao().getRecipeById(recipeId)
            if (existing == null) {
                Logger.log(TAG, "moveRecipeToFolder: recipe not found $recipeId")
                return@withContext false
            }
            if (existing.isDeleted) {
                Logger.log(TAG, "moveRecipeToFolder: recipe is tombstone $recipeId")
                return@withContext false
            }
            if (existing.folderId == folderId) {
                Logger.log(TAG, "moveRecipeToFolder: same folder, skipped $recipeId → $folderId")
                return@withContext true
            }

            if (folderId != null) {
                val folder = db.recipeFolderDao().getLiveFolderById(folderId)
                if (folder == null) {
                    Logger.log(TAG, "moveRecipeToFolder: folder not found or deleted $folderId")
                    return@withContext false
                }
            }

            val now = System.currentTimeMillis()
            db.recipeDao().moveRecipeToFolder(recipeId, folderId, now, currentUser)
            val after = db.recipeDao().getRecipeById(recipeId) ?: return@withContext false

            try {
                OutboxHelper.logRecipe(db, after, OutboxEntity.ACTION_UPDATE, currentUser)
            } catch (e: Exception) {
                Logger.log(TAG, "moveRecipeToFolder: outbox log error: ${e.message}", e)
            }

            Logger.log(TAG, "moveRecipeToFolder: OK id=$recipeId → folderId=$folderId")
            true
        } catch (e: Exception) {
            Logger.log(TAG, "moveRecipeToFolder ERROR: ${e.message}", e)
            false
        }
    }

    suspend fun getFolders(parentId: String?, sortNewest: Boolean): List<RecipeFolderEntity> =
        withContext(Dispatchers.IO) {
            try {
                if (sortNewest) db.recipeFolderDao().getByParentNewest(parentId)
                else db.recipeFolderDao().getByParentAlphabetical(parentId)
            } catch (e: Exception) {
                Logger.log(TAG, "getFolders ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun getAllFolders(): List<RecipeFolderEntity> =
        withContext(Dispatchers.IO) {
            try {
                db.recipeFolderDao().getAllLive()
            } catch (e: Exception) {
                Logger.log(TAG, "getAllFolders ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun getRecipesInFolder(folderId: String, sortNewest: Boolean): List<RecipeEntity> =
        withContext(Dispatchers.IO) {
            try {
                if (sortNewest) db.recipeDao().getByFolderNewest(folderId)
                else db.recipeDao().getByFolderAlphabetical(folderId)
            } catch (e: Exception) {
                Logger.log(TAG, "getRecipesInFolder ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun getRootRecipes(source: String, sortNewest: Boolean): List<RecipeEntity> =
        withContext(Dispatchers.IO) {
            try {
                if (sortNewest) db.recipeDao().getRootRecipesNewest(source)
                else db.recipeDao().getRootRecipesAlphabetical(source)
            } catch (e: Exception) {
                Logger.log(TAG, "getRootRecipes ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun searchRootRecipes(source: String, query: String): List<RecipeEntity> =
        withContext(Dispatchers.IO) {
            try {
                if (query.isBlank()) db.recipeDao().getRootRecipesNewest(source)
                else db.recipeDao().searchRootRecipes(source, query.trim())
            } catch (e: Exception) {
                Logger.log(TAG, "searchRootRecipes ERROR: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun searchInFolder(folderId: String, query: String): List<RecipeEntity> =
        withContext(Dispatchers.IO) {
            try {
                if (query.isBlank()) db.recipeDao().getByFolderNewest(folderId)
                else db.recipeDao().searchInFolder(folderId, query.trim())
            } catch (e: Exception) {
                Logger.log(TAG, "searchInFolder ERROR: ${e.message}", e)
                emptyList()
            }
        }

    // ============================================================
    // 🆕 6. AI ГЕНЕРАЦИЯ РЕЦЕПТОВ (Шаг 2)
    // ============================================================
    /**
     * Генерация рецепта через AI-прокси Cloudflare Worker.
     * @param prompt запрос пользователя (например, "Рецепт блинов на молоке").
     * @return сгенерированный текст рецепта или null в случае ошибки.
     */
    suspend fun generateAiRecipe(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

            val jsonBody = JSONObject().apply {
                put("prompt", prompt)
            }.toString()

            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(AI_RECIPE_PROXY_URL)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Logger.log(TAG, "generateAiRecipe: HTTP ${response.code}, body=${responseBody.take(300)}")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val success = json.optBoolean("success", false)

            if (success) {
                val recipeText = json.optString("recipe", "")
                Logger.log(TAG, "generateAiRecipe: OK, length=${recipeText.length}")
                return@withContext recipeText.ifBlank { null }
            } else {
                val error = json.optString("error", "Unknown error")
                Logger.log(TAG, "generateAiRecipe: API error: $error")
                return@withContext null
            }
        } catch (e: Exception) {
            Logger.log(TAG, "generateAiRecipe ERROR: ${e.message}", e)
            return@withContext null
        }
    }
}
