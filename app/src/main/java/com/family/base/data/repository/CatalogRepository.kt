package com.family.base.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.family.base.BaseApplication
import com.family.base.Config
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.*
import com.family.base.data.remote.YandexDiskApi
import com.family.base.data.remote.model.UserModel
import com.family.base.data.remote.model.UsersFile
import com.family.base.util.Logger
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
    private val DEFAULT_FOLDER_NAME = "BAZA"

    // 🆕 v13.0.3: убраны суффиксы .bak — возвращены стандартные имена файлов.
    // Раньше было "items.json.bak" / "folders.json.bak" для ускорения загрузки,
    // но это создавало рассинхрон между устройствами с разными сборками.
    private val ITEMS_FILENAME = "items.json"
    private val FOLDERS_FILENAME = "folders.json"

    private var folderPathCache: String? = null

    @Volatile
    private var dnsFailureUntil: Long = 0L

    private fun isDnsBlocked(): Boolean =
        System.currentTimeMillis() < dnsFailureUntil

    private fun noteDnsFailure() {
        dnsFailureUntil = System.currentTimeMillis() + 60_000L
    }

    fun isNetworkAvailable(): Boolean = !isDnsBlocked()

    private fun getFolderPath(): String {
        if (folderPathCache != null) return folderPathCache!!
        val folderName = try {
            val context = BaseApplication.getAppContext()
            val tokenStorage = TokenStorage(context)
            tokenStorage.getFolderName()
        } catch (e: Exception) {
            Logger.log(TAG, "Error getting folder name, using default: $DEFAULT_FOLDER_NAME")
            DEFAULT_FOLDER_NAME
        }
        folderPathCache = "/$folderName"
        return folderPathCache!!
    }

    private fun getRootPath(): String = getFolderPath()

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
        return db.folderDao().getAllFolders()
    }

    suspend fun createFolder(name: String, parentId: String?, parentPath: String?, creator: String): FolderEntity {
        val path = if (parentPath != null) "$parentPath/$name" else "${Config.SHARED_FOLDER_NAME}/$name"
        val folder = FolderEntity(name = name, parentId = parentId, createdBy = creator, path = path)
        db.folderDao().insertFolder(folder)
        return folder
    }

    /**
     * 🆕 B-5: создание папки внутри предмета.
     * @param name имя папки
     * @param parentItemId id предмета-родителя (parentItemId новой папки)
     * @param parentFolderId id папки, в которой лежит предмет-родитель (parentId новой папки)
     * @param creator создатель
     * @return созданная папка
     */
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

    suspend fun deleteFolder(folderId: String) {
        db.folderDao().deleteFolderById(folderId)
    }

    // ============================================================
    // ПРЕДМЕТЫ
    // ============================================================

    /**
     * 🆕 B-5: возвращает ТОЛЬКО «корневые» предметы папки.
     * Вложенные предметы (parentItemId != null) в каталоге папки не видны —
     * они отображаются в секции «📦 Вложенные» карточки родителя.
     */
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

    suspend fun deleteItem(itemId: String) {
        db.itemDao().getItemById(itemId)?.let { db.itemDao().deleteItem(it) }
    }

    // ============================================================
    // 🆕 B-3: ОТВЯЗАТЬ ВСЕХ ДЕТЕЙ (папки + предметы)
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

    // ============================================================
    // 🆕 B-5: ВЛОЖЕННЫЕ (для секции «📦 Вложенные»)
    // ============================================================

    /**
     * Возвращает прямых дочерних ПРЕДМЕТОВ (неархивных) указанного предмета.
     */
    suspend fun getNestedItems(parentItemId: String): List<ItemEntity> =
        db.itemDao().getItemsByParentItem(parentItemId)

    /**
     * Возвращает прямые дочерние ПАПКИ указанного предмета.
     */
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

    private fun getPublicKey(): String? {
        return try {
            val context = BaseApplication.getAppContext()
            val tokenStorage = TokenStorage(context)
            tokenStorage.getPublicKey()
        } catch (e: Exception) {
            Logger.log(TAG, "getPublicKey error: ${e.message}")
            null
        }
    }

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

    // ============================================================
    // ОБРАБОТКА DNS-СБОЕВ
    // ============================================================

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
    // ЗАПИСЬ НА ДИСК (ПРИВАТНЫЙ API)
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

        val rootPath = getRootPath()
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
            val rootPath = getRootPath()
            val path = "$rootPath/$fileName"

            val dataFolder = "$rootPath/data"
            createFolderIfNotExists(dataFolder)

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
        val rootPath = getRootPath()
        val path = "$rootPath/.last_modified"

        createFolderIfNotExists(rootPath)

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
    // ПОЛЬЗОВАТЕЛИ (users.json на Яндекс.Диске)
    // ============================================================

    suspend fun downloadUsersJson(): UsersFile? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext null
                val auth = getAuthHeader() ?: return@withContext null
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()
                val path = "$rootPath/${Config.USERS_JSON_FILE}"

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
                val success = uploadJsonWithToken(Config.USERS_JSON_FILE, json)
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
    // ОПЕРАЦИИ С ПАПКАМИ НА ДИСКЕ
    // ============================================================

    suspend fun uploadAllFoldersToDisk(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val allFolders = db.folderDao().getAllFolders()
                Logger.log(TAG, "uploadAllFoldersToDisk: uploading ${allFolders.size} folders")
                val json = gson.toJson(allFolders)
                val success = uploadJsonWithToken("data/$FOLDERS_FILENAME", json)
                if (!success) {
                    Logger.log(TAG, "uploadAllFoldersToDisk: failed (json upload)")
                    return@withContext false
                }
                val lm = updateLastModifiedWithToken()
                if (!lm) {
                    Logger.log(TAG, "uploadAllFoldersToDisk: json OK, but last_modified FAILED")
                    return@withContext false
                }
                Logger.log(TAG, "uploadAllFoldersToDisk: success")
                true
            } catch (e: Exception) {
                Logger.log(TAG, "uploadAllFoldersToDisk error: ${e.message}")
                false
            }
        }
    }

    suspend fun uploadAllItemsToDisk(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val allItems = db.itemDao().getAllItemsRaw()
                Logger.log(TAG, "uploadAllItemsToDisk: uploading ${allItems.size} items")
                val json = gson.toJson(allItems)
                val success = uploadJsonWithToken("data/$ITEMS_FILENAME", json)
                if (!success) {
                    Logger.log(TAG, "uploadAllItemsToDisk: failed (json upload)")
                    return@withContext false
                }
                val lm = updateLastModifiedWithToken()
                if (!lm) {
                    Logger.log(TAG, "uploadAllItemsToDisk: json OK, but last_modified FAILED")
                    return@withContext false
                }
                Logger.log(TAG, "uploadAllItemsToDisk: success")
                true
            } catch (e: Exception) {
                Logger.log(TAG, "uploadAllItemsToDisk error: ${e.message}")
                false
            }
        }
    }

    suspend fun createFolderOnDisk(folder: FolderEntity): Boolean =
        withContext(Dispatchers.IO) { uploadAllFoldersToDisk() }

    suspend fun updateFolderOnDisk(folder: FolderEntity): Boolean =
        withContext(Dispatchers.IO) { uploadAllFoldersToDisk() }

    suspend fun deleteFolderOnDisk(folderId: String): Boolean =
        withContext(Dispatchers.IO) { uploadAllFoldersToDisk() }

    // ============================================================
    // ОПЕРАЦИИ С ПРЕДМЕТАМИ НА ДИСКЕ
    // ============================================================

    suspend fun createItemOnDisk(item: ItemEntity): Boolean =
        withContext(Dispatchers.IO) { uploadAllItemsToDisk() }

    suspend fun updateItemOnDisk(item: ItemEntity): Boolean =
        withContext(Dispatchers.IO) { uploadAllItemsToDisk() }

    suspend fun deleteItemOnDisk(itemId: String): Boolean =
        withContext(Dispatchers.IO) { uploadAllItemsToDisk() }

    // ============================================================
    // ЗАГРУЗКА ФОТО ПАПКИ НА ДИСК
    // ============================================================

    suspend fun uploadFolderImage(folderId: String, imageBytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext false
                val auth = getAuthHeader() ?: return@withContext false
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()
                val path = "$rootPath/images/folder_$folderId.jpg"

                createFolderIfNotExists("$rootPath/images")

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

    // ============================================================
    // СКАЧИВАНИЕ ИКОНКИ ПАПКИ С ДИСКА
    // ============================================================
    suspend fun downloadFolderImage(folderId: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext null
                val auth = getAuthHeader() ?: return@withContext null
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()
                val path = "$rootPath/images/folder_$folderId.jpg"

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
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) {
                    Logger.log(TAG, "getDiskLastModified: DNS blocked")
                    return@withContext null
                }
                val auth = getAuthHeader()
                if (auth == null) {
                    Logger.log(TAG, "No auth header, cannot get last_modified")
                    return@withContext null
                }
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()
                val path = "$rootPath/.last_modified"

                val response = api.getDiskResources(auth, path)
                if (!response.isSuccessful) {
                    if (response.code() == 404) {
                        return@withContext 0L
                    }
                    return@withContext null
                }

                val urlResponse = api.getDiskDownloadUrl(auth, path)
                if (!urlResponse.isSuccessful) {
                    return@withContext null
                }
                val href = urlResponse.body()?.href ?: return@withContext null
                val downloadResponse = api.downloadFile(href)
                if (!downloadResponse.isSuccessful) {
                    return@withContext null
                }
                val json = downloadResponse.body()?.string()
                if (json.isNullOrEmpty()) {
                    return@withContext 0L
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
                    return@withContext timestamp
                }
                Logger.log(TAG, "Last_modified parsed but empty/invalid: raw=$raw")
                return@withContext 0L
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    Logger.log(TAG, "DNS failure in getDiskLastModified: ${e.message}")
                    return@withContext null
                }
                Logger.log(TAG, "Error getDiskLastModified: ${e.message}")
                null
            }
        }
    }

    data class DownloadResult(
        val folders: List<FolderEntity>,
        val items: List<ItemEntity>,
        val foldersError: Boolean,
        val itemsError: Boolean
    ) {
        val hasAnyError: Boolean get() = foldersError || itemsError
    }

    suspend fun downloadDataFromDisk(): DownloadResult {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) {
                    Logger.log(TAG, "downloadDataFromDisk: DNS blocked")
                    return@withContext DownloadResult(emptyList(), emptyList(), true, true)
                }
                val auth = getAuthHeader()
                if (auth == null) {
                    return@withContext DownloadResult(emptyList(), emptyList(), true, true)
                }
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()

                val foldersResult = downloadJsonFileSafe<FolderEntity>(api, auth, "$rootPath/data/$FOLDERS_FILENAME")
                Logger.log(TAG, "Downloaded ${foldersResult.data.size} folders (error=${foldersResult.error})")

                val itemsResult = downloadJsonFileSafe<ItemEntity>(api, auth, "$rootPath/data/$ITEMS_FILENAME")
                Logger.log(TAG, "Downloaded ${itemsResult.data.size} items (error=${itemsResult.error})")

                DownloadResult(
                    folders = foldersResult.data,
                    items = itemsResult.data,
                    foldersError = foldersResult.error,
                    itemsError = itemsResult.error
                )
            } catch (e: Exception) {
                if (isDnsError(e)) {
                    noteDnsFailure()
                    Logger.log(TAG, "DNS failure in downloadDataFromDisk: ${e.message}")
                    return@withContext DownloadResult(emptyList(), emptyList(), true, true)
                }
                Logger.log(TAG, "Error downloadDataFromDisk: ${e.message}")
                DownloadResult(emptyList(), emptyList(), true, true)
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

            val type = if (path.contains("folders")) {
                object : TypeToken<List<FolderEntity>>() {}.type
            } else {
                object : TypeToken<List<ItemEntity>>() {}.type
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
    // ПРОВЕРКА: ЕСТЬ ЛИ ФОТО ПРЕДМЕТА НА ДИСКЕ
    // ============================================================

    suspend fun itemImageExistsOnDisk(itemId: String): Boolean? {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext null
                val auth = getAuthHeader() ?: return@withContext null
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()
                val path = "$rootPath/images/$itemId.jpg"
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
                val rootPath = getRootPath()
                val path = "$rootPath/images/folder_$folderId.jpg"
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

    // ============================================================
    // ИЗОБРАЖЕНИЯ ПРЕДМЕТОВ
    // ============================================================

    suspend fun uploadItemImage(itemId: String, imageBytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isDnsBlocked()) return@withContext false
                val auth = getAuthHeader() ?: return@withContext false
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()
                val imagesPath = "$rootPath/images"
                val path = "$imagesPath/$itemId.jpg"

                createFolderIfNotExists(imagesPath)

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
                val rootPath = getRootPath()
                val path = "$rootPath/images/$itemId.jpg"

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

    // ============================================================
    // ЗАГРУЗКА ЛОГОВ НА ЯНДЕКС.ДИСК
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
                val rootPath = getRootPath()
                val logsFolder = "$rootPath/logs"
                val path = "$logsFolder/$fileName"

                createFolderIfNotExists(logsFolder)

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
