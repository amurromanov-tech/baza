package com.family.base.data.repository

import android.content.Context
import com.family.base.Config.AI_RECIPE_PROXY_URL
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxHelper
import com.family.base.data.local.dao.CountryCountRow
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeFolderEntity
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * 🆕 v21.1.0 (миграция 21→22): репозиторий для рецептов.
 * ...
 * 🆕 v23.2.2: исправлен импорт AI_RECIPE_PROXY_URL (добавлен Config.)
 * и добавлен явный ConnectionSpec (TLS 1.2 + TLS 1.3) с повторной попыткой
 * для устранения SSLHandshakeException при обращении к Cloudflare Worker.
 *
 * 🆕 v23.4.9 (миграция 23→24): КУХНЯ МИРА + РЕЙТИНГ.
 * Добавлены методы:
 *   - incrementLikes / incrementDislikes / incrementCookedCount
 *   - decrementLikes / decrementDislikes / decrementCookedCount (отмена долгого нажатия)
 *   - updateCuisineCountry
 *   - getRecipesBySource* (новые сортировки: по лайкам/дизлайкам/популярности)
 *   - getRecipesByCountry* (фильтр по стране + все сортировки)
 *   - getDistinctCountriesWithCount* (для BottomSheet «По странам»)
 *   - createRecipe теперь принимает cuisineCountry (обязательное поле)
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

    /**
     * 🆕 v23.4.9: добавлен параметр cuisineCountry (обязательное поле).
     * Дефолт "" — для обратной совместимости со старыми вызовами.
     */
    suspend fun createRecipe(
        title: String,
        description: String?,
        source: String,
        currentUser: String,
        folderId: String? = null,
        cuisineCountry: String = ""
    ): RecipeEntity? = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val recipe = RecipeEntity(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                source = source,
                folderId = folderId,
                cuisineCountry = cuisineCountry.trim(),
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
                "createRecipe: OK id=${recipe.id}, source=$source, folderId=$folderId, " +
                    "country='${recipe.cuisineCountry}', title='${recipe.title}'"
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
            Logger.log(TAG, "addMedia: OK id=${media.id}, recipe=$recipeId, type=$mediaType, order=$nextIndex, localName=$localName")
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
    // 🆕 4.1. v23.4.9: НОВЫЕ СОРТИРОВКИ (source)
    // ============================================================
    suspend fun getRecipesBySourceSorted(
        source: String,
        sortType: String
    ): List<RecipeEntity> = withContext(Dispatchers.IO) {
        try {
            when (sortType) {
                "newest" -> db.recipeDao().getBySourceNewest(source)
                "alphabetical" -> db.recipeDao().getBySourceAlphabetical(source)
                "likes" -> db.recipeDao().getBySourceByLikes(source)
                "dislikes" -> db.recipeDao().getBySourceByDislikes(source)
                "popularity" -> db.recipeDao().getBySourceByPopularity(source)
                else -> db.recipeDao().getBySourceNewest(source)
            }
        } catch (e: Exception) {
            Logger.log(TAG, "getRecipesBySourceSorted ERROR: ${e.message}", e)
            emptyList()
        }
    }

    // ============================================================
    // 🆕 4.2. v23.4.9: ФИЛЬТР ПО СТРАНЕ КУХНИ (source)
    // ============================================================
    suspend fun getRecipesByCountry(
        source: String,
        country: String,
        sortType: String
    ): List<RecipeEntity> = withContext(Dispatchers.IO) {
        try {
            when (sortType) {
                "newest" -> db.recipeDao().getBySourceAndCountry(source, country)
                "alphabetical" -> db.recipeDao().getBySourceAndCountryAlphabetical(source, country)
                "likes" -> db.recipeDao().getBySourceAndCountryByLikes(source, country)
                "dislikes" -> db.recipeDao().getBySourceAndCountryByDislikes(source, country)
                "popularity" -> db.recipeDao().getBySourceAndCountryByPopularity(source, country)
                else -> db.recipeDao().getBySourceAndCountry(source, country)
            }
        } catch (e: Exception) {
            Logger.log(TAG, "getRecipesByCountry ERROR: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun searchRecipesByCountry(
        source: String,
        country: String,
        query: String
    ): List<RecipeEntity> = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) db.recipeDao().getBySourceAndCountry(source, country)
            else db.recipeDao().searchBySourceAndCountry(source, country, query.trim())
        } catch (e: Exception) {
            Logger.log(TAG, "searchRecipesByCountry ERROR: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getDistinctCountriesWithCount(source: String): List<CountryCountRow> =
        withContext(Dispatchers.IO) {
            try {
                db.recipeDao().getDistinctCountriesWithCount(source)
            } catch (e: Exception) {
                Logger.log(TAG, "getDistinctCountriesWithCount ERROR: ${e.message}", e)
                emptyList()
            }
        }

    // ============================================================
    // 5. ПАПКИ РЕЦЕПТОВ (Шаг 1.7-2, миграция 22→23)
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
            Logger.log(TAG, "createFolder: OK id=${folder.id}, name='${folder.name}', parentId=$parentId")
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
                    Logger.log(TAG, "deleteFolder: not empty id=$folderId, recipes=$recipesCount, children=$childrenCount")
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

    suspend fun getRecipesInFolderSorted(
        folderId: String,
        sortType: String
    ): List<RecipeEntity> = withContext(Dispatchers.IO) {
        try {
            when (sortType) {
                "newest" -> db.recipeDao().getByFolderNewest(folderId)
                "alphabetical" -> db.recipeDao().getByFolderAlphabetical(folderId)
                "likes" -> db.recipeDao().getByFolderByLikes(folderId)
                "dislikes" -> db.recipeDao().getByFolderByDislikes(folderId)
                "popularity" -> db.recipeDao().getByFolderByPopularity(folderId)
                else -> db.recipeDao().getByFolderNewest(folderId)
            }
        } catch (e: Exception) {
            Logger.log(TAG, "getRecipesInFolderSorted ERROR: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getRecipesInFolderByCountry(
        folderId: String,
        country: String,
        sortType: String
    ): List<RecipeEntity> = withContext(Dispatchers.IO) {
        try {
            when (sortType) {
                "newest" -> db.recipeDao().getByFolderAndCountry(folderId, country)
                "alphabetical" -> db.recipeDao().getByFolderAndCountryAlphabetical(folderId, country)
                "likes" -> db.recipeDao().getByFolderAndCountryByLikes(folderId, country)
                "dislikes" -> db.recipeDao().getByFolderAndCountryByDislikes(folderId, country)
                "popularity" -> db.recipeDao().getByFolderAndCountryByPopularity(folderId, country)
                else -> db.recipeDao().getByFolderAndCountry(folderId, country)
            }
        } catch (e: Exception) {
            Logger.log(TAG, "getRecipesInFolderByCountry ERROR: ${e.message}", e)
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

    suspend fun searchInFolderByCountry(
        folderId: String,
        country: String,
        query: String
    ): List<RecipeEntity> = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) db.recipeDao().getByFolderAndCountry(folderId, country)
            else db.recipeDao().searchInFolderByCountry(folderId, country, query.trim())
        } catch (e: Exception) {
            Logger.log(TAG, "searchInFolderByCountry ERROR: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getDistinctCountriesWithCountInFolder(folderId: String): List<CountryCountRow> =
        withContext(Dispatchers.IO) {
            try {
                db.recipeDao().getDistinctCountriesWithCountInFolder(folderId)
            } catch (e: Exception) {
                Logger.log(TAG, "getDistinctCountriesWithCountInFolder ERROR: ${e.message}", e)
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

    suspend fun getRootRecipesSorted(
        source: String,
        sortType: String
    ): List<RecipeEntity> = withContext(Dispatchers.IO) {
        try {
            when (sortType) {
                "newest" -> db.recipeDao().getRootRecipesNewest(source)
                "alphabetical" -> db.recipeDao().getRootRecipesAlphabetical(source)
                "likes" -> db.recipeDao().getRootRecipesByLikes(source)
                "dislikes" -> db.recipeDao().getRootRecipesByDislikes(source)
                "popularity" -> db.recipeDao().getRootRecipesByPopularity(source)
                else -> db.recipeDao().getRootRecipesNewest(source)
            }
        } catch (e: Exception) {
            Logger.log(TAG, "getRootRecipesSorted ERROR: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getRootRecipesByCountry(
        source: String,
        country: String,
        sortType: String
    ): List<RecipeEntity> = withContext(Dispatchers.IO) {
        try {
            when (sortType) {
                "newest" -> db.recipeDao().getRootRecipesByCountry(source, country)
                "alphabetical" -> db.recipeDao().getRootRecipesByCountryAlphabetical(source, country)
                "likes" -> db.recipeDao().getRootRecipesByCountryByLikes(source, country)
                "dislikes" -> db.recipeDao().getRootRecipesByCountryByDislikes(source, country)
                "popularity" -> db.recipeDao().getRootRecipesByCountryByPopularity(source, country)
                else -> db.recipeDao().getRootRecipesByCountry(source, country)
            }
        } catch (e: Exception) {
            Logger.log(TAG, "getRootRecipesByCountry ERROR: ${e.message}", e)
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

    suspend fun searchRootRecipesByCountry(
        source: String,
        country: String,
        query: String
    ): List<RecipeEntity> = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) db.recipeDao().getRootRecipesByCountry(source, country)
            else db.recipeDao().searchRootRecipesByCountry(source, country, query.trim())
        } catch (e: Exception) {
            Logger.log(TAG, "searchRootRecipesByCountry ERROR: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getDistinctCountriesWithCountInRoot(source: String): List<CountryCountRow> =
        withContext(Dispatchers.IO) {
            try {
                db.recipeDao().getDistinctCountriesWithCountInRoot(source)
            } catch (e: Exception) {
                Logger.log(TAG, "getDistinctCountriesWithCountInRoot ERROR: ${e.message}", e)
                emptyList()
            }
        }

    // ============================================================
    // 🆕 6. v23.4.9: СЧЁТЧИКИ РЕЙТИНГА И СТРАНА КУХНИ
    // ============================================================

    /**
     * 🆕 +1 к лайкам рецепта.
     */
    suspend fun incrementLikes(recipeId: String, currentUser: String): RecipeEntity? =
        withContext(Dispatchers.IO) {
            try {
                val existing = db.recipeDao().getRecipeById(recipeId)
                if (existing == null || existing.isDeleted) {
                    Logger.log(TAG, "incrementLikes: recipe not found or deleted $recipeId")
                    return@withContext null
                }
                val now = System.currentTimeMillis()
                db.recipeDao().incrementLikes(recipeId, now, currentUser)
                val updated = db.recipeDao().getRecipeById(recipeId) ?: return@withContext null
                try {
                    OutboxHelper.logRecipe(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "incrementLikes: outbox log error: ${e.message}", e)
                }
                Logger.log(TAG, "incrementLikes: OK id=$recipeId, likes=${updated.likes}")
                updated
            } catch (e: Exception) {
                Logger.log(TAG, "incrementLikes ERROR: ${e.message}", e)
                null
            }
        }

    suspend fun incrementDislikes(recipeId: String, currentUser: String): RecipeEntity? =
        withContext(Dispatchers.IO) {
            try {
                val existing = db.recipeDao().getRecipeById(recipeId)
                if (existing == null || existing.isDeleted) {
                    Logger.log(TAG, "incrementDislikes: recipe not found or deleted $recipeId")
                    return@withContext null
                }
                val now = System.currentTimeMillis()
                db.recipeDao().incrementDislikes(recipeId, now, currentUser)
                val updated = db.recipeDao().getRecipeById(recipeId) ?: return@withContext null
                try {
                    OutboxHelper.logRecipe(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "incrementDislikes: outbox log error: ${e.message}", e)
                }
                Logger.log(TAG, "incrementDislikes: OK id=$recipeId, dislikes=${updated.dislikes}")
                updated
            } catch (e: Exception) {
                Logger.log(TAG, "incrementDislikes ERROR: ${e.message}", e)
                null
            }
        }

    suspend fun incrementCookedCount(recipeId: String, currentUser: String): RecipeEntity? =
        withContext(Dispatchers.IO) {
            try {
                val existing = db.recipeDao().getRecipeById(recipeId)
                if (existing == null || existing.isDeleted) {
                    Logger.log(TAG, "incrementCookedCount: recipe not found or deleted $recipeId")
                    return@withContext null
                }
                val now = System.currentTimeMillis()
                db.recipeDao().incrementCookedCount(recipeId, now, currentUser)
                val updated = db.recipeDao().getRecipeById(recipeId) ?: return@withContext null
                try {
                    OutboxHelper.logRecipe(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "incrementCookedCount: outbox log error: ${e.message}", e)
                }
                Logger.log(TAG, "incrementCookedCount: OK id=$recipeId, cookedCount=${updated.cookedCount}")
                updated
            } catch (e: Exception) {
                Logger.log(TAG, "incrementCookedCount ERROR: ${e.message}", e)
                null
            }
        }

    // ============================================================
    // 🆕 v23.4.9 (27.11.2026): ДЕКРЕМЕНТ СЧЁТЧИКОВ (отмена долгого нажатия)
    //
    // Защита от ухода в минус реализована в SQL (MAX(0, value - 1)).
    // Даже если счётчик уже 0, он останется 0.
    // ============================================================

    /**
     * 🆕 -1 к лайкам (отмена долгого нажатия).
     * Защита от ухода в минус: MAX(0, likes - 1) в SQL.
     */
    suspend fun decrementLikes(recipeId: String, currentUser: String): RecipeEntity? =
        withContext(Dispatchers.IO) {
            try {
                val existing = db.recipeDao().getRecipeById(recipeId)
                if (existing == null || existing.isDeleted) {
                    Logger.log(TAG, "decrementLikes: recipe not found or deleted $recipeId")
                    return@withContext null
                }
                val now = System.currentTimeMillis()
                db.recipeDao().decrementLikes(recipeId, now, currentUser)
                val updated = db.recipeDao().getRecipeById(recipeId) ?: return@withContext null
                try {
                    OutboxHelper.logRecipe(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "decrementLikes: outbox log error: ${e.message}", e)
                }
                Logger.log(TAG, "decrementLikes: OK id=$recipeId, likes=${updated.likes}")
                updated
            } catch (e: Exception) {
                Logger.log(TAG, "decrementLikes ERROR: ${e.message}", e)
                null
            }
        }

    /**
     * 🆕 -1 к дизлайкам (отмена долгого нажатия).
     */
    suspend fun decrementDislikes(recipeId: String, currentUser: String): RecipeEntity? =
        withContext(Dispatchers.IO) {
            try {
                val existing = db.recipeDao().getRecipeById(recipeId)
                if (existing == null || existing.isDeleted) {
                    Logger.log(TAG, "decrementDislikes: recipe not found or deleted $recipeId")
                    return@withContext null
                }
                val now = System.currentTimeMillis()
                db.recipeDao().decrementDislikes(recipeId, now, currentUser)
                val updated = db.recipeDao().getRecipeById(recipeId) ?: return@withContext null
                try {
                    OutboxHelper.logRecipe(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "decrementDislikes: outbox log error: ${e.message}", e)
                }
                Logger.log(TAG, "decrementDislikes: OK id=$recipeId, dislikes=${updated.dislikes}")
                updated
            } catch (e: Exception) {
                Logger.log(TAG, "decrementDislikes ERROR: ${e.message}", e)
                null
            }
        }

    /**
     * 🆕 -1 к счётчику приготовлений (отмена долгого нажатия).
     */
    suspend fun decrementCookedCount(recipeId: String, currentUser: String): RecipeEntity? =
        withContext(Dispatchers.IO) {
            try {
                val existing = db.recipeDao().getRecipeById(recipeId)
                if (existing == null || existing.isDeleted) {
                    Logger.log(TAG, "decrementCookedCount: recipe not found or deleted $recipeId")
                    return@withContext null
                }
                val now = System.currentTimeMillis()
                db.recipeDao().decrementCookedCount(recipeId, now, currentUser)
                val updated = db.recipeDao().getRecipeById(recipeId) ?: return@withContext null
                try {
                    OutboxHelper.logRecipe(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "decrementCookedCount: outbox log error: ${e.message}", e)
                }
                Logger.log(TAG, "decrementCookedCount: OK id=$recipeId, cookedCount=${updated.cookedCount}")
                updated
            } catch (e: Exception) {
                Logger.log(TAG, "decrementCookedCount ERROR: ${e.message}", e)
                null
            }
        }

    suspend fun updateCuisineCountry(
        recipeId: String,
        country: String,
        currentUser: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = db.recipeDao().getRecipeById(recipeId)
            if (existing == null) {
                Logger.log(TAG, "updateCuisineCountry: not found $recipeId")
                return@withContext false
            }
            if (existing.isDeleted) {
                Logger.log(TAG, "updateCuisineCountry: already tombstone $recipeId")
                return@withContext false
            }
            val cleanCountry = country.trim()
            if (existing.cuisineCountry == cleanCountry) {
                Logger.log(TAG, "updateCuisineCountry: same country, skipped $recipeId")
                return@withContext true
            }
            val now = System.currentTimeMillis()
            db.recipeDao().updateCuisineCountry(recipeId, cleanCountry, now, currentUser)
            val updated = db.recipeDao().getRecipeById(recipeId) ?: return@withContext false
            try {
                OutboxHelper.logRecipe(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
            } catch (e: Exception) {
                Logger.log(TAG, "updateCuisineCountry: outbox log error: ${e.message}", e)
            }
            Logger.log(TAG, "updateCuisineCountry: OK id=$recipeId, country='$cleanCountry'")
            true
        } catch (e: Exception) {
            Logger.log(TAG, "updateCuisineCountry ERROR: ${e.message}", e)
            false
        }
    }

    // ============================================================
    // 🆕 7. AI ГЕНЕРАЦИЯ РЕЦЕПТОВ (Шаг 2)
    // ============================================================
    private fun buildAiClient(): OkHttpClient {
        val connectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
            .build()
        return OkHttpClient.Builder()
            .connectionSpecs(listOf(connectionSpec))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun generateAiRecipe(prompt: String): String? = withContext(Dispatchers.IO) {
        val client = buildAiClient()
        val jsonBody = JSONObject().apply {
            put("prompt", prompt)
        }.toString()
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(AI_RECIPE_PROXY_URL)
            .post(requestBody)
            .build()
        var lastException: Exception? = null
        for (attempt in 1..2) {
            try {
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
            } catch (e: SSLHandshakeException) {
                lastException = e
                Logger.log(TAG, "generateAiRecipe: SSLHandshakeException on attempt $attempt: ${e.message}")
                if (attempt == 1) {
                    delay(500)
                }
            } catch (e: Exception) {
                Logger.log(TAG, "generateAiRecipe ERROR: ${e.message}", e)
                return@withContext null
            }
        }
        Logger.log(TAG, "generateAiRecipe: all attempts failed. Last error: ${lastException?.message}")
        return@withContext null
    }
}

// ============================================================
// 🆕 v23.4.9 (27.11.2026) — отмена долгим нажатием
// Добавлены методы декремента счётчиков:
//   - decrementLikes
//   - decrementDislikes
//   - decrementCookedCount
// Все методы:
//   - Проверяют существование рецепта и что он не удалён
//   - Вызывают DAO-методы с SQL-защитой MAX(0, value - 1)
//   - Логируют изменения в outbox для синка
//   - Возвращают обновлённый RecipeEntity или null при ошибке
// ============================================================
