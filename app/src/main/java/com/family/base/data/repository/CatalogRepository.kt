package com.family.base.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.family.base.BaseApplication
import com.family.base.Config
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.*
import com.family.base.data.remote.YandexDiskApi
import com.family.base.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class CatalogRepository(private val db: AppDatabase) {

    private val gson = Gson()
    private val TAG = "CatalogRepository"
    private val DEFAULT_FOLDER_NAME = "BAZA"
    private var folderPathCache: String? = null

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

    suspend fun deleteFolder(folderId: String) {
        db.folderDao().deleteFolderById(folderId)
    }

    // ============================================================
    // ПРЕДМЕТЫ
    // ============================================================

    suspend fun getItems(parentId: String?): List<ItemEntity> =
        db.itemDao().getItemsByParent(parentId)

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
            val token = tokenStorage.getAccessToken()
            if (token != null) {
                Logger.log(TAG, "Token length: ${token.length}, first 20: ${token.take(20)}...")
            } else {
                Logger.log(TAG, "Access token is null")
            }
            token
        } catch (e: Exception) {
            Logger.log(TAG, "getAccessToken error: ${e.message}")
            null
        }
    }

    private fun getAuthHeader(): String? {
        val token = getAccessToken()
        val auth = if (token != null) "OAuth $token" else null
        if (auth != null) {
            Logger.log(TAG, "Authorization header (full): $auth")
        } else {
            Logger.log(TAG, "Authorization header is null")
        }
        return auth
    }

    // ============================================================
    // ЗАПИСЬ НА ДИСК (ПРИВАТНЫЙ API)
    // ============================================================

private suspend fun createFolderIfNotExists(folderPath: String) {
    val auth = getAuthHeader()
    if (auth == null) {
        Logger.log(TAG, "No auth header, cannot create folder: $folderPath")
        return
    }
    val api = YandexDiskApi.getInstance()
    
    // ===== ВАЖНО: сначала убеждаемся, что корневая папка существует =====
    val rootPath = getRootPath()
    try {
        val rootCheck = api.getDiskResources(auth, rootPath)
        if (rootCheck.code() == 404) {
            Logger.log(TAG, "Root folder $rootPath not found, creating...")
            val createRoot = api.createFolder(auth, rootPath)
            if (createRoot.isSuccessful) {
                Logger.log(TAG, "Root folder $rootPath created")
            } else if (createRoot.code() == 409) {
                Logger.log(TAG, "Root folder $rootPath already exists (conflict), proceeding")
            } else {
                val errorBody = createRoot.errorBody()?.string()
                Logger.log(TAG, "Failed to create root folder $rootPath: ${createRoot.code()}, $errorBody")
            }
        } else if (!rootCheck.isSuccessful && rootCheck.code() != 404) {
            val errorBody = rootCheck.errorBody()?.string()
            Logger.log(TAG, "Unexpected response checking root folder: ${rootCheck.code()}, $errorBody")
        }
    } catch (e: Exception) {
        Logger.log(TAG, "Error ensuring root folder: ${e.message}")
        e.printStackTrace()
    }
    
    // ===== Теперь создаём целевую папку =====
    Logger.log(TAG, "Checking folder existence: $folderPath")
    try {
        val checkResponse = api.getDiskResources(auth, folderPath)
        Logger.log(TAG, "Check folder response: code=${checkResponse.code()}")
        
        if (checkResponse.isSuccessful) {
            Logger.log(TAG, "Folder exists: $folderPath")
            return
        } else if (checkResponse.code() == 404) {
            Logger.log(TAG, "Folder not found, creating: $folderPath")
            val createResponse = api.createFolder(auth, folderPath)
            Logger.log(TAG, "Create folder response: code=${createResponse.code()}")
            if (createResponse.isSuccessful) {
                Logger.log(TAG, "Folder created: $folderPath")
            } else {
                val errorBody = createResponse.errorBody()?.string()
                Logger.log(TAG, "Failed to create folder $folderPath: code=${createResponse.code()}, body=$errorBody")
            }
        } else {
            val errorBody = checkResponse.errorBody()?.string()
            Logger.log(TAG, "Failed to check folder $folderPath: code=${checkResponse.code()}, body=$errorBody")
        }
    } catch (e: Exception) {
        Logger.log(TAG, "Error checking/creating folder $folderPath: ${e.message}")
        e.printStackTrace()
    }
}
    

    private suspend fun deleteFileOnDisk(path: String): Boolean {
        val auth = getAuthHeader()
        if (auth == null) {
            Logger.log(TAG, "No auth header, cannot delete file: $path")
            return false
        }
        val api = YandexDiskApi.getInstance()
        return try {
            val response = api.deleteFile(auth, path, false)
            Logger.log(TAG, "Delete file $path response: code=${response.code()}")
            response.isSuccessful || response.code() == 404
        } catch (e: Exception) {
            Logger.log(TAG, "Error deleting file $path: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun uploadJsonWithToken(fileName: String, json: String, maxRetries: Int = 3): Boolean {
        var attempts = 0
        while (attempts < maxRetries) {
            attempts++
            Logger.log(TAG, "Upload attempt $attempts for $fileName")
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
            // Небольшая задержка после удаления, чтобы сервер успел обработать
            delay(500)

            Logger.log(TAG, "Getting upload URL for: $path")
            try {
                val body = json.toRequestBody("application/json".toMediaType())
                val urlResponse = api.getUploadUrl(auth, path, true)
                Logger.log(TAG, "Get upload URL response: code=${urlResponse.code()}")
                
                if (!urlResponse.isSuccessful) {
                    val errorBody = urlResponse.errorBody()?.string()
                    Logger.log(TAG, "Failed to get upload URL: code=${urlResponse.code()}, body=$errorBody")
                    if (urlResponse.code() == 409 && attempts < maxRetries) {
                        Logger.log(TAG, "Conflict (409) on getUploadUrl, retrying...")
                        delay(1000L * attempts)
                        continue
                    } else {
                        return false
                    }
                }

                val href = urlResponse.body()?.href
                if (href == null) {
                    Logger.log(TAG, "Href is null for: $path")
                    return false
                }

                Logger.log(TAG, "Uploading to URL: $href")
                val uploadResponse = api.uploadFileToUrl(href, body)
                Logger.log(TAG, "Upload response: code=${uploadResponse.code()}")
                if (uploadResponse.isSuccessful) {
                    Logger.log(TAG, "Uploaded successfully: $fileName")
                    return true
                } else {
                    val errorBody = uploadResponse.errorBody()?.string()
                    Logger.log(TAG, "Upload failed: code=${uploadResponse.code()}, body=$errorBody")
                    if (uploadResponse.code() == 409 && attempts < maxRetries) {
                        Logger.log(TAG, "Conflict (409), retrying after delay...")
                        delay(1000L * attempts)
                    } else {
                        Logger.log(TAG, "Upload failed permanently, giving up.")
                        return false
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error uploading $fileName: ${e.message}")
                e.printStackTrace()
                if (attempts < maxRetries) {
                    delay(1000L * attempts)
                } else {
                    return false
                }
            }
        }
        Logger.log(TAG, "All $maxRetries attempts failed for $fileName")
        return false
    }

    private suspend fun updateLastModifiedWithToken(): Boolean {
        val auth = getAuthHeader()
        if (auth == null) {
            Logger.log(TAG, "No auth header, cannot update last_modified")
            return false
        }
        val api = YandexDiskApi.getInstance()
        val rootPath = getRootPath()
        val path = "$rootPath/.last_modified"

        createFolderIfNotExists(rootPath)
        deleteFileOnDisk(path)
        delay(500)

        Logger.log(TAG, "Getting upload URL for last_modified: $path")
        return try {
            val timestamp = System.currentTimeMillis()
            val json = "{\"timestamp\": $timestamp}"
            val body = json.toRequestBody("application/json".toMediaType())
            val urlResponse = api.getUploadUrl(auth, path, true)
            Logger.log(TAG, "Get upload URL response (last_modified): code=${urlResponse.code()}")
            
            if (!urlResponse.isSuccessful) {
                val errorBody = urlResponse.errorBody()?.string()
                Logger.log(TAG, "Failed to get upload URL for last_modified: code=${urlResponse.code()}, body=$errorBody")
                false
            } else {
                val href = urlResponse.body()?.href
                if (href == null) {
                    Logger.log(TAG, "Href is null for last_modified")
                    false
                } else {
                    Logger.log(TAG, "Uploading last_modified to: $href")
                    val uploadResponse = api.uploadFileToUrl(href, body)
                    Logger.log(TAG, "Upload last_modified response: code=${uploadResponse.code()}")
                    if (uploadResponse.isSuccessful) {
                        Logger.log(TAG, "Last_modified updated successfully")
                        true
                    } else {
                        val errorBody = uploadResponse.errorBody()?.string()
                        Logger.log(TAG, "Failed to update last_modified: code=${uploadResponse.code()}, body=$errorBody")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error updating last_modified: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // ============================================================
    // ОПЕРАЦИИ С ПАПКАМИ НА ДИСКЕ
    // ============================================================

    suspend fun createFolderOnDisk(folder: FolderEntity): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Logger.log(TAG, "Creating folder on disk: id=${folder.id}, name=${folder.name}")
                val (existingFolders, _) = downloadDataFromDisk()
                val updatedList = existingFolders.toMutableList().apply { add(folder) }
                val json = gson.toJson(updatedList)
                val success = uploadJsonWithToken("data/folders.json", json)
                if (success) {
                    updateLastModifiedWithToken()
                    Logger.log(TAG, "Folder uploaded to disk: ${folder.id}")
                } else {
                    Logger.log(TAG, "Failed to upload folder to disk: ${folder.id}")
                }
                return@withContext success
            } catch (e: Exception) {
                Logger.log(TAG, "Error createFolderOnDisk: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    suspend fun updateFolderOnDisk(folder: FolderEntity): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Logger.log(TAG, "Updating folder on disk: id=${folder.id}")
                val (existingFolders, _) = downloadDataFromDisk()
                val idx = existingFolders.indexOfFirst { it.id == folder.id }
                if (idx != -1) {
                    val updatedList = existingFolders.toMutableList()
                    updatedList[idx] = folder
                    val json = gson.toJson(updatedList)
                    val success = uploadJsonWithToken("data/folders.json", json)
                    if (success) {
                        updateLastModifiedWithToken()
                        Logger.log(TAG, "Folder updated on disk: ${folder.id}")
                    } else {
                        Logger.log(TAG, "Failed to update folder on disk: ${folder.id}")
                    }
                    return@withContext success
                } else {
                    Logger.log(TAG, "Folder not found on disk: ${folder.id}")
                    return@withContext false
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error updateFolderOnDisk: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    suspend fun deleteFolderOnDisk(folderId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Logger.log(TAG, "Deleting folder on disk: $folderId")
                val (existingFolders, _) = downloadDataFromDisk()
                val updatedList = existingFolders.filter { it.id != folderId }
                val json = gson.toJson(updatedList)
                val success = uploadJsonWithToken("data/folders.json", json)
                if (success) {
                    updateLastModifiedWithToken()
                    Logger.log(TAG, "Folder deleted from disk: $folderId")
                } else {
                    Logger.log(TAG, "Failed to delete folder from disk: $folderId")
                }
                return@withContext success
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleteFolderOnDisk: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    // ============================================================
    // ОПЕРАЦИИ С ПРЕДМЕТАМИ НА ДИСКЕ
    // ============================================================

    suspend fun createItemOnDisk(item: ItemEntity): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Logger.log(TAG, "Creating item on disk: id=${item.id}, name=${item.name}")
                val (_, existingItems) = downloadDataFromDisk()
                val updatedList = existingItems.toMutableList().apply { add(item) }
                val json = gson.toJson(updatedList)
                val success = uploadJsonWithToken("data/items.json", json)
                if (success) {
                    updateLastModifiedWithToken()
                    Logger.log(TAG, "Item uploaded to disk: ${item.id}")
                } else {
                    Logger.log(TAG, "Failed to upload item to disk: ${item.id}")
                }
                return@withContext success
            } catch (e: Exception) {
                Logger.log(TAG, "Error createItemOnDisk: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    suspend fun updateItemOnDisk(item: ItemEntity): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Logger.log(TAG, "Updating item on disk: id=${item.id}")
                val (_, existingItems) = downloadDataFromDisk()
                val idx = existingItems.indexOfFirst { it.id == item.id }
                if (idx != -1) {
                    val updatedList = existingItems.toMutableList()
                    updatedList[idx] = item
                    val json = gson.toJson(updatedList)
                    val success = uploadJsonWithToken("data/items.json", json)
                    if (success) {
                        updateLastModifiedWithToken()
                        Logger.log(TAG, "Item updated on disk: ${item.id}")
                    } else {
                        Logger.log(TAG, "Failed to update item on disk: ${item.id}")
                    }
                    return@withContext success
                } else {
                    Logger.log(TAG, "Item not found on disk: ${item.id}")
                    return@withContext false
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error updateItemOnDisk: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    suspend fun deleteItemOnDisk(itemId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Logger.log(TAG, "Deleting item on disk: $itemId")
                val (_, existingItems) = downloadDataFromDisk()
                val updatedList = existingItems.filter { it.id != itemId }
                val json = gson.toJson(updatedList)
                val success = uploadJsonWithToken("data/items.json", json)
                if (success) {
                    updateLastModifiedWithToken()
                    Logger.log(TAG, "Item deleted from disk: $itemId")
                } else {
                    Logger.log(TAG, "Failed to delete item from disk: $itemId")
                }
                return@withContext success
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleteItemOnDisk: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    // ============================================================
    // ЗАГРУЗКА ФОТО ПАПКИ НА ДИСК (НОВЫЙ МЕТОД)
    // ============================================================

    suspend fun uploadFolderImage(folderId: String, imageBytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val auth = getAuthHeader()
                if (auth == null) {
                    Logger.log(TAG, "No auth header, cannot upload folder image")
                    return@withContext false
                }
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()
                val path = "$rootPath/images/folder_$folderId.jpg"

                createFolderIfNotExists("$rootPath/images")
                deleteFileOnDisk(path)
                delay(500)

                Logger.log(TAG, "Getting upload URL for folder image: $path")
                val body = imageBytes.toRequestBody("image/jpeg".toMediaType())
                val urlResponse = api.getUploadUrl(auth, path, true)
                if (!urlResponse.isSuccessful) {
                    val errorBody = urlResponse.errorBody()?.string()
                    Logger.log(TAG, "Failed to get upload URL for folder image: code=${urlResponse.code()}, body=$errorBody")
                    return@withContext false
                }

                val href = urlResponse.body()?.href
                if (href == null) {
                    Logger.log(TAG, "Href is null for folder image")
                    return@withContext false
                }

                Logger.log(TAG, "Uploading folder image to: $href")
                val uploadResponse = api.uploadFileToUrl(href, body)
                if (uploadResponse.isSuccessful) {
                    Logger.log(TAG, "Folder image uploaded: $path")
                    return@withContext true
                } else {
                    val errorBody = uploadResponse.errorBody()?.string()
                    Logger.log(TAG, "Folder image upload failed: code=${uploadResponse.code()}, body=$errorBody")
                    return@withContext false
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error uploadFolderImage: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    // ============================================================
    // ЧТЕНИЕ С ДИСКА (ПРИВАТНЫЙ API)
    // ============================================================

    suspend fun getDiskLastModified(): Long {
        return withContext(Dispatchers.IO) {
            try {
                val auth = getAuthHeader()
                if (auth == null) {
                    Logger.log(TAG, "No auth header, cannot get last_modified")
                    return@withContext 0L
                }
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()
                val path = "$rootPath/.last_modified"
                
                Logger.log(TAG, "Checking last_modified file: $path")
                val response = api.getDiskResources(auth, path)
                Logger.log(TAG, "Check last_modified response: code=${response.code()}")
                
                if (response.isSuccessful) {
                    Logger.log(TAG, "Last_modified file exists, getting download URL")
                    val urlResponse = api.getDiskDownloadUrl(auth, path)
                    Logger.log(TAG, "Get download URL response (last_modified): code=${urlResponse.code()}")
                    
                    if (urlResponse.isSuccessful) {
                        val href = urlResponse.body()?.href
                        if (href != null) {
                            val downloadResponse = api.downloadFile(href)
                            Logger.log(TAG, "Download last_modified response: code=${downloadResponse.code()}")
                            if (downloadResponse.isSuccessful) {
                                val json = downloadResponse.body()?.string()
                                if (!json.isNullOrEmpty()) {
                                    try {
                                        val jsonObject = gson.fromJson(json, Map::class.java)
                                        val timestamp = jsonObject["timestamp"]?.toString()?.toLongOrNull()
                                        if (timestamp != null) {
                                            Logger.log(TAG, "Last_modified: $timestamp")
                                            return@withContext timestamp
                                        }
                                    } catch (e: Exception) {
                                        Logger.log(TAG, "Error parsing last_modified json: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Logger.log(TAG, "Last_modified not found (code ${response.code()})")
                }
                
                Logger.log(TAG, "Last_modified not found, returning 0")
                return@withContext 0L
            } catch (e: Exception) {
                Logger.log(TAG, "Error getDiskLastModified: ${e.message}")
                e.printStackTrace()
                return@withContext 0L
            }
        }
    }

    suspend fun downloadDataFromDisk(): Pair<List<FolderEntity>, List<ItemEntity>> {
        return withContext(Dispatchers.IO) {
            try {
                val auth = getAuthHeader()
                if (auth == null) {
                    Logger.log(TAG, "No auth header, cannot download data")
                    return@withContext Pair(emptyList(), emptyList())
                }
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()

                Logger.log(TAG, "Downloading data from disk...")
                
                val folders = downloadJsonFile<FolderEntity>(api, auth, "$rootPath/data/folders.json")
                Logger.log(TAG, "Downloaded ${folders.size} folders")
                
                val items = downloadJsonFile<ItemEntity>(api, auth, "$rootPath/data/items.json")
                Logger.log(TAG, "Downloaded ${items.size} items")
                
                Pair(folders, items)
            } catch (e: Exception) {
                Logger.log(TAG, "Error downloadDataFromDisk: ${e.message}")
                e.printStackTrace()
                Pair(emptyList(), emptyList())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> downloadJsonFile(api: YandexDiskApi, auth: String, path: String): List<T> {
        return try {
            Logger.log(TAG, "Getting download URL for: $path")
            val urlResponse = api.getDiskDownloadUrl(auth, path)
            Logger.log(TAG, "Get download URL response: code=${urlResponse.code()}")
            
            if (urlResponse.isSuccessful) {
                val href = urlResponse.body()?.href
                if (href != null) {
                    Logger.log(TAG, "Downloading from: $href")
                    val downloadResponse = api.downloadFile(href)
                    Logger.log(TAG, "Download response: code=${downloadResponse.code()}")
                    if (downloadResponse.isSuccessful) {
                        val json = downloadResponse.body()?.string()
                        if (!json.isNullOrEmpty()) {
                            val type = if (path.contains("folders")) {
                                object : TypeToken<List<FolderEntity>>() {}.type
                            } else {
                                object : TypeToken<List<ItemEntity>>() {}.type
                            }
                            return gson.fromJson(json, type)
                        }
                    }
                }
            }
            emptyList()
        } catch (e: Exception) {
            Logger.log(TAG, "Error downloading $path: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================================================
    // ИЗОБРАЖЕНИЯ ПРЕДМЕТОВ
    // ============================================================

suspend fun uploadItemImage(itemId: String, imageBytes: ByteArray): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val auth = getAuthHeader()
            if (auth == null) {
                Logger.log(TAG, "No auth header, cannot upload image")
                return@withContext false
            }
            val api = YandexDiskApi.getInstance()
            val rootPath = getRootPath() // "/BAZA"
            val imagesPath = "$rootPath/images"
            val path = "$imagesPath/$itemId.jpg"

            // Создаём папку images, если её нет (без создания корня)
            createFolderIfNotExists(imagesPath)

            deleteFileOnDisk(path)
            delay(500)

            Logger.log(TAG, "Getting upload URL for image: $path")
            val body = imageBytes.toRequestBody("image/jpeg".toMediaType())
            val urlResponse = api.getUploadUrl(auth, path, true)
            if (!urlResponse.isSuccessful) {
                val errorBody = urlResponse.errorBody()?.string()
                Logger.log(TAG, "Failed to get upload URL for image: code=${urlResponse.code()}, body=$errorBody")
                return@withContext false
            }

            val href = urlResponse.body()?.href ?: return@withContext false
            val uploadResponse = api.uploadFileToUrl(href, body)
            if (uploadResponse.isSuccessful) {
                Logger.log(TAG, "Image uploaded: $path")
                val item = db.itemDao().getItemById(itemId)
                item?.let {
                    val updated = it.copy(imageUrl = "images/$itemId.jpg")
                    db.itemDao().updateItem(updated)
                    updateItemOnDisk(updated)
                }
                return@withContext true
            } else {
                val errorBody = uploadResponse.errorBody()?.string()
                Logger.log(TAG, "Image upload failed: code=${uploadResponse.code()}, body=$errorBody")
                return@withContext false
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error uploadItemImage: ${e.message}")
            e.printStackTrace()
            return@withContext false
        }
    }
}

    suspend fun downloadItemImage(itemId: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val auth = getAuthHeader()
                if (auth == null) {
                    Logger.log(TAG, "No auth header, cannot download image")
                    return@withContext null
                }
                val api = YandexDiskApi.getInstance()
                val rootPath = getRootPath()
                val path = "$rootPath/images/$itemId.jpg"

                Logger.log(TAG, "Getting download URL for image: $path")
                val urlResponse = api.getDiskDownloadUrl(auth, path)
                Logger.log(TAG, "Get download URL response (image): code=${urlResponse.code()}")
                
                if (urlResponse.isSuccessful) {
                    val href = urlResponse.body()?.href
                    if (href != null) {
                        Logger.log(TAG, "Downloading image from: $href")
                        val downloadResponse = api.downloadFile(href)
                        Logger.log(TAG, "Download image response: code=${downloadResponse.code()}")
                        if (downloadResponse.isSuccessful) {
                            val bytes = downloadResponse.body()?.bytes()
                            Logger.log(TAG, "Image downloaded: $itemId.jpg")
                            return@withContext bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    }
                }
                Logger.log(TAG, "Image not found: $itemId.jpg")
                return@withContext null
            } catch (e: Exception) {
                Logger.log(TAG, "Error downloadItemImage: ${e.message}")
                e.printStackTrace()
                return@withContext null
            }
        }
    }




    
}
