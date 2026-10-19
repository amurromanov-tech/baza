package com.family.base.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.family.base.BaseApplication
import com.family.base.Config
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxLogger
import com.family.base.data.local.entity.*
import com.family.base.data.remote.YandexDiskApi
import com.family.base.data.remote.model.UserModel
import com.family.base.data.remote.model.UsersFile
import com.family.base.util.DiskPaths
import com.family.base.util.Logger
import com.family.base.util.NetworkCallExecutor
import com.family.base.util.NetworkCallExecutor.NetworkException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.UnknownHostException

class CatalogRepository(private val db: AppDatabase) {

    private val gson = Gson()
    private val TAG = "CatalogRepository"

    private val ITEMS_FILENAME = "items.json"
    private val FOLDERS_FILENAME = "folders.json"
    private val HISTORY_FILENAME = "history.json"
    private val TASKS_FILENAME = "tasks.json"

    private var folderPathCache: String? = null

    @Volatile
    private var dnsFailureUntil: Long = 0L

    private fun isDnsBlocked(): Boolean =
        System.currentTimeMillis() < dnsFailureUntil

    private fun noteDnsFailure() {
        dnsFailureUntil = System.currentTimeMillis() + 60_000L
    }

    fun isNetworkAvailable(): Boolean = !isDnsBlocked()

    // ============================================================
    // ПАПКИ
    // ============================================================

    suspend fun getFolders(parentId: String?): List<FolderEntity> {
        return if (parentId == null) {
            db.folderDao().getRootFolders()
        } else {
            db.folderDao().getFoldersByParent(parentId)
        }
    }

    suspend fun getAllFolders(): List<FolderEntity> {
        return db.folderDao().getAllLiveFolders()
    }

    suspend fun createFolder(name: String, parentId: String?, parentPath: String?, creator: String): FolderEntity {
        val path = if (parentPath != null) "$parentPath/$name" else "${Config.SHARED_FOLDER_NAME}/$name"
        val folder = FolderEntity(name = name, parentId = parentId, createdBy = creator, path = path)
        db.folderDao().insertFolder(folder)
        return folder
    }

    suspend fun createFolderInItem(
        name: String,
        parentItemId: String,
        parentFolderId: String?,
        creator: String
    ): FolderEntity {
        val folder = FolderEntity(
            name = name,
            parentId = parentFolderId,
            parentItemId = parentItemId,
            createdBy = creator,
            path = name
        )
        db.folderDao().insertFolder(folder)
        Logger.log(TAG, "createFolderInItem: name=$name, parentItemId=$parentItemId, parentFolderId=$parentFolderId")
        return folder
    }

    /**
     * Удаление папки — tombstone (№10 v19).
     *
     * ⚠️ Системные папки (isSystem = true) удалять НЕЛЬЗЯ.
     * Физически запись не стирается: isDeleted = 1, syncStatus = 'pending',
     * deleteConfirmed = 0. Уедет через changelog, чтобы удаление
     * разошлось по всем устройствам и не воскресло при merge.
     *
     * @param folderId  id удаляемой папки.
     * @param deletedBy кто удалил (AppUser.name).
     */
    suspend fun deleteFolder(folderId: String, deletedBy: String) {
        val folder = db.folderDao().getFolderById(folderId)
        if (folder == null) {
            Logger.log(TAG, "deleteFolder: folder not found $folderId")
            return
        }
        if (folder.isSystem) {
            Logger.log(TAG, "deleteFolder: BLOCKED for system folder $folderId")
            return
        }
        if (folder.isDeleted) {
            Logger.log(TAG, "deleteFolder: already tombstone $folderId")
            return
        }

        val now = System.currentTimeMillis()
        db.folderDao().markFolderDeleted(folderId, now, deletedBy)

        val updated = db.folderDao().getFolderById(folderId) ?: return
        OutboxLogger.folderChanged(
            db = db,
            folder = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = deletedBy
        )
        Logger.log(TAG, "deleteFolder: tombstone set for $folderId by $deletedBy")
    }

    /**
     * Подтвердить tombstone папки после успешного PUSH.
     * Вызывается из SyncManager.
     */
    suspend fun confirmFolderDeleted(folderId: String) {
        db.folderDao().confirmFolderDeleted(folderId)
    }

    /**
     * Физическое удаление подтверждённых tombstones папок старше thresholdMs.
     * Вызывается из cleanup.
     *
     * @return количество физически удалённых записей.
     */
    suspend fun cleanupOldFolderTombstones(thresholdMs: Long): Int {
        val ids = db.folderDao().getConfirmedFoldersOlderThan(thresholdMs)
        if (ids.isEmpty()) return 0
        db.folderDao().hardDeleteConfirmedFolders(ids)
        Logger.log(TAG, "cleanupOldFolderTombstones: removed ${ids.size} folders")
        return ids.size
    }

    /**
     * Обновление папки.
     * ⚠️ Системные папки можно обновлять только внутри (updatedAt),
     * но НЕ менять name / parentId / isSystem.
     */
    suspend fun updateFolderSafe(updated: FolderEntity) {
        val existing = db.folderDao().getFolderById(updated.id)
        if (existing?.isSystem == true) {
            val safe = updated.copy(
                name = existing.name,
                parentId = existing.parentId,
                parentItemId = existing.parentItemId,
                isSystem = existing.isSystem,
                systemKey = existing.systemKey,
                path = existing.path
            )
            db.folderDao().updateFolder(safe)
            Logger.log(TAG, "updateFolderSafe: system folder ${updated.id} — protected fields unchanged")
            return
        }
        db.folderDao().updateFolder(updated)
    }

    // ============================================================
    // ПРЕДМЕТЫ
    // ============================================================

    suspend fun getItems(parentId: String?): List<ItemEntity> =
        db.itemDao().getItemsByParent(parentId).filter { it.parentItemId == null }

    suspend fun addItem(item: ItemEntity) {
        val entity = item.copy()
        entity.computeExpiryFields()
        db.itemDao().insertItem(entity)
    }

    suspend fun updateItem(item: ItemEntity) {
        val updated = item.copy()
        updated.computeExpiryFields()
        db.itemDao().updateItem(updated)
    }

    suspend fun updateQuantity(itemId: String, newQty: Int) {
        db.itemDao().updateQuantity(itemId, newQty)
    }

    /**
     * Удаление предмета — tombstone (№10 v19).
     *
     * Физически запись не стирается: isDeleted = 1, syncStatus = 'pending',
     * deleteConfirmed = 0. Уедет через changelog, чтобы удаление
     * разошлось по всем устройствам и не воскресло при merge.
     *
     * Архив (isArchived = 1) — НЕ tombstone. Архивные предметы остаются
     * в базе навсегда. Tombstone ставится только на ошибочно
     * занесённые данные, которые пользователь хочет удалить.
     *
     * @param itemId    id удаляемого предмета.
     * @param deletedBy кто удалил (AppUser.name).
     */
    suspend fun deleteItem(itemId: String, deletedBy: String) {
        val item = db.itemDao().getItemById(itemId)
        if (item == null) {
            Logger.log(TAG, "deleteItem: not found $itemId")
            return
        }
        if (item.isDeleted) {
            Logger.log(TAG, "deleteItem: already tombstone $itemId")
            return
        }

        val now = System.currentTimeMillis()
        db.itemDao().markItemDeleted(itemId, now, deletedBy)

        val updated = db.itemDao().getItemById(itemId) ?: return
        OutboxLogger.itemChanged(
            db = db,
            item = updated,
            action = OutboxEntity.ACTION_UPDATE,
            changedBy = deletedBy
        )
        Logger.log(TAG, "deleteItem: tombstone set for $itemId by $deletedBy")
    }

    /**
     * Подтвердить tombstone предмета после успешного PUSH.
     * Вызывается из SyncManager.
     */
    suspend fun confirmItemDeleted(itemId: String) {
        db.itemDao().confirmItemDeleted(itemId)
    }

    /**
     * Физическое удаление подтверждённых tombstones предметов старше thresholdMs.
     * Вызывается из cleanup.
     *
     * @return количество физически удалённых записей.
     */
    suspend fun cleanupOldItemTombstones(thresholdMs: Long): Int {
        val ids = db.itemDao().getConfirmedItemsOlderThan(thresholdMs)
        if (ids.isEmpty()) return 0
        db.itemDao().hardDeleteConfirmedItems(ids)
        Logger.log(TAG, "cleanupOldItemTombstones: removed ${ids.size} items")
        return ids.size
    }

    // ============================================================
    // B-3: ОТВЯЗАТЬ ВСЕХ ДЕТЕЙ
    // ============================================================
    suspend fun detachAllChildren(itemId: String): Int {
        return withContext(Dispatchers.IO) {
            val parent = db.itemDao().getItemById(itemId)
            if (parent == null) {
                Logger.log(TAG, "detachAllChildren: parent not found $itemId")
                return@withContext 0
            }

            val newParentId = parent.parentId
            val now = System.currentTimeMillis()

            val folderChildren = db.folderDao().getFoldersByParentItem(itemId)
            for (folder in folderChildren) {
                val updated = folder.copy(
                    parentId = newParentId,
                    parentItemId = null,
                    updatedAt = now
                )
                db.folderDao().updateFolder(updated)
            }

            val itemChildren = db.itemDao().getItemsByParentItemRaw(itemId)
            for (item in itemChildren) {
                val updated = item.copy(
                    parentId = newParentId,
                    parentItemId = null,
                    updatedDate = now
                )
                updated.computeExpiryFields()
                db.itemDao().updateItem(updated)
            }

            val total = folderChildren.size + itemChildren.size
            Logger.log(
                TAG,
                "detachAllChildren: item=$itemId, detached folders=${folderChildren.size}, " +
                    "items=${itemChildren.size}, newParentId=$newParentId"
            )
            total
        }
    }

    suspend fun countChildren(itemId: String): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            val folderCount = db.folderDao().getFolderChildCount(itemId)
            val itemCount = db.itemDao().getItemChildCount(itemId)
            Pair(folderCount, itemCount)
        }
    }

    suspend fun getNestedItems(parentItemId: String): List<ItemEntity> =
        db.itemDao().getItemsByParentItem(parentItemId)

    suspend fun getNestedFolders(parentItemId: String): List<FolderEntity> =
        db.folderDao().getFoldersByParentItem(parentItemId)

    // ============================================================
    // ИСТОРИЯ
    // ============================================================

    suspend fun addHistory(entry: HistoryEntry) = db.historyDao().insertEntry(entry)

    suspend fun getHistoryForItem(itemId: String): List<HistoryEntry> =
        db.historyDao().getHistoryForItem(itemId)

    // ============================================================
    // НАСТРОЙКИ
    // ============================================================

    suspend fun getSettings(): SettingsEntity {
        return db.settingsDao().getSettings() ?: SettingsEntity()
    }

    suspend fun updateSettings(settings: SettingsEntity) {
        db.settingsDao().insertOrUpdateSettings(settings)
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================================

    private fun getAccessToken(): String? {
        return try {
            val context = BaseApplication.getAppContext()
            val tokenStorage = TokenStorage(context)
            tokenStorage.getAccessToken()
        } catch (e: Exception) {
            Logger.log(TAG, "getAccessToken error: ${e.message}")
            null
        }
    }

    private fun getAuthHeader(): String? {
        val token = getAccessToken()
        return if (token != null) "OAuth $token" else null
    }

    private fun isDnsError(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is UnknownHostException) return true
            if (cause.message?.contains("No address associated with hostname") == true) return true
            cause = cause.cause
        }
        return false
    }

    // ============================================================
    // ЗАПИСЬ НА ДИСК
    // ============================================================

    private suspend fun createFolderIfNotExists(folderPath: String) {
        if (isDnsBlocked()) {
            Logger.log(TAG, "createFolderIfNotExists: DNS blocked, skipping $folderPath")
            return
        }
        val auth = getAuthHeader()
        if (auth == null) {
            Logger.log(TAG, "No auth header, cannot create folder: $folderPath")
            return
        }
        val api = YandexDiskApi.getInstance()

        val rootPath = DiskPaths.ROOT
        try {
            val rootCheck = api.getDiskResources(auth, rootPath)
            if (rootCheck.code() == 404) {
                Logger.log(TAG, "Root folder $rootPath not found, creating...")
                val createRoot = api.createFolder(auth, rootPath)
                if (createRoot.isSuccessful) {
                    Logger.log(TAG, "Root folder $rootPath created")
                } else if (createRoot.code() == 409) {
                    // ok
                } else {
                    Logger.log(TAG, "Failed to create root folder $rootPath: ${createRoot.code()}")
                }
            }
        } catch (e: Exception) {
            if (isDnsError(e)) {
                noteDnsFailure()
                Logger.log(TAG, "DNS failure in createFolderIfNotExists (root): ${e.message}")
                return
            }
            Logger.log(TAG, "Error ensuring root folder: ${e.message}")
        }

        try {
            val checkResponse = api.getDiskResources(auth, folderPath)
            if (checkResponse.isSuccessful) {
                return
            } else if (checkResponse.code() == 404) {
                val createResponse = api.createFolder(auth, folderPath)
                if (!createResponse.isSuccessful) {
                    Logger.log(TAG, "Failed to create folder $folderPath: code=${createResponse.code()}")
                }
            }
        } catch (e: Exception) {
            if (isDnsError(e)) {
                noteDnsFailure()
                Logger.log(TAG, "DNS failure in createFolderIfNotExists: ${e.message}")
                return
            }
            Logger.log(TAG, "Error checking/creating folder $folderPath: ${e.message}")
        }
    }

    private suspend fun deleteFileOnDisk(path: String): Boolean {
        if (isDnsBlocked()) return false
        val auth = getAuthHeader()
        if (auth == null) {
            Logger.log(TAG, "No auth header, cannot delete file: $path")
            return false
        }
        val api = YandexDiskApi.getInstance()
        return try {
            val response = api.deleteFile(auth, path, false)
            response.isSuccessful || response.code() == 404
        } catch (e: Exception) {
            if (isDnsError(e)) {
                noteDnsFailure()
                Logger.log(TAG, "DNS failure in deleteFileOnDisk: ${e.message}")
                return false
            }
            Logger.log(TAG, "Error deleting file $path: ${e.message}")
            false
        }
    }

    private suspend fun uploadJsonWithToken(fileName: String, json: String, maxRetries: Int = 3): Boolean {
        if (isDnsBlocked()) {
            Logger.log(TAG, "uploadJsonWithToken: DNS blocked, skipping $fileName")
            return false
        }

        var attempts = 0
        while (attempts < maxRetries) {
            attempts++

            val auth = getAuthHeader()
            if (auth == null) {
                Logger.log(TAG, "No auth header, cannot upload: $fileName")
                return false
            }
            val api = YandexDiskApi.getInstance()
            val path = DiskPaths.dataFile(fileName)

            createFolderIfNotExists(DiskPaths.DATA)

            deleteFileOnDisk(path)
            delay(500)

            try {
                val body = json.toRequestBody("application/json".toMediaType())
                val urlResponse = api.getUploadUrl(auth, path, true)

                if (!urlResponse.isSuccessful) {
                    if (urlResponse.code() == 409 && attempts < maxRetries) {
                        delay(1000L * attempts)
                        continue
                    }
                    Logger.log(TAG, "Failed to get upload URL for $fileName: code=${urlResponse.code()}")
                    return false
                }

                val href = urlResponse.body()?.href
                if (href == null) {
                    Logger.log(TAG, "Href is null for: $path")
                    return false
                }

                val uploadResponse = api.uploadFileToUrl(href, body)
                if (uploadResponse.isSuccessful) {
                    Logger.log(TAG, "Uploaded successfully: $fileName")
                    return true
                } else {
                    if (uploadResponse.code() == 409 && attempts < maxRetries) {
                        delay(1000L * attempts)
                    } else {
                        Logger.log(TAG, "Upload failed permanently for $fileName: code=${uploadResponse.code()}")
                        return false
                    }
                }
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    Logger.log(TAG, "DNS failure in uploadJsonWithToken($fileName): ${e.message}")
                    return false
                }
                if (attempts < maxRetries) {
                    delay(1000L * attempts)
                } else {
                    Logger.log(TAG, "Error uploading $fileName: ${e.message}")
                    return false
                }
            }
        }
        Logger.log(TAG, "All $maxRetries attempts failed for $fileName")
        return false
    }

    private suspend fun updateLastModifiedWithToken(): Boolean {
        if (isDnsBlocked()) {
            Logger.log(TAG, "updateLastModifiedWithToken: DNS blocked")
            return false
        }
        val auth = getAuthHeader()
        if (auth == null) {
            Logger.log(TAG, "No auth header, cannot update last_modified")
            return false
        }
        val api = YandexDiskApi.getInstance()
        val path = DiskPaths.LAST_MODIFIED

        createFolderIfNotExists(DiskPaths.ROOT)

        return try {
            val timestamp = System.currentTimeMillis()
            val json = "{\"timestamp\": $timestamp}"
            val body = json.toRequestBody("application/json".toMediaType())

            val urlResponse = api.getUploadUrl(auth, path, true)
            if (!urlResponse.isSuccessful) {
                Logger.log(TAG, "Failed to get upload URL for last_modified: code=${urlResponse.code()}")
                return false
            }
            val href = urlResponse.body()?.href
            if (href == null) {
                Logger.log(TAG, "Href is null for last_modified")
                return false
            }
            val uploadResponse = api.uploadFileToUrl(href, body)
            if (uploadResponse.isSuccessful) {
                Logger.log(TAG, "Last_modified updated successfully: $timestamp")
                true
            } else {
                Logger.log(TAG, "Failed to update last_modified: code=${uploadResponse.code()}")
                false
            }
        } catch (e: Exception) {
            if (isDnsError(e)) {
                noteDnsFailure()
                Logger.log(TAG, "DNS failure in updateLastModifiedWithToken: ${e.message}")
                return false
            }
            Logger.log(TAG, "Error updating last_modified: ${e.message}")
            false
        }
    }

    // ============================================================
    // ПОЛЬЗОВАТЕЛИ
    // ============================================================

    suspend fun downloadUsersJson(): UsersFile? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext null
                val auth = getAuthHeader() ?: return@withContext null
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.USERS_JSON

                val urlResponse = api.getDiskDownloadUrl(auth, path)
                if (!urlResponse.isSuccessful) return@withContext null

                val href = urlResponse.body()?.href ?: return@withContext null
                val downloadResponse = api.downloadFile(href)
                if (!downloadResponse.isSuccessful) return@withContext null

                val json = downloadResponse.body()?.string()
                if (json.isNullOrEmpty()) return@withContext null

                val usersFile = gson.fromJson(json, UsersFile::class.java)
                Logger.log(TAG, "downloadUsersJson: loaded ${usersFile?.users?.size ?: 0} users")
                return@withContext usersFile
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    Logger.log(TAG, "DNS failure in downloadUsersJson: ${e.message}")
                    return@withContext null
                }
                Logger.log(TAG, "downloadUsersJson error: ${e.message}")
                null
            }
        }
    }

    suspend fun uploadUsersJson(usersFile: UsersFile): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(usersFile)
                val success = uploadJsonWithToken(Config.USERS_JSON_FILE.substringAfterLast('/'), json)
                if (success) {
                    updateLastModifiedWithToken()
                }
                return@withContext success
            } catch (e: Exception) {
                Logger.log(TAG, "uploadUsersJson error: ${e.message}")
                false
            }
        }
    }

    fun findUserByName(usersFile: UsersFile?, name: String): UserModel? {
        return usersFile?.users?.firstOrNull { it.name == name }
    }

    fun addOrUpdateUser(usersFile: UsersFile?, user: UserModel): UsersFile {
        val current = usersFile ?: UsersFile(emptyList())
        val idx = current.users.indexOfFirst { it.name == user.name }
        val updatedList = if (idx >= 0) {
            current.users.toMutableList().apply { this[idx] = user }
        } else {
            current.users + user
        }
        return UsersFile(updatedList)
    }

    // ============================================================
    // ФОТО ПАПОК
    // ============================================================

    suspend fun uploadFolderImage(folderId: String, imageBytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext false
                val auth = getAuthHeader() ?: return@withContext false
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.folderImage(folderId)

                createFolderIfNotExists(DiskPaths.IMAGES)

                val body = imageBytes.toRequestBody("image/jpeg".toMediaType())
                val urlResponse = api.getUploadUrl(auth, path, true)
                if (!urlResponse.isSuccessful) {
                    Logger.log(TAG, "Failed to get upload URL for folder image: code=${urlResponse.code()}")
                    return@withContext false
                }
                val href = urlResponse.body()?.href ?: return@withContext false
                val uploadResponse = api.uploadFileToUrl(href, body)
                if (uploadResponse.isSuccessful) {
                    return@withContext true
                } else {
                    Logger.log(TAG, "Folder image upload failed: code=${uploadResponse.code()}")
                    return@withContext false
                }
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    return@withContext false
                }
                Logger.log(TAG, "Error uploadFolderImage: ${e.message}")
                false
            }
        }
    }

    suspend fun downloadFolderImage(folderId: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext null
                val auth = getAuthHeader() ?: return@withContext null
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.folderImage(folderId)

                val urlResponse = api.getDiskDownloadUrl(auth, path)
                if (urlResponse.isSuccessful) {
                    val href = urlResponse.body()?.href
                    if (href != null) {
                        val downloadResponse = api.downloadFile(href)
                        if (downloadResponse.isSuccessful) {
                            val bytes = downloadResponse.body()?.bytes()
                            return@withContext bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    }
                }
                return@withContext null
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    return@withContext null
                }
                Logger.log(TAG, "Error downloadFolderImage: ${e.message}")
                null
            }
        }
    }

    // ============================================================
    // ЧТЕНИЕ С ДИСКА
    // ============================================================

    suspend fun getDiskLastModified(): Long? {
        if (isDnsBlocked()) {
            Logger.log(TAG, "getDiskLastModified: DNS blocked")
            return null
        }
        val auth = getAuthHeader()
        if (auth == null) {
            Logger.log(TAG, "getDiskLastModified: no auth header")
            return null
        }
        val api = YandexDiskApi.getInstance()
        val path = DiskPaths.LAST_MODIFIED

        val result = NetworkCallExecutor.runHttpSafely<Long>(
            label = "getDiskLastModified",
            treat404AsSuccess = true
        ) {
            val urlResponse = api.getDiskDownloadUrl(auth, path)
            val urlCode = urlResponse.code()

            if (urlCode == 404) {
                return@runHttpSafely NetworkCallExecutor.HttpOutcome(null, 404)
            }
            if (!urlResponse.isSuccessful) {
                return@runHttpSafely NetworkCallExecutor.HttpOutcome(null, urlCode)
            }

            val href = urlResponse.body()?.href
            if (href == null) {
                return@runHttpSafely NetworkCallExecutor.HttpOutcome(null, 500)
            }

            val downloadResponse = api.downloadFile(href)
            val dlCode = downloadResponse.code()
            if (!downloadResponse.isSuccessful) {
                return@runHttpSafely NetworkCallExecutor.HttpOutcome(null, dlCode)
            }

            val json = downloadResponse.body()?.string()
            if (json.isNullOrEmpty()) {
                return@runHttpSafely NetworkCallExecutor.HttpOutcome(0L, 200)
            }

            val jsonObject = gson.fromJson(json, Map::class.java)
            val raw = jsonObject["timestamp"]
            val timestamp: Long? = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
            if (timestamp != null && timestamp > 0L) {
                Logger.log(TAG, "Last_modified: $timestamp")
                NetworkCallExecutor.HttpOutcome(timestamp, 200)
            } else {
                Logger.log(TAG, "Last_modified parsed but empty/invalid: raw=$raw")
                NetworkCallExecutor.HttpOutcome(0L, 200)
            }
        }

        return result.fold(
            onSuccess = { value -> value ?: 0L },
            onFailure = { err ->
                when (err) {
                    is NetworkException -> {
                        if (err.kind == NetworkCallExecutor.Kind.DNS) {
                            noteDnsFailure()
                        }
                        Logger.log(TAG, "getDiskLastModified FAILED: kind=${err.kind} msg=${err.message}")
                    }
                    else -> Logger.log(TAG, "getDiskLastModified FAILED: ${err.message}")
                }
                null
            }
        )
    }

    data class DownloadResult(
        val folders: List<FolderEntity>,
        val items: List<ItemEntity>,
        val history: List<HistoryEntry>,
        val tasks: List<TaskEntity>,
        val foldersError: Boolean,
        val itemsError: Boolean,
        val historyError: Boolean,
        val tasksError: Boolean
    ) {
        val hasAnyError: Boolean get() = foldersError || itemsError || historyError || tasksError
    }

    suspend fun downloadDataFromDisk(): DownloadResult {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) {
                    Logger.log(TAG, "downloadDataFromDisk: DNS blocked")
                    return@withContext DownloadResult(
                        emptyList(), emptyList(), emptyList(), emptyList(),
                        true, true, true, true
                    )
                }
                val auth = getAuthHeader()
                if (auth == null) {
                    return@withContext DownloadResult(
                        emptyList(), emptyList(), emptyList(), emptyList(),
                        true, true, true, true
                    )
                }
                val api = YandexDiskApi.getInstance()

                val foldersResult = downloadJsonFileSafe<FolderEntity>(api, auth, DiskPaths.dataFile(FOLDERS_FILENAME))
                Logger.log(TAG, "Downloaded ${foldersResult.data.size} folders (error=${foldersResult.error})")

                val itemsResult = downloadJsonFileSafe<ItemEntity>(api, auth, DiskPaths.dataFile(ITEMS_FILENAME))
                Logger.log(TAG, "Downloaded ${itemsResult.data.size} items (error=${itemsResult.error})")

                val historyResult = downloadJsonFileSafe<HistoryEntry>(api, auth, DiskPaths.dataFile(HISTORY_FILENAME))
                Logger.log(TAG, "Downloaded ${historyResult.data.size} history entries (error=${historyResult.error})")

                val tasksResult = downloadJsonFileSafe<TaskEntity>(api, auth, DiskPaths.dataFile(TASKS_FILENAME))
                Logger.log(TAG, "Downloaded ${tasksResult.data.size} tasks (error=${tasksResult.error})")

                DownloadResult(
                    folders = foldersResult.data,
                    items = itemsResult.data,
                    history = historyResult.data,
                    tasks = tasksResult.data,
                    foldersError = foldersResult.error,
                    itemsError = itemsResult.error,
                    historyError = historyResult.error,
                    tasksError = tasksResult.error
                )
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    Logger.log(TAG, "DNS failure in downloadDataFromDisk: ${e.message}")
                    return@withContext DownloadResult(
                        emptyList(), emptyList(), emptyList(), emptyList(),
                        true, true, true, true
                    )
                }
                Logger.log(TAG, "Error downloadDataFromDisk: ${e.message}")
                DownloadResult(
                    emptyList(), emptyList(), emptyList(), emptyList(),
                    true, true, true, true
                )
            }
        }
    }

    private data class JsonDownloadResult<T>(
        val data: List<T>,
        val error: Boolean
    )

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> downloadJsonFileSafe(api: YandexDiskApi, auth: String, path: String): JsonDownloadResult<T> {
        return try {
            if (isDnsBlocked()) return JsonDownloadResult(emptyList(), true)

            val urlResponse = api.getDiskDownloadUrl(auth, path)
            if (!urlResponse.isSuccessful) {
                val isError = urlResponse.code() != 404
                return JsonDownloadResult(emptyList(), isError)
            }
            val href = urlResponse.body()?.href ?: return JsonDownloadResult(emptyList(), true)
            val downloadResponse = api.downloadFile(href)
            if (!downloadResponse.isSuccessful) return JsonDownloadResult(emptyList(), true)

            val json = downloadResponse.body()?.string()
            if (json.isNullOrEmpty()) return JsonDownloadResult(emptyList(), false)

            val type = when {
                path.contains("folders") -> object : TypeToken<List<FolderEntity>>() {}.type
                path.contains("history") -> object : TypeToken<List<HistoryEntry>>() {}.type
                path.contains("tasks") -> object : TypeToken<List<TaskEntity>>() {}.type
                else -> object : TypeToken<List<ItemEntity>>() {}.type
            }
            val parsed: List<T> = gson.fromJson(json, type) ?: emptyList()
            JsonDownloadResult(parsed, false)
        } catch (e: Exception) {
            if (isDnsError(e)) {
                noteDnsFailure()
                return JsonDownloadResult(emptyList(), true)
            }
            Logger.log(TAG, "Error downloading $path: ${e.message}")
            JsonDownloadResult(emptyList(), true)
        }
    }

    // ============================================================
    // ФОТО ПРЕДМЕТОВ
    // ============================================================

    suspend fun itemImageExistsOnDisk(itemId: String): Boolean? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext null
                val auth = getAuthHeader() ?: return@withContext null
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.itemImage(itemId)
                val response = api.getDiskDownloadUrl(auth, path)
                when (response.code()) {
                    200 -> true
                    404 -> false
                    else -> null
                }
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    return@withContext null
                }
                null
            }
        }
    }

    suspend fun folderImageExistsOnDisk(folderId: String): Boolean? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext null
                val auth = getAuthHeader() ?: return@withContext null
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.folderImage(folderId)
                val response = api.getDiskDownloadUrl(auth, path)
                when (response.code()) {
                    200 -> true
                    404 -> false
                    else -> null
                }
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    return@withContext null
                }
                null
            }
        }
    }

    suspend fun uploadItemImage(itemId: String, imageBytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext false
                val auth = getAuthHeader() ?: return@withContext false
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.itemImage(itemId)

                createFolderIfNotExists(DiskPaths.IMAGES)

                val body = imageBytes.toRequestBody("image/jpeg".toMediaType())
                val urlResponse = api.getUploadUrl(auth, path, true)
                if (!urlResponse.isSuccessful) {
                    Logger.log(TAG, "Failed to get upload URL for image: code=${urlResponse.code()}")
                    return@withContext false
                }
                val href = urlResponse.body()?.href ?: return@withContext false
                val uploadResponse = api.uploadFileToUrl(href, body)
                if (uploadResponse.isSuccessful) {
                    val item = db.itemDao().getItemById(itemId)
                    item?.let {
                        val updated = it.copy(imageUrl = "images/$itemId.jpg")
                        db.itemDao().updateItem(updated)
                    }
                    return@withContext true
                } else {
                    Logger.log(TAG, "Image upload failed: code=${uploadResponse.code()}")
                    return@withContext false
                }
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    return@withContext false
                }
                Logger.log(TAG, "Error uploadItemImage: ${e.message}")
                false
            }
        }
    }

    suspend fun downloadItemImage(itemId: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext null
                val auth = getAuthHeader() ?: return@withContext null
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.itemImage(itemId)

                val urlResponse = api.getDiskDownloadUrl(auth, path)
                if (urlResponse.isSuccessful) {
                    val href = urlResponse.body()?.href
                    if (href != null) {
                        val downloadResponse = api.downloadFile(href)
                        if (downloadResponse.isSuccessful) {
                            val bytes = downloadResponse.body()?.bytes()
                            return@withContext bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    }
                }
                return@withContext null
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    return@withContext null
                }
                Logger.log(TAG, "Error downloadItemImage: ${e.message}")
                null
            }
        }
    }

    suspend fun downloadItemImageBytes(itemId: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) {
                    Logger.log(TAG, "downloadItemImageBytes: DNS blocked, skip $itemId")
                    return@withContext null
                }
                val auth = getAuthHeader()
                if (auth == null) {
                    Logger.log(TAG, "downloadItemImageBytes: no auth, skip $itemId")
                    return@withContext null
                }
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.itemImage(itemId)

                val urlResponse = api.getDiskDownloadUrl(auth, path)
                if (!urlResponse.isSuccessful) {
                    Logger.log(TAG, "downloadItemImageBytes: no file on disk for $itemId (code=${urlResponse.code()})")
                    return@withContext null
                }
                val href = urlResponse.body()?.href
                if (href == null) {
                    Logger.log(TAG, "downloadItemImageBytes: href null for $itemId")
                    return@withContext null
                }
                val downloadResponse = api.downloadFile(href)
                if (!downloadResponse.isSuccessful) {
                    Logger.log(TAG, "downloadItemImageBytes: download failed for $itemId (code=${downloadResponse.code()})")
                    return@withContext null
                }
                val bytes = downloadResponse.body()?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    Logger.log(TAG, "downloadItemImageBytes: empty bytes for $itemId")
                    return@withContext null
                }
                Logger.log(TAG, "downloadItemImageBytes: OK $itemId (${bytes.size} bytes)")
                return@withContext bytes
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    Logger.log(TAG, "downloadItemImageBytes: DNS failure for $itemId: ${e.message}")
                    return@withContext null
                }
                Logger.log(TAG, "downloadItemImageBytes error for $itemId: ${e.message}")
                null
            }
        }
    }

    // ============================================================
    // ВОССТАНОВЛЕНИЕ (RESTORE)
    // ============================================================

    suspend fun downloadRestoreFile(fileName: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) {
                    Logger.log(TAG, "downloadRestoreFile: DNS blocked, skip $fileName")
                    return@withContext null
                }
                val auth = getAuthHeader()
                if (auth == null) {
                    Logger.log(TAG, "downloadRestoreFile: no auth, skip $fileName")
                    return@withContext null
                }
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.restoreFile(fileName)

                val urlResponse = api.getDiskDownloadUrl(auth, path)
                if (!urlResponse.isSuccessful) {
                    Logger.log(TAG, "downloadRestoreFile: no file on disk $fileName (code=${urlResponse.code()})")
                    return@withContext null
                }
                val href = urlResponse.body()?.href
                if (href == null) {
                    Logger.log(TAG, "downloadRestoreFile: href null for $fileName")
                    return@withContext null
                }
                val downloadResponse = api.downloadFile(href)
                if (!downloadResponse.isSuccessful) {
                    Logger.log(TAG, "downloadRestoreFile: download failed for $fileName (code=${downloadResponse.code()})")
                    return@withContext null
                }
                val text = downloadResponse.body()?.string()
                if (text.isNullOrEmpty()) {
                    Logger.log(TAG, "downloadRestoreFile: empty body for $fileName")
                    return@withContext null
                }
                Logger.log(TAG, "downloadRestoreFile: OK $fileName (${text.length} chars)")
                return@withContext text
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    Logger.log(TAG, "downloadRestoreFile: DNS failure for $fileName: ${e.message}")
                    return@withContext null
                }
                Logger.log(TAG, "downloadRestoreFile error for $fileName: ${e.message}")
                null
            }
        }
    }

    suspend fun uploadRestoreFile(fileName: String, json: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) {
                    Logger.log(TAG, "uploadRestoreFile: DNS blocked, skip $fileName")
                    return@withContext false
                }
                val auth = getAuthHeader()
                if (auth == null) {
                    Logger.log(TAG, "uploadRestoreFile: no auth, skip $fileName")
                    return@withContext false
                }
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.restoreFile(fileName)

                createFolderIfNotExists(DiskPaths.RESTORE)

                val body = json.toRequestBody("application/json".toMediaType())
                val urlResponse = api.getUploadUrl(auth, path, true)
                if (!urlResponse.isSuccessful) {
                    Logger.log(TAG, "uploadRestoreFile: failed to get upload URL for $fileName: code=${urlResponse.code()}")
                    return@withContext false
                }
                val href = urlResponse.body()?.href
                if (href == null) {
                    Logger.log(TAG, "uploadRestoreFile: href null for $fileName")
                    return@withContext false
                }
                val uploadResponse = api.uploadFileToUrl(href, body)
                if (uploadResponse.isSuccessful) {
                    Logger.log(TAG, "uploadRestoreFile: uploaded $path (${json.length} chars)")
                    return@withContext true
                } else {
                    Logger.log(TAG, "uploadRestoreFile: upload failed for $fileName: code=${uploadResponse.code()}")
                    return@withContext false
                }
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    Logger.log(TAG, "uploadRestoreFile: DNS failure for $fileName: ${e.message}")
                    return@withContext false
                }
                Logger.log(TAG, "uploadRestoreFile error for $fileName: ${e.message}")
                false
            }
        }
    }

    // ============================================================
    // ЛОГИ
    // ============================================================

    suspend fun uploadLogToDisk(fileName: String, bytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) {
                    Logger.log(TAG, "uploadLogToDisk: DNS blocked")
                    return@withContext false
                }
                val auth = getAuthHeader()
                if (auth == null) {
                    Logger.log(TAG, "uploadLogToDisk: no auth header")
                    return@withContext false
                }
                val api = YandexDiskApi.getInstance()
                val path = DiskPaths.logFile(fileName)

                createFolderIfNotExists(DiskPaths.LOGS)

                val body = bytes.toRequestBody("text/plain".toMediaType())
                val urlResponse = api.getUploadUrl(auth, path, true)
                if (!urlResponse.isSuccessful) {
                    Logger.log(TAG, "uploadLogToDisk: failed to get upload URL, code=${urlResponse.code()}")
                    return@withContext false
                }
                val href = urlResponse.body()?.href
                if (href == null) {
                    Logger.log(TAG, "uploadLogToDisk: href is null")
                    return@withContext false
                }
                val uploadResponse = api.uploadFileToUrl(href, body)
                if (uploadResponse.isSuccessful) {
                    Logger.log(TAG, "uploadLogToDisk: uploaded $path (${bytes.size} bytes)")
                    return@withContext true
                } else {
                    Logger.log(TAG, "uploadLogToDisk: upload failed, code=${uploadResponse.code()}")
                    return@withContext false
                }
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    Logger.log(TAG, "uploadLogToDisk: DNS failure: ${e.message}")
                    return@withContext false
                }
                Logger.log(TAG, "uploadLogToDisk error: ${e.message}")
                false
            }
        }
    }
}
