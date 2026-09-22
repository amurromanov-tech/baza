package com.family.base.ui.viewmodel

import com.family.base.util.ImageUtils
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.*
import com.family.base.data.repository.CatalogRepository
import com.family.base.ui.SyncStatus
import com.family.base.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.LiveData

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _searchQuery = MutableLiveData<String?>(null)
    val searchQueryLiveData: LiveData<String?> = _searchQuery

    private val db = AppDatabase.getInstance(application)
    private val repository = CatalogRepository(db)
    private val tokenStorage = TokenStorage(application)
    private val lockDao = db.lockDao()
    private val syncQueueDao = db.syncQueueDao()
    private val syncInfoDao = db.syncInfoDao()
    private val settingsDao = db.settingsDao()

    val currentEntries = MutableLiveData<List<Any>>()
    val currentPath = MutableLiveData<String>()
    val syncStatus = MutableLiveData<SyncStatus>(SyncStatus.SYNCED)

    private var currentFolderId: String? = null
    private val currentUser: String
        get() = tokenStorage.getUserEmail() ?: "unknown_user"
    private val currentUserDisplayName: String
        get() = tokenStorage.getUserDisplayName() ?: "User"

    private val TAG = "MainViewModel"
    private var syncRetryJob: Job? = null

    private var allItems: List<ItemEntity> = emptyList()
    private var searchQuery: String? = null

    // ===== ГЛОБАЛЬНЫЙ СКОУП ДЛЯ СИНХРОНИЗАЦИИ =====
    private val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Logger.log(TAG, "MainViewModel initialized")
        viewModelScope.launch {
            checkFirstLaunch()
            // Автоматическая синхронизация ОТКЛЮЧЕНА
            // syncWithDisk() вызывается только из MainActivity (onResume/onPause) и по кнопке
        }
    }

    fun getCurrentFolderId(): String? = currentFolderId

    // ============================================================
    // ПРОВЕРКА ПЕРВОГО ЗАПУСКА
    // ============================================================

    private suspend fun checkFirstLaunch() {
        try {
            val settings = settingsDao.getSettings()
            if (settings == null || settings.isFirstLaunch) {
                Logger.log(TAG, "First launch detected - initializing")
                settingsDao.insertOrUpdateSettings(
                    SettingsEntity(
                        id = 1,
                        isFirstLaunch = false
                    )
                )
                Logger.log(TAG, "First launch flag saved")
            } else {
                Logger.log(TAG, "Not first launch")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error checking first launch: ${e.message}")
            e.printStackTrace()
        }
    }

    // ============================================================
    // НАВИГАЦИЯ
    // ============================================================

    fun navigateToFolder(folderId: String?) {
        Logger.log(TAG, "navigateToFolder: folderId=$folderId")
        currentFolderId = folderId
        loadContents()
        viewModelScope.launch { updateCurrentPath() }
    }

    fun navigateToRoot() {
        Logger.log(TAG, "navigateToRoot called")
        currentFolderId = null
        loadContents()
        viewModelScope.launch { updateCurrentPath() }
    }

    fun navigateUp() {
        Logger.log(TAG, "navigateUp called, currentFolderId=$currentFolderId")
        viewModelScope.launch {
            try {
                currentFolderId?.let { id ->
                    val folder = withContext(Dispatchers.IO) { db.folderDao().getFolderById(id) }
                    currentFolderId = folder?.parentId
                    loadContents()
                    viewModelScope.launch { updateCurrentPath() }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error in navigateUp: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // ============================================================
    // ЗАГРУЗКА ДАННЫХ
    // ============================================================

    fun loadContents() {
        Logger.log(TAG, "loadContents START, currentFolderId=$currentFolderId")
        val start = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                val entries = mutableListOf<Any>()
                withContext(Dispatchers.IO) {
                    val folders = repository.getFolders(currentFolderId)
                    val allItemsFromDb = db.itemDao().getAllItems()
                    allItems = allItemsFromDb

                    val items = if (searchQuery.isNullOrEmpty()) {
                        repository.getItems(currentFolderId).sortedBy { item ->
                            when {
                                item.isExpired -> 0
                                item.daysUntilExpiry in 0..3 -> 1
                                else -> 2
                            }
                        }
                    } else {
                        allItemsFromDb.filter { it.name.contains(searchQuery!!, ignoreCase = true) }
                            .sortedBy { it.name }
                    }
                    entries.addAll(folders)
                    entries.addAll(items)
                }
                currentEntries.postValue(entries)
                Logger.log(TAG, "loadContents END, took ${System.currentTimeMillis() - start} ms")
            } catch (e: Exception) {
                Logger.log(TAG, "Error in loadContents: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private suspend fun updateCurrentPath() {
        try {
            val pathParts = mutableListOf<String>()
            var id = currentFolderId
            while (id != null) {
                val folder = db.folderDao().getFolderById(id)
                if (folder != null) {
                    pathParts.add(folder.name)
                    id = folder.parentId
                } else break
            }
            val path = if (pathParts.isEmpty()) "/" else pathParts.reversed().joinToString("/")
            Logger.log(TAG, "Updated path: $path")
            currentPath.postValue(path)
        } catch (e: Exception) {
            Logger.log(TAG, "Error in updateCurrentPath: ${e.message}")
            e.printStackTrace()
        }
    }

    // ============================================================
    // СИНХРОНИЗАЦИЯ (только по вызову извне)
    // ============================================================

    fun syncWithDisk() {
        Logger.log(TAG, "syncWithDisk called")
        viewModelScope.launch {
            try {
                if (!ensureValidToken()) {
                    Logger.log(TAG, "Token validation failed, cannot sync")
                    syncStatus.postValue(SyncStatus.OFFLINE)
                    return@launch
                }
                if (!isInternetAvailable()) {
                    Logger.log(TAG, "No internet, setting OFFLINE status")
                    syncStatus.postValue(SyncStatus.OFFLINE)
                    checkPendingChanges()
                    return@launch
                }

                syncStatus.postValue(SyncStatus.SYNCING)
                Logger.log(TAG, "Internet available, starting sync")

                val (diskFolders, diskItems) = repository.downloadDataFromDisk()
                Logger.log(TAG, "Downloaded from disk: ${diskFolders.size} folders, ${diskItems.size} items")

                mergeData(diskFolders, diskItems)
                syncImages(diskItems)

                val pendingCount = syncQueueDao.getPendingCount()
                if (pendingCount > 0) {
                    Logger.log(TAG, "Has $pendingCount pending changes")
                    syncStatus.postValue(SyncStatus.PENDING)
                    processPendingChanges()
                    // Периодическая синхронизация ОТКЛЮЧЕНА
                } else {
                    syncStatus.postValue(SyncStatus.SYNCED)
                }

                loadContents()
                Logger.log(TAG, "syncWithDisk finished")
            } catch (e: Exception) {
                Logger.log(TAG, "Error in syncWithDisk: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private suspend fun syncImages(items: List<ItemEntity>) {
        val appContext = getApplication<Application>().applicationContext
        val itemsWithImages = items.filter { !it.imageUrl.isNullOrEmpty() }
        if (itemsWithImages.isEmpty()) {
            Logger.log(TAG, "No images to sync")
            return
        }

        Logger.log(TAG, "Syncing ${itemsWithImages.size} images...")
        var downloadedCount = 0

        itemsWithImages.forEach { item ->
            val imageId = item.id
            val localFile = ImageUtils.getLocalImageFile(appContext, imageId)
            if (localFile != null && localFile.exists()) {
                return@forEach
            }

            try {
                val bitmap = repository.downloadItemImage(imageId)
                if (bitmap != null) {
                    val bytes = ImageUtils.bitmapToJpegBytes(bitmap, 85)
                    ImageUtils.saveImageLocally(appContext, imageId, bytes)
                    downloadedCount++
                    Logger.log(TAG, "Downloaded image: $imageId")
                } else {
                    Logger.log(TAG, "Image not found on disk: $imageId")
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to download image $imageId: ${e.message}")
            }
        }

        Logger.log(TAG, "Images synced: $downloadedCount new images")
        loadContents()
    }

    private suspend fun ensureValidToken(): Boolean {
        val token = tokenStorage.getAccessToken()
        if (token == null) {
            Logger.log(TAG, "No token available")
            return false
        }

        Logger.log(TAG, "Checking token validity...")
        val auth = "OAuth $token"
        val api = com.family.base.data.remote.YandexDiskApi.getInstance()
        return try {
            val response = api.getDiskResources(auth, "/BAZA")
            when (response.code()) {
                200 -> {
                    Logger.log(TAG, "Token is valid")
                    true
                }
                401, 403 -> {
                    Logger.log(TAG, "Token expired or invalid, refreshing...")
                    val newToken = tokenStorage.refreshAccessToken()
                    if (newToken != null) {
                        Logger.log(TAG, "Token refreshed successfully")
                        true
                    } else {
                        Logger.log(TAG, "Token refresh failed")
                        false
                    }
                }
                else -> {
                    Logger.log(TAG, "Unexpected response: ${response.code()}")
                    true
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error checking token: ${e.message}")
            false
        }
    }

    private suspend fun mergeData(diskFolders: List<FolderEntity>, diskItems: List<ItemEntity>) {
        withContext(Dispatchers.IO) {
            diskFolders.forEach { diskFolder ->
                val local = db.folderDao().getFolderById(diskFolder.id)
                if (local == null) {
                    db.folderDao().insertFolder(diskFolder)
                    Logger.log(TAG, "Added new folder: ${diskFolder.name}")
                } else if (diskFolder.updatedAt > local.updatedAt) {
                    db.folderDao().updateFolder(diskFolder)
                    Logger.log(TAG, "Updated folder: ${diskFolder.name}")
                }
            }

            diskItems.forEach { diskItem ->
                val local = db.itemDao().getItemById(diskItem.id)
                if (local == null) {
                    db.itemDao().insertItem(diskItem)
                    Logger.log(TAG, "Added new item: ${diskItem.name}")
                } else if (diskItem.updatedDate > local.updatedDate) {
                    db.itemDao().updateItem(diskItem)
                    Logger.log(TAG, "Updated item: ${diskItem.name}")
                }
            }

            val diskLastModified = repository.getDiskLastModified()
            val localLastModified = syncInfoDao.getLastModified()
            Logger.log(TAG, "Disk lastModified: $diskLastModified, Local: $localLastModified")
            if (diskLastModified > localLastModified) {
                syncInfoDao.setLastModified(diskLastModified)
            }
        }
    }

    private fun startPeriodicSync() {
        // ОТКЛЮЧЕНО: периодическая синхронизация
        Logger.log(TAG, "Periodic sync disabled")
    }

    private fun stopPeriodicSync() {
        syncRetryJob?.cancel()
        syncRetryJob = null
    }

    private suspend fun processPendingChanges() {
        Logger.log(TAG, "processPendingChanges called")
        val pending = syncQueueDao.getAllPending()
        if (pending.isEmpty()) return

        if (!isInternetAvailable()) {
            Logger.log(TAG, "No internet, cannot process pending")
            syncStatus.postValue(SyncStatus.OFFLINE)
            return
        }

        try {
            Logger.log(TAG, "Processing ${pending.size} pending changes")
            for (entry in pending) {
                when (entry.entityType) {
                    "folder" -> applyFolderChange(entry)
                    "item" -> applyItemChange(entry)
                }
            }
            syncQueueDao.clearAll()
            syncStatus.postValue(SyncStatus.SYNCED)
            stopPeriodicSync()
            Logger.log(TAG, "Pending changes processed successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "Error processing pending changes: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun applyFolderChange(entry: SyncQueueEntity) {
        when (entry.action) {
            "create" -> {
                val folder = db.folderDao().getFolderById(entry.entityId)
                folder?.let { repository.createFolderOnDisk(it) }
            }
            "update" -> {
                val folder = db.folderDao().getFolderById(entry.entityId)
                folder?.let { repository.updateFolderOnDisk(it) }
            }
            "delete" -> {
                repository.deleteFolderOnDisk(entry.entityId)
            }
        }
    }

    private suspend fun applyItemChange(entry: SyncQueueEntity) {
        when (entry.action) {
            "create" -> {
                val item = db.itemDao().getItemById(entry.entityId)
                item?.let { repository.createItemOnDisk(it) }
            }
            "update" -> {
                val item = db.itemDao().getItemById(entry.entityId)
                item?.let { repository.updateItemOnDisk(it) }
            }
            "delete" -> {
                repository.deleteItemOnDisk(entry.entityId)
            }
        }
    }

    private suspend fun checkPendingChanges() {
        val pendingCount = syncQueueDao.getPendingCount()
        if (pendingCount > 0) {
            Logger.log(TAG, "Has $pendingCount pending changes")
            syncStatus.postValue(SyncStatus.PENDING)
            processPendingChanges()
        } else {
            syncStatus.postValue(SyncStatus.SYNCED)
            stopPeriodicSync()
        }
    }

    // ============================================================
    // СОЗДАНИЕ ПАПОК
    // ============================================================

    fun createFolder(name: String) {
        Logger.log(TAG, "createFolder START: ${System.currentTimeMillis()}")
        Logger.log(TAG, "createFolder: name=$name, parentId=$currentFolderId")

        val folder = FolderEntity(
            name = name,
            parentId = currentFolderId,
            createdBy = currentUser,
            path = name
        )

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Logger.log(TAG, "createFolder: before insert into DB")
                db.folderDao().insertFolder(folder)
                Logger.log(TAG, "createFolder: after insert into DB, id=${folder.id}")
            }
            loadContents()

            globalScope.launch {
                try {
                    if (isInternetAvailable()) {
                        Logger.log(TAG, "createFolder: starting sync to disk")
                        repository.createFolderOnDisk(folder)
                        syncInfoDao.setLastModified(System.currentTimeMillis())
                        Logger.log(TAG, "createFolder: sync to disk completed")
                    } else {
                        Logger.log(TAG, "createFolder: no internet, queuing")
                        syncQueueDao.addToQueue(
                            SyncQueueEntity(
                                entityType = "folder",
                                entityId = folder.id,
                                action = "create",
                                parentId = currentFolderId,
                                data = null,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        syncStatus.postValue(SyncStatus.PENDING)
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "createFolder: sync failed: ${e.message}")
                    e.printStackTrace()
                    syncQueueDao.addToQueue(
                        SyncQueueEntity(
                            entityType = "folder",
                            entityId = folder.id,
                            action = "create",
                            parentId = currentFolderId,
                            data = null,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    syncStatus.postValue(SyncStatus.PENDING)
                }
            }
            Logger.log(TAG, "createFolder END: ${System.currentTimeMillis()}")
        }
    }

    // ============================================================
    // РЕДАКТИРОВАНИЕ ПАПОК
    // ============================================================

    fun renameFolder(folderId: String, newName: String) {
        Logger.log(TAG, "renameFolder: $folderId -> $newName")
        viewModelScope.launch {
            try {
                val folder = db.folderDao().getFolderById(folderId)
                if (folder != null) {
                    val updated = folder.copy(name = newName, updatedAt = System.currentTimeMillis())
                    db.folderDao().updateFolder(updated)
                    Logger.log(TAG, "Folder renamed locally: $folderId")

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                repository.updateFolderOnDisk(updated)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
                                Logger.log(TAG, "Folder rename synced to disk: $folderId")
                            } else {
                                syncQueueDao.addToQueue(
                                    SyncQueueEntity(
                                        entityType = "folder",
                                        entityId = folderId,
                                        action = "update",
                                        parentId = folder.parentId,
                                        data = null,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                                syncStatus.postValue(SyncStatus.PENDING)
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Folder rename sync failed: ${e.message}")
                            syncQueueDao.addToQueue(
                                SyncQueueEntity(
                                    entityType = "folder",
                                    entityId = folderId,
                                    action = "update",
                                    parentId = folder.parentId,
                                    data = null,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }

                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error renaming folder: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun getFolderStats(folderId: String, callback: (Pair<Int, Int>) -> Unit) {
        viewModelScope.launch {
            try {
                val itemCount = db.folderDao().getItemCountInFolder(folderId)
                val folderCount = db.folderDao().getSubfolderCountInFolder(folderId)
                callback(Pair(itemCount, folderCount))
            } catch (e: Exception) {
                Logger.log(TAG, "Error getting folder stats: ${e.message}")
                callback(Pair(0, 0))
            }
        }
    }

    fun deleteFolder(folderId: String) {
        Logger.log(TAG, "deleteFolder: $folderId")
        viewModelScope.launch {
            try {
                db.folderDao().deleteFolderById(folderId)
                Logger.log(TAG, "Folder deleted locally: $folderId")

                globalScope.launch {
                    try {
                        if (isInternetAvailable()) {
                            val success = repository.deleteFolderOnDisk(folderId)
                            if (success) {
                                syncInfoDao.setLastModified(System.currentTimeMillis())
                                Logger.log(TAG, "Folder delete synced to disk: $folderId")
                            } else {
                                syncQueueDao.addToQueue(
                                    SyncQueueEntity(
                                        entityType = "folder",
                                        entityId = folderId,
                                        action = "delete",
                                        parentId = null,
                                        data = null,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                                syncStatus.postValue(SyncStatus.PENDING)
                            }
                        } else {
                            syncQueueDao.addToQueue(
                                SyncQueueEntity(
                                    entityType = "folder",
                                    entityId = folderId,
                                    action = "delete",
                                    parentId = null,
                                    data = null,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            syncStatus.postValue(SyncStatus.PENDING)
                        }
                    } catch (e: Exception) {
                        Logger.log(TAG, "Folder delete sync failed: ${e.message}")
                        syncQueueDao.addToQueue(
                            SyncQueueEntity(
                                entityType = "folder",
                                entityId = folderId,
                                action = "delete",
                                parentId = null,
                                data = null,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleting folder: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ ПАПОК
    // ============================================================

    fun moveFolder(folderId: String, newParentId: String?) {
        Logger.log(TAG, "moveFolder: $folderId -> newParentId=$newParentId")
        viewModelScope.launch {
            try {
                val folder = db.folderDao().getFolderById(folderId)
                if (folder != null) {
                    val updated = folder.copy(parentId = newParentId, updatedAt = System.currentTimeMillis())
                    db.folderDao().updateFolder(updated)
                    Logger.log(TAG, "Folder moved locally: $folderId to $newParentId")

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                val success = repository.updateFolderOnDisk(updated)
                                if (success) {
                                    syncInfoDao.setLastModified(System.currentTimeMillis())
                                    Logger.log(TAG, "Folder move synced to disk: $folderId")
                                } else {
                                    syncQueueDao.addToQueue(
                                        SyncQueueEntity(
                                            entityType = "folder",
                                            entityId = folderId,
                                            action = "update",
                                            parentId = newParentId,
                                            data = null,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                    syncStatus.postValue(SyncStatus.PENDING)
                                }
                            } else {
                                syncQueueDao.addToQueue(
                                    SyncQueueEntity(
                                        entityType = "folder",
                                        entityId = folderId,
                                        action = "update",
                                        parentId = newParentId,
                                        data = null,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                                syncStatus.postValue(SyncStatus.PENDING)
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Folder move sync failed: ${e.message}")
                            syncQueueDao.addToQueue(
                                SyncQueueEntity(
                                    entityType = "folder",
                                    entityId = folderId,
                                    action = "update",
                                    parentId = newParentId,
                                    data = null,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error moving folder: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ ПРЕДМЕТОВ
    // ============================================================

    fun moveItem(itemId: String, newParentId: String?) {
        Logger.log(TAG, "moveItem: $itemId -> newParentId=$newParentId")
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    val updated = item.copy(parentId = newParentId, updatedDate = System.currentTimeMillis(), updatedBy = currentUser)
                    updated.computeExpiryFields()
                    db.itemDao().updateItem(updated)
                    Logger.log(TAG, "Item moved locally: $itemId to $newParentId")

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                val success = repository.updateItemOnDisk(updated)
                                if (success) {
                                    syncInfoDao.setLastModified(System.currentTimeMillis())
                                    Logger.log(TAG, "Item move synced to disk: $itemId")
                                } else {
                                    syncQueueDao.addToQueue(
                                        SyncQueueEntity(
                                            entityType = "item",
                                            entityId = itemId,
                                            action = "update",
                                            parentId = newParentId,
                                            data = null,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                    syncStatus.postValue(SyncStatus.PENDING)
                                }
                            } else {
                                syncQueueDao.addToQueue(
                                    SyncQueueEntity(
                                        entityType = "item",
                                        entityId = itemId,
                                        action = "update",
                                        parentId = newParentId,
                                        data = null,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                                syncStatus.postValue(SyncStatus.PENDING)
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Item move sync failed: ${e.message}")
                            syncQueueDao.addToQueue(
                                SyncQueueEntity(
                                    entityType = "item",
                                    entityId = itemId,
                                    action = "update",
                                    parentId = newParentId,
                                    data = null,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error moving item: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // ============================================================
    // СОЗДАНИЕ ПРЕДМЕТОВ
    // ============================================================

    fun createItem(item: ItemEntity, imageBytes: ByteArray? = null) {
        Logger.log(TAG, "createItem START: ${System.currentTimeMillis()}")
        Logger.log(TAG, "createItem: name=${item.name}, id=${item.id}, parentId=${item.parentId}")

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Logger.log(TAG, "createItem: before insert into DB")
                db.itemDao().insertItem(item)
                Logger.log(TAG, "createItem: after insert into DB, id=${item.id}")
            }
            Logger.log(TAG, "createItem: before loadContents()")
            loadContents()
            Logger.log(TAG, "createItem: after loadContents()")

            globalScope.launch {
                try {
                    if (isInternetAvailable()) {
                        Logger.log(TAG, "createItem: starting sync to disk")
                        repository.createItemOnDisk(item)
                        imageBytes?.let {
                            Logger.log(TAG, "createItem: uploading image, size=${it.size}")
                            val success = repository.uploadItemImage(item.id, it)
                            if (success) {
                                Logger.log(TAG, "createItem: image uploaded successfully")
                                val appContext = getApplication<Application>().applicationContext
                                ImageUtils.saveImageLocally(appContext, item.id, it)
                            } else {
                                Logger.log(TAG, "createItem: image upload failed")
                            }
                        }
                        syncInfoDao.setLastModified(System.currentTimeMillis())
                        Logger.log(TAG, "createItem: sync to disk completed")
                    } else {
                        Logger.log(TAG, "createItem: no internet, queuing")
                        syncQueueDao.addToQueue(
                            SyncQueueEntity(
                                entityType = "item",
                                entityId = item.id,
                                action = "create",
                                parentId = item.parentId,
                                data = null,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        syncStatus.postValue(SyncStatus.PENDING)
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "createItem: sync failed: ${e.message}")
                    e.printStackTrace()
                    syncQueueDao.addToQueue(
                        SyncQueueEntity(
                            entityType = "item",
                            entityId = item.id,
                            action = "create",
                            parentId = item.parentId,
                            data = null,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    syncStatus.postValue(SyncStatus.PENDING)
                }
            }
            Logger.log(TAG, "createItem END: ${System.currentTimeMillis()}")
        }
    }

    // ============================================================
    // РЕДАКТИРОВАНИЕ ПРЕДМЕТОВ
    // ============================================================

    fun updateItemQuantity(itemId: String, newQty: Int) {
        Logger.log(TAG, "updateItemQuantity: $itemId -> $newQty")
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    val updated = item.copy(
                        quantity = newQty,
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = currentUser
                    )
                    updated.computeExpiryFields()
                    db.itemDao().updateItem(updated)
                    Logger.log(TAG, "Item quantity updated locally: $itemId")

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                val success = repository.updateItemOnDisk(updated)
                                if (success) {
                                    syncInfoDao.setLastModified(System.currentTimeMillis())
                                    Logger.log(TAG, "Item quantity synced to disk: $itemId")
                                } else {
                                    syncQueueDao.addToQueue(
                                        SyncQueueEntity(
                                            entityType = "item",
                                            entityId = itemId,
                                            action = "update",
                                            parentId = item.parentId,
                                            data = null,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                    syncStatus.postValue(SyncStatus.PENDING)
                                }
                            } else {
                                syncQueueDao.addToQueue(
                                    SyncQueueEntity(
                                        entityType = "item",
                                        entityId = itemId,
                                        action = "update",
                                        parentId = item.parentId,
                                        data = null,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                                syncStatus.postValue(SyncStatus.PENDING)
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Item quantity sync failed: ${e.message}")
                            syncQueueDao.addToQueue(
                                SyncQueueEntity(
                                    entityType = "item",
                                    entityId = itemId,
                                    action = "update",
                                    parentId = item.parentId,
                                    data = null,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }

                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error updating quantity: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun deleteItem(itemId: String) {
        Logger.log(TAG, "deleteItem: $itemId")
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    db.itemDao().deleteItem(item)
                    val appContext = getApplication<Application>().applicationContext
                    val deleted = ImageUtils.deleteLocalImage(appContext, itemId)
                    Logger.log(TAG, "Local image deleted: $deleted, itemId=$itemId")

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                val success = repository.deleteItemOnDisk(itemId)
                                if (success) {
                                    syncInfoDao.setLastModified(System.currentTimeMillis())
                                    Logger.log(TAG, "Item delete synced to disk: $itemId")
                                } else {
                                    Logger.log(TAG, "Failed to delete item on disk, queuing")
                                    syncQueueDao.addToQueue(
                                        SyncQueueEntity(
                                            entityType = "item",
                                            entityId = itemId,
                                            action = "delete",
                                            parentId = null,
                                            data = null,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                    syncStatus.postValue(SyncStatus.PENDING)
                                }
                            } else {
                                syncQueueDao.addToQueue(
                                    SyncQueueEntity(
                                        entityType = "item",
                                        entityId = itemId,
                                        action = "delete",
                                        parentId = null,
                                        data = null,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                                syncStatus.postValue(SyncStatus.PENDING)
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Item delete sync failed: ${e.message}")
                            syncQueueDao.addToQueue(
                                SyncQueueEntity(
                                    entityType = "item",
                                    entityId = itemId,
                                    action = "delete",
                                    parentId = null,
                                    data = null,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleting item: ${e.message}")
                e.printStackTrace()
                syncQueueDao.addToQueue(
                    SyncQueueEntity(
                        entityType = "item",
                        entityId = itemId,
                        action = "delete",
                        parentId = null,
                        data = null,
                        timestamp = System.currentTimeMillis()
                    )
                )
                syncStatus.postValue(SyncStatus.PENDING)
                loadContents()
            }
        }
    }

    // ============================================================
    // ПРИНУДИТЕЛЬНАЯ СИНХРОНИЗАЦИЯ
    // ============================================================

    private var syncJob: Job? = null

    fun forceSync() {
        Logger.log(TAG, "forceSync called")
        if (syncJob?.isActive == true) {
            Logger.log(TAG, "Sync already running, skipping")
            return
        }
        syncJob = viewModelScope.launch {
            syncWithDisk()
        }
    }

    // ============================================================
    // ПОИСК
    // ============================================================

    fun search(query: String) {
        Logger.log(TAG, "search: query='$query'")
        searchQuery = query
        _searchQuery.value = query
        loadContents()
    }

    fun clearSearch() {
        Logger.log(TAG, "clearSearch")
        searchQuery = null
        _searchQuery.value = null
        loadContents()
    }

    fun getSearchQuery(): String? = searchQuery

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            return networkInfo.isConnected
        }
    }

    // ============================================================
    // ПОЛУЧЕНИЕ ВСЕХ ПАПОК
    // ============================================================

    suspend fun getAllFolders(): List<FolderEntity> {
        return withContext(Dispatchers.IO) {
            repository.getAllFolders()
        }
    }

    suspend fun uploadFolderImage(folderId: String, imageBytes: ByteArray): Boolean {
        return repository.uploadFolderImage(folderId, imageBytes)
    }
}
