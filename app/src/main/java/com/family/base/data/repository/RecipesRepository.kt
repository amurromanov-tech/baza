package com.family.base.data.repository

import android.content.Context
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxHelper
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 🆕 v21.1.0 (миграция 21→22): репозиторий для рецептов.
 *
 * Две зоны ответственности:
 *
 * 1. СИДИРОВАНИЕ СИСТЕМНЫХ ПАПОК «Рецепты».
 *    Метод ensureRecipesFolders(currentUser) вызывается из MainViewModel
 *    при старте (по аналогии с ensureGalleryFolder / ensureSystemFolder).
 *    Создаёт три папки, если их ещё нет:
 *      - «🍳 Рецепты»       (systemKey = "recipes",    parentId = null)
 *      - «🤖 Рецепты ИИ»    (systemKey = "recipes_ai", parentId = id «Рецептов»)
 *      - «📝 Мои рецепты»   (systemKey = "recipes_my", parentId = id «Рецептов»)
 *
 *    Идемпотентно: повторный вызов ничего не ломает. Если «Рецепты» есть,
 *    а дочерней не хватает (например, пользователь как-то ухитрился её
 *    удалить — хотя isSystem защищает), досоздаёт отсутствующую.
 *
 *    ВАЖНО: папки создаются с фиксированными id (константы из FolderEntity),
 *    чтобы на всех устройствах это были одни и те же записи, и синк
 *    через folders.json не плодил дубликаты.
 *
 * 2. CRUD РЕЦЕПТОВ.
 *    Обёртки над RecipeDao / RecipeMediaDao, единая точка правды
 *    для записи в outbox (changelog).
 *
 *    Стиль — как у CatalogRepository:
 *     - createRecipe / updateRecipe / deleteRecipe — с outbox;
 *     - addMedia / deleteMedia — с outbox;
 *     - никакой работы с файлами на Диске здесь нет — это делает
 *       отдельный слой (SyncManager), потому что файлы рецептов
 *       грузятся отдельной очередью uploadUnsyncedRecipeMedia.
 *
 * 🆕 v21.1.0 (Шаг 1.5-2): addMedia получил параметр mediaId
 *  (по умолчанию null → сгенерируется UUID внутри). Это нужно, чтобы
 *  id строки recipe_media совпадал с именем файла в filesDir/recipe_media/
 *  (<mediaId>.jpg / <mediaId>.mp4 / <mediaId>_thumb.jpg). См. RecipeMediaController.
 *  Старые вызовы (без mediaId) продолжают работать как раньше.
 */
class RecipesRepository(
    private val db: AppDatabase,
    private val appContext: Context
) {

    private val TAG = "RecipesRepository"

    // ============================================================
    // 1. СИДИРОВАНИЕ СИСТЕМНЫХ ПАПОК «РЕЦЕПТЫ»
    // ============================================================

    /**
     * Гарантирует, что три системные папки «Рецептов» существуют.
     *
     * Вызывается из MainViewModel.checkFirstLaunch() — один раз при старте
     * (после ensureGalleryFolder). Идемпотентно.
     *
     * @param currentUser имя текущего пользователя (AppUser.name).
     * @return Pair(создано ли что-то, id корневой папки «Рецепты»).
     */
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
                    // На всякий случай выставим флаг isSystem, если запись
                    // когда-то была создана без него (защита от ручного вмешательства).
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
                    // Если по какой-то причине дочерняя папка оказалась
                    // в другом месте — вернём её под «Рецепты».
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

    /**
     * Создать рецепт.
     *
     * Пишет в outbox как ACTION_CREATE и ставит syncStatus = 'pending',
     * чтобы синк подхватил. Источник (source) задаёт вызывающий:
     *  - RecipeEntity.SOURCE_AI   — для «Рецептов ИИ»;
     *  - RecipeEntity.SOURCE_USER — для «Моих рецептов».
     *
     * @return созданный RecipeEntity (с реальным id), либо null при ошибке.
     */
    suspend fun createRecipe(
        title: String,
        description: String?,
        source: String,
        currentUser: String
    ): RecipeEntity? = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val recipe = RecipeEntity(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                source = source,
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

            Logger.log(TAG, "createRecipe: OK id=${recipe.id}, source=$source, title='${recipe.title}'")
            recipe
        } catch (e: Exception) {
            Logger.log(TAG, "createRecipe ERROR: ${e.message}", e)
            null
        }
    }

    /**
     * Обновить рецепт (название / описание).
     * Пишет в outbox как ACTION_UPDATE.
     */
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

    /**
     * Удалить рецепт (tombstone).
     *
     * Медиа рецепта тоже помечаются tombstone (markAllByRecipeDeleted),
     * чтобы каждое удаление файла уехало отдельной записью в changelog.
     *
     * ⚠️ Физическое удаление файлов с Диска — НЕ здесь. Это сделает
     * cleanupOldRecipeTombstones через год, вместе с hardDelete.
     * Локальные файлы (filesDir/recipe_media/<recipeId>/) тоже удаляются
     * не сразу — при hard-delete в cleanup (Шаг 1.6).
     *
     * Права: удалять рецепт может Алексей (любой) или автор (свой) —
     * проверка в UI/ViewModel, здесь только техническая операция.
     */
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

                // Помечаем всё медиа рецепта tombstone (отдельные записи в outbox).
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

    /**
     * Добавить медиа (фото или видео) к рецепту.
     *
     * Файл УЖЕ должен быть сохранён локально до вызова этого метода
     * (через RecipeMediaController). Здесь только запись в БД + outbox.
     *
     * 🆕 Шаг 1.5-2: параметр mediaId.
     *  - Если передан (RecipeMediaController сгенерировал UUID) — используем его
     *    как id строки recipe_media. Тогда id == имя файла (без расширения),
     *    что упрощает синк на Шаге 1.6.
     *  - Если null (старые вызовы) — генерируем UUID внутри, как раньше.
     *
     * @param recipeId   id рецепта.
     * @param mediaType  RecipeMediaEntity.TYPE_PHOTO / TYPE_VIDEO.
     * @param localName  имя локального файла (например "abc-123.jpg").
     * @param thumbName  имя файла превью (для видео) или null.
     * @param mimeType   MIME (например "video/mp4").
     * @param currentUser имя пользователя.
     * @param mediaId    🆕 id для строки recipe_media (null → сгенерируется).
     * @return созданный RecipeMediaEntity, либо null при ошибке.
     */
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

    /**
     * Удалить одно медиа (tombstone).
     * Файл с Диска удалится позже — через cleanup (Шаг 1.6).
     * Локальный файл (filesDir/recipe_media/...) удаляется вызывающим
     * (RecipeEditActivity / RecipeDetailActivity) через RecipeMediaController,
     * чтобы UI сразу видел исчезновение.
     */
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
                Logger.log(TAG, "getRecipeById ERROR: ${e.message}", e)
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

    /**
     * Всё медиа рецепта (фото + видео), по orderIndex.
     * Нужно для экрана редактирования (Шаг 1.5-2) — чтобы показать
     * уже добавленные файлы в одном RecyclerView.
     */
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
}
