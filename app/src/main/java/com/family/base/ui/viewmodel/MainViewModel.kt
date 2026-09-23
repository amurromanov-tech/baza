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
                    SettingsEntity(id = 1, isFirstLaunch = false)
                )
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error checking first launch: ${e.message}")
        }
    }

    // ============================================================
    // НАВИГАЦИЯ
    // ============================================================

    fun navigateToFolder(folderId: String?) {
        currentFolderId = folderId
        loadContents()
        viewModelScope.launch { updateCurrentPath() }
    }

    fun navigateToRoot() {
        currentFolderId = null
        loadContents()
        viewModelScope.launch { updateCurrentPath() }
    }

    fun navigateUp() {
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
            }
        }
    }

    // ============================================================
    // ЗАГРУЗКА ДАННЫХ (БЕЗ АРХИВА)
    // ============================================================

    fun loadContents() {
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
            } catch (e: Exception) {
                Logger.log(TAG, "Error in loadContents: ${e.message}")
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
            currentPath.postValue(path)
        } catch (e: Exception) {
            Logger.log(TAG, "Error in updateCurrentPath: ${e.message}")
        }
    }

    // ============================================================
    // СИНХРОНИЗАЦИЯ
    // ============================================================

    fun syncWithDisk() {
        viewModelScope.launch {
            try {
                if (!ensureValidToken()) {
                    syncStatus.postValue(SyncStatus.OFFLINE)
                    return@launch
                }
                if (!isInternetAvailable()) {
                    syncStatus.postValue(SyncStatus.OFFLINE)
                    checkPendingChanges()
                    return@launch
                }

                syncStatus.postValue(SyncStatus.SYNCING)

                val (diskFolders, diskItems) = repository.downloadDataFromDisk()
                mergeData(diskFolders, diskItems)
                uploadUnsyncedImages()
                syncImages(diskItems)

                val pendingCount = syncQueueDao.getPendingCount()
                if (pendingCount > 0) {
                    syncStatus.postValue(SyncStatus.PENDING)
                    processPendingChanges()
                } else {
                    syncStatus.postValue(SyncStatus.SYNCED)
                }

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error in syncWithDisk: ${e.message}")
            }
        }
    }

    private suspend fun uploadUnsyncedImages() {
        val appContext = getApplication<Application>().applicationContext
        val allItems = withContext(Dispatchers.IO) { db.itemDao().getAllItemsRaw() }

        allItems.forEach { item ->
            val localFile = ImageUtils.getLocalImageFile(appContext, item.id)
            if (localFile == null || !localFile.exists()) return@forEach

            if (item.imageUrl.isNullOrEmpty()) {
                try {
                    val bytes = localFile.readBytes()
                    val success = repository.uploadItemImage(item.id, bytes)
                    if (success) {
                        val updated = item.copy(imageUrl = "images/${item.id}.jpg")
                        withContext(Dispatchers.IO) { db.itemDao().updateItem(updated) }
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "Failed to upload image ${item.id}: ${e.message}")
                }
            }
        }
    }

    private suspend fun syncImages(items: List<ItemEntity>) {
        val appContext = getApplication<Application>().applicationContext
        items.filter { !it.imageUrl.isNullOrEmpty() }.forEach { item ->
            val localFile = ImageUtils.getLocalImageFile(appContext, item.id)
            if (localFile != null && localFile.exists()) return@forEach

            try {
                val bitmap = repository.downloadItemImage(item.id)
                if (bitmap != null) {
                    val bytes = ImageUtils.bitmapToJpegBytes(bitmap, 85)
                    ImageUtils.saveImageLocally(appContext, item.id, bytes)
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to download image ${item.id}: ${e.message}")
            }
        }
        loadContents()
    }

    private suspend fun ensureValidToken(): Boolean {
        val token = tokenStorage.getAccessToken() ?: return false
        val auth = "OAuth $token"
        val api = com.family.base.data.remote.YandexDiskApi.getInstance()
        return try {
            val response = api.getDiskResources(auth, "/BAZA")
            when (response.code()) {
                200 -> true
                401, 403 -> tokenStorage.refreshAccessToken() != null
                else -> true
            }
        } catch (e: Exception) { false }
    }

    private suspend fun mergeData(diskFolders: List<FolderEntity>, diskItems: List<ItemEntity>) {
        withContext(Dispatchers.IO) {
            diskFolders.forEach { diskFolder ->
                val local = db.folderDao().getFolderById(diskFolder.id)
                if (local == null) db.folderDao().insertFolder(diskFolder)
                else if (diskFolder.updatedAt > local.updatedAt) db.folderDao().updateFolder(diskFolder)
            }

            diskItems.forEach { diskItem ->
                val local = db.itemDao().getItemById(diskItem.id)
                if (local == null) db.itemDao().insertItem(diskItem)
                else if (diskItem.updatedDate > local.updatedDate) db.itemDao().updateItem(diskItem)
            }

            val diskLastModified = repository.getDiskLastModified()
            val localLastModified = syncInfoDao.getLastModified()
            if (diskLastModified > localLastModified) syncInfoDao.setLastModified(diskLastModified)
        }
    }

    private fun stopPeriodicSync() {
        syncRetryJob?.cancel()
        syncRetryJob = null
    }

    private suspend fun processPendingChanges() {
        val pending = syncQueueDao.getAllPending()
        if (pending.isEmpty()) return
        if (!isInternetAvailable()) {
            syncStatus.postValue(SyncStatus.OFFLINE)
            return
        }

        try {
            for (entry in pending) {
                when (entry.entityType) {
                    "folder" -> applyFolderChange(entry)
                    "item" -> applyItemChange(entry)
                }
            }
            syncQueueDao.clearAll()
            syncStatus.postValue(SyncStatus.SYNCED)
            stopPeriodicSync()
        } catch (e: Exception) {
            Logger.log(TAG, "Error processing pending changes: ${e.message}")
        }
    }

    private suspend fun applyFolderChange(entry: SyncQueueEntity) {
        when (entry.action) {
            "create" -> db.folderDao().getFolderById(entry.entityId)?.let { repository.createFolderOnDisk(it) }
            "update" -> db.folderDao().getFolderById(entry.entityId)?.let { repository.updateFolderOnDisk(it) }
            "delete" -> repository.deleteFolderOnDisk(entry.entityId)
        }
    }

    private suspend fun applyItemChange(entry: SyncQueueEntity) {
        when (entry.action) {
            "create" -> db.itemDao().getItemById(entry.entityId)?.let {
                repository.createItemOnDisk(it)
                uploadItemImageIfExists(it)
            }
            "update" -> db.itemDao().getItemById(entry.entityId)?.let {
                repository.updateItemOnDisk(it)
                uploadItemImageIfExists(it)
            }
            "delete" -> repository.deleteItemOnDisk(entry.entityId)
        }
    }

    private suspend fun uploadItemImageIfExists(item: ItemEntity) {
        val appContext = getApplication<Application>().applicationContext
        val localFile = ImageUtils.getLocalImageFile(appContext, item.id)
        if (localFile != null && localFile.exists() && item.imageUrl.isNullOrEmpty()) {
            try {
                val bytes = localFile.readBytes()
                val success = repository.uploadItemImage(item.id, bytes)
                if (success) {
                    val updated = item.copy(imageUrl = "images/${item.id}.jpg")
                    db.itemDao().updateItem(updated)
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to upload image ${item.id}: ${e.message}")
            }
        }
    }

    private suspend fun checkPendingChanges() {
        val pendingCount = syncQueueDao.getPendingCount()
        if (pendingCount > 0) {
            syncStatus.postValue(SyncStatus.PENDING)
            processPendingChanges()
        } else {
            syncStatus.postValue(SyncStatus.SYNCED)
            stopPeriodicSync()
        }
    }

    // ============================================================
    // ЗАЙМ (ВЫДАЧА) ПРЕДМЕТА
    // ============================================================

    fun lendItem(itemId: String, personName: String, note: String? = null) {
        Logger.log(TAG, "lendItem: $itemId to $personName")
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    val updated = item.copy(
                        isLent = true,
                        lentTo = personName,
                        lentDate = System.currentTimeMillis(),
                        lentNote = note,
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = currentUser
                    )
                    db.itemDao().updateItem(updated)
                    Logger.log(TAG, "Item lent locally: $itemId")

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                repository.updateItemOnDisk(updated)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
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
                            Logger.log(TAG, "Lend sync failed: ${e.message}")
                        }
                    }

                    val history = HistoryEntry(
                        itemId = itemId,
                        action = "lend",
                        oldValue = "В базе",
                        newValue = "Выдан: $personName${if (!note.isNullOrEmpty()) " ($note)" else ""}",
                        changedBy = currentUser
                    )
                    db.historyDao().insertEntry(history)

                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error lending item: ${e.message}")
            }
        }
    }

    fun returnItem(itemId: String) {
        Logger.log(TAG, "returnItem: $itemId")
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    val updated = item.copy(
                        isLent = false,
                        lentTo = null,
                        lentDate = null,
                        lentNote = null,
                        returnDate = null,
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = currentUser
                    )
                    db.itemDao().updateItem(updated)
                    Logger.log(TAG, "Item returned locally: $itemId")

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                repository.updateItemOnDisk(updated)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
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
                            Logger.log(TAG, "Return sync failed: ${e.message}")
                        }
                    }

                    val history = HistoryEntry(
                        itemId = itemId,
                        action = "return",
                        oldValue = "Выдан: ${item.lentTo}",
                        newValue = "Возвращён в базу",
                        changedBy = currentUser
                    )
                    db.historyDao().insertEntry(history)

                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error returning item: ${e.message}")
            }
        }
    }

    suspend fun getLentItems(): List<ItemEntity> {
        return withContext(Dispatchers.IO) {
            db.itemDao().getLentItems()
        }
    }

    // ============================================================
    // АРХИВАЦИЯ ПРЕДМЕТОВ
    // ============================================================

    fun archiveItem(itemId: String, reason: String, note: String?) {
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    db.itemDao().archiveItem(itemId, reason, System.currentTimeMillis(), note)

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                val archived = db.itemDao().getItemById(itemId)
                                archived?.let {
                                    repository.updateItemOnDisk(it)
                                    syncInfoDao.setLastModified(System.currentTimeMillis())
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
                            Logger.log(TAG, "Archive sync failed: ${e.message}")
                        }
                    }

                    val history = HistoryEntry(
                        itemId = itemId,
                        action = "archive",
                        oldValue = "В базе",
                        newValue = "В архиве (${getArchiveReasonText(reason)}${if (!note.isNullOrEmpty()) ": $note" else ""})",
                        changedBy = currentUser
                    )
                    db.historyDao().insertEntry(history)

                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error archiving item: ${e.message}")
            }
        }
    }

    fun unarchiveItem(itemId: String) {
        viewModelScope.launch {
            try {
                db.itemDao().unarchiveItem(itemId, System.currentTimeMillis())

                globalScope.launch {
                    try {
                        if (isInternetAvailable()) {
                            val item = db.itemDao().getItemById(itemId)
                            item?.let {
                                repository.updateItemOnDisk(it)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
                            }
                        }
                    } catch (e: Exception) {
                        Logger.log(TAG, "Unarchive sync failed: ${e.message}")
                    }
                }

                val history = HistoryEntry(
                    itemId = itemId,
                    action = "unarchive",
                    oldValue = "В архиве",
                    newValue = "Вернули в базу",
                    changedBy = currentUser
                )
                db.historyDao().insertEntry(history)

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error unarchiving item: ${e.message}")
            }
        }
    }

    suspend fun getArchivedItems(): List<ItemEntity> {
        return withContext(Dispatchers.IO) { db.itemDao().getArchivedItems() }
    }

    suspend fun getArchivedItemsByReason(reason: String): List<ItemEntity> {
        return withContext(Dispatchers.IO) { db.itemDao().getArchivedItemsByReason(reason) }
    }

    private fun getArchiveReasonText(reason: String): String {
        return when (reason) {
            "eaten" -> "Съедено"
            "broken" -> "Сломано"
            "thrown" -> "Выброшено"
            "gifted" -> "Подарено"
            "sold" -> "Продано"
            "expired" -> "Истёк срок"
            else -> "Другое"
        }
    }

    // ============================================================
    // КОПИРОВАНИЕ ПРЕДМЕТА
    // ============================================================

    fun copyItem(itemId: String) {
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    val copy = item.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "${item.name} (копия)",
                        addedDate = System.currentTimeMillis(),
                        updatedDate = System.currentTimeMillis()
                    )
                    db.itemDao().insertItem(copy)

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                repository.createItemOnDisk(copy)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Copy sync failed: ${e.message}")
                        }
                    }

                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error copying item: ${e.message}")
            }
        }
    }

    // ============================================================
    // СОЗДАНИЕ ПАПОК
    // ============================================================

    fun createFolder(name: String) {
        val folder = FolderEntity(
            name = name,
            parentId = currentFolderId,
            createdBy = currentUser,
            path = name
        )

        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.folderDao().insertFolder(folder) }
            loadContents()

            globalScope.launch {
                try {
                    if (isInternetAvailable()) {
                        repository.createFolderOnDisk(folder)
                        syncInfoDao.setLastModified(System.currentTimeMillis())
                    } else {
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
                }
            }
        }
    }

    // ============================================================
    // РЕДАКТИРОВАНИЕ ПАПОК
    // ============================================================

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch {
            try {
                val folder = db.folderDao().getFolderById(folderId)
                if (folder != null) {
                    val updated = folder.copy(name = newName, updatedAt = System.currentTimeMillis())
                    db.folderDao().updateFolder(updated)

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                repository.updateFolderOnDisk(updated)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Folder rename sync failed: ${e.message}")
                        }
                    }
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error renaming folder: ${e.message}")
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
                callback(Pair(0, 0))
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            try {
                db.folderDao().deleteFolderById(folderId)

                globalScope.launch {
                    try {
                        if (isInternetAvailable()) {
                            repository.deleteFolderOnDisk(folderId)
                            syncInfoDao.setLastModified(System.currentTimeMillis())
                        }
                    } catch (e: Exception) {
                        Logger.log(TAG, "Folder delete sync failed: ${e.message}")
                    }
                }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleting folder: ${e.message}")
            }
        }
    }

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ ПАПОК И ПРЕДМЕТОВ
    // ============================================================

    fun moveFolder(folderId: String, newParentId: String?) {
        viewModelScope.launch {
            try {
                val folder = db.folderDao().getFolderById(folderId)
                if (folder != null) {
                    val updated = folder.copy(parentId = newParentId, updatedAt = System.currentTimeMillis())
                    db.folderDao().updateFolder(updated)

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                repository.updateFolderOnDisk(updated)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Folder move sync failed: ${e.message}")
                        }
                    }
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error moving folder: ${e.message}")
            }
        }
    }

    fun moveItem(itemId: String, newParentId: String?) {
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    val updated = item.copy(parentId = newParentId, updatedDate = System.currentTimeMillis(), updatedBy = currentUser)
                    updated.computeExpiryFields()
                    db.itemDao().updateItem(updated)

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                repository.updateItemOnDisk(updated)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Item move sync failed: ${e.message}")
                        }
                    }
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error moving item: ${e.message}")
            }
        }
    }

    // ============================================================
    // СОЗДАНИЕ ПРЕДМЕТОВ
    // ============================================================

    fun createItem(item: ItemEntity, imageBytes: ByteArray? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.itemDao().insertItem(item) }

            imageBytes?.let { bytes ->
                val appContext = getApplication<Application>().applicationContext
                ImageUtils.saveImageLocally(appContext, item.id, bytes)
            }

            loadContents()

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

    // ============================================================
    // РЕДАКТИРОВАНИЕ ПРЕДМЕТОВ
    // ============================================================

    fun updateItemQuantity(itemId: String, newQty: Int) {
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    val updated = item.copy(quantity = newQty, updatedDate = System.currentTimeMillis(), updatedBy = currentUser)
                    updated.computeExpiryFields()
                    db.itemDao().updateItem(updated)

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                repository.updateItemOnDisk(updated)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Item quantity sync failed: ${e.message}")
                        }
                    }
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error updating quantity: ${e.message}")
            }
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    db.itemDao().deleteItem(item)
                    val appContext = getApplication<Application>().applicationContext
                    ImageUtils.deleteLocalImage(appContext, itemId)

                    globalScope.launch {
                        try {
                            if (isInternetAvailable()) {
                                repository.deleteItemOnDisk(itemId)
                                syncInfoDao.setLastModified(System.currentTimeMillis())
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "Item delete sync failed: ${e.message}")
                        }
                    }
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleting item: ${e.message}")
            }
        }
    }

    // ============================================================
    // ПРИНУДИТЕЛЬНАЯ СИНХРОНИЗАЦИЯ
    // ============================================================

    private var syncJob: Job? = null

    fun forceSync() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch { syncWithDisk() }
    }

    // ============================================================
    // ПОИСК
    // ============================================================

    fun search(query: String) {
        searchQuery = query
        _searchQuery.value = query
        loadContents()
    }

    fun clearSearch() {
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

    suspend fun getAllFolders(): List<FolderEntity> {
        return withContext(Dispatchers.IO) { repository.getAllFolders() }
    }

    suspend fun uploadFolderImage(folderId: String, imageBytes: ByteArray): Boolean {
        return repository.uploadFolderImage(folderId, imageBytes)
    }
}
