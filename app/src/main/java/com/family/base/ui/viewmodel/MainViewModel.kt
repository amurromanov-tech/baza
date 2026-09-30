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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import androidx.lifecycle.LiveData

// ============================================================
// МОДЕЛЬ ПРОГРЕССА СИНХРОНИЗАЦИИ
// ============================================================
enum class SyncPhase {
    SENDING,
    DOWNLOADING,
    UPLOADING_PHOTOS,
    DOWNLOADING_PHOTOS,
    DONE
}

data class SyncProgress(
    val phase: SyncPhase,
    val current: Int,
    val total: Int,
    val message: String
)

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

    val syncResultMessage = MutableLiveData<String?>()
    val syncProgress = MutableLiveData<SyncProgress?>(null)

    private var currentFolderId: String? = null
    private val currentUser: String
        get() = tokenStorage.getUserEmail() ?: "unknown_user"
    private val currentUserDisplayName: String
        get() = tokenStorage.getUserDisplayName() ?: "User"

    private val TAG = "MainViewModel"

    private var allItems: List<ItemEntity> = emptyList()
    private var searchQuery: String? = null

    private val syncMutex = Mutex()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var forceSyncJob: Job? = null
    private var forceSyncRequested = false

    init {
        Logger.log(TAG, "MainViewModel initialized")
        viewModelScope.launch {
            checkFirstLaunch()
        }
    }

    fun getCurrentFolderId(): String? = currentFolderId

    // ============================================================
    // ПРОВЕРКА ПЕРВОГО ЗАПУСКА + РЕМОНТ БАЗЫ
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

        try {
            val orphans = db.itemDao().getOrphanItems()
            if (orphans.isNotEmpty()) {
                Logger.log(TAG, "Found ${orphans.size} orphan items, moving to root")
                db.itemDao().fixOrphanItems()
                Logger.log(TAG, "Orphan items fixed")
            } else {
                Logger.log(TAG, "No orphan items found")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error fixing orphan items: ${e.message}")
        }

        try {
            fixOldImageUrls()
        } catch (e: Exception) {
            Logger.log(TAG, "Error fixing old imageUrls: ${e.message}")
        }

        try {
            fixOldFolderIconUrls()
        } catch (e: Exception) {
            Logger.log(TAG, "Error fixing old folder iconUrls: ${e.message}")
        }
    }

    private suspend fun fixOldImageUrls() {
        val appContext = getApplication<Application>().applicationContext
        val allItems = db.itemDao().getAllItemsWithImageUrl()

        var fixedCount = 0
        for (item in allItems) {
            val expected = "images/${item.id}.jpg"
            val current = item.imageUrl ?: continue

            if (current == expected) continue

            val localFile = ImageUtils.getLocalImageFile(appContext, item.id)
            if (localFile != null && localFile.exists()) {
                db.itemDao().clearImageUrl(item.id)
                fixedCount++
                Logger.log(TAG, "Cleared bad imageUrl for item '${item.name}' (was: $current)")
            } else {
                Logger.log(TAG, "Item '${item.name}' has bad imageUrl but no local file (was: $current)")
            }
        }
        Logger.log(TAG, "Fixed $fixedCount bad imageUrls")
    }

    private suspend fun fixOldFolderIconUrls() {
        val appContext = getApplication<Application>().applicationContext
        val allFolders = db.folderDao().getAllFolders()

        var fixedCount = 0
        for (folder in allFolders) {
            val expected = "folder_${folder.id}.jpg"
            val current = folder.iconUrl ?: continue

            if (current == expected) continue

            val localFile = ImageUtils.getLocalImageFile(appContext, "folder_${folder.id}")
            if (localFile != null && localFile.exists()) {
                val updated = folder.copy(iconUrl = null)
                db.folderDao().updateFolder(updated)
                fixedCount++
                Logger.log(TAG, "Cleared bad iconUrl for folder '${folder.name}' (was: $current)")
            }
        }
        Logger.log(TAG, "Fixed $fixedCount bad folder iconUrls")
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
    // ХЕЛПЕР: ПОСТАВИТЬ В ОЧЕРЕДЬ
    // ============================================================
    private suspend fun enqueue(
        entityType: String,
        entityId: String,
        action: String,
        parentId: String?
    ) {
        syncQueueDao.addToQueue(
            SyncQueueEntity(
                entityType = entityType,
                entityId = entityId,
                action = action,
                parentId = parentId,
                data = null,
                timestamp = System.currentTimeMillis()
            )
        )
        syncStatus.postValue(SyncStatus.PENDING)
    }

    // ============================================================
    // СОЗДАНИЕ ПРЕДМЕТОВ (одиночное)
    // ============================================================

    fun createItem(item: ItemEntity, imageBytes: ByteArray? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.itemDao().insertItem(item) }

            imageBytes?.let { bytes ->
                val appContext = getApplication<Application>().applicationContext
                ImageUtils.saveImageLocally(appContext, item.id, bytes)
            }

            enqueue("item", item.id, "create", item.parentId)

            loadContents()
        }
    }

    // ============================================================
    // СОЗДАНИЕ ПРЕДМЕТОВ (массовое — для чеков)
    // ============================================================

    suspend fun createItemsBatch(items: List<ItemEntity>, folderId: String?): Int {
        if (items.isEmpty()) return 0

        Logger.log(TAG, "createItemsBatch: ${items.size} items, folderId=$folderId")

        return try {
            val prepared = items.map { it.copy(parentId = folderId) }

            withContext(Dispatchers.IO) {
                db.itemDao().insertItems(prepared)
            }

            withContext(Dispatchers.IO) {
                prepared.forEach { item ->
                    syncQueueDao.addToQueue(
                        SyncQueueEntity(
                            entityType = "item",
                            entityId = item.id,
                            action = "create",
                            parentId = folderId,
                            data = null,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }

            Logger.log(TAG, "createItemsBatch: inserted ${prepared.size} items")

            loadContents()
            syncStatus.postValue(SyncStatus.PENDING)

            prepared.size

        } catch (e: Exception) {
            Logger.log(TAG, "createItemsBatch error: ${e.message}", e)
            0
        }
    }

    // ============================================================
    // СИНХРОНИЗАЦИЯ
    // ============================================================

    fun syncWithDisk() {
        applicationScope.launch {
            if (!syncMutex.tryLock()) {
                Logger.log(TAG, "syncWithDisk: already running, skipping")
                return@launch
            }

            val startedAt = System.currentTimeMillis()
            var uploadedCount = 0
            var downloadedCount = 0

            try {
                syncProgress.postValue(SyncProgress(SyncPhase.SENDING, 0, 1, "Проверка авторизации…"))

                if (!ensureValidToken()) {
                    syncStatus.postValue(SyncStatus.OFFLINE)
                    notifySyncResult("Не удалось авторизоваться")
                    return@launch
                }
                if (!isInternetAvailable()) {
                    syncStatus.postValue(SyncStatus.OFFLINE)
                    checkPendingChanges()
                    notifySyncResult("Нет сети. Изменения сохранены локально")
                    return@launch
                }

                syncStatus.postValue(SyncStatus.SYNCING)

                uploadedCount = processPendingChangesInternal()

                syncProgress.postValue(SyncProgress(SyncPhase.DOWNLOADING, 0, 1, "Получение данных…"))
                val (diskFolders, diskItems) = repository.downloadDataFromDisk()
                syncProgress.postValue(SyncProgress(SyncPhase.DOWNLOADING, 1, 1, "Слияние данных…"))
                mergeData(diskFolders, diskItems)
                downloadedCount = diskFolders.size + diskItems.size

                uploadUnsyncedImages()
                uploadUnsyncedFolderImages()

                syncImages()
                syncFolderImages()

                val pendingCount = syncQueueDao.getPendingCount()
                if (pendingCount > 0) {
                    syncStatus.postValue(SyncStatus.PENDING)
                } else {
                    syncStatus.postValue(SyncStatus.SYNCED)
                }

                syncProgress.postValue(
                    SyncProgress(
                        SyncPhase.DONE,
                        uploadedCount + downloadedCount,
                        uploadedCount + downloadedCount,
                        "Готово: отправлено $uploadedCount, получено $downloadedCount"
                    )
                )

                loadContents()

                val elapsed = System.currentTimeMillis() - startedAt
                Logger.log(TAG, "Sync finished in ${elapsed}ms: uploaded=$uploadedCount, downloaded=$downloadedCount")

                if (forceSyncRequested) {
                    val msg = buildString {
                        append("✅ Синхронизация завершена\n")
                        append("⬆️ Отправлено: $uploadedCount\n")
                        append("⬇️ Получено: $downloadedCount")
                        if (pendingCount > 0) append("\n⏳ Осталось в очереди: $pendingCount")
                    }
                    notifySyncResult(msg)
                    forceSyncRequested = false
                }

            } catch (e: Exception) {
                Logger.log(TAG, "Error in syncWithDisk: ${e.message}", e)
                if (forceSyncRequested) {
                    notifySyncResult("❌ Ошибка синхронизации: ${e.message}")
                    forceSyncRequested = false
                }
                syncProgress.postValue(null)
            } finally {
                syncMutex.unlock()
            }
        }
    }

    private fun notifySyncResult(message: String) {
        syncResultMessage.postValue(message)
        applicationScope.launch {
            delay(2000)
            syncResultMessage.postValue(null)
        }
    }

    private suspend fun uploadUnsyncedImages() {
        val appContext = getApplication<Application>().applicationContext
        val allItems = withContext(Dispatchers.IO) { db.itemDao().getAllItemsRaw() }
        val itemsToUpload = allItems.filter { item ->
            val f = ImageUtils.getLocalImageFile(appContext, item.id)
            f != null && f.exists() && item.imageUrl.isNullOrEmpty()
        }

        if (itemsToUpload.isEmpty()) {
            Logger.log(TAG, "No images to upload")
            return
        }

        var uploadedCount = 0
        val total = itemsToUpload.size

        itemsToUpload.forEachIndexed { index, item ->
            syncProgress.postValue(
                SyncProgress(
                    SyncPhase.UPLOADING_PHOTOS,
                    index + 1,
                    total,
                    "Загрузка фото: ${item.name}"
                )
            )

            val localFile = ImageUtils.getLocalImageFile(appContext, item.id)
            if (localFile == null || !localFile.exists()) return@forEachIndexed

            try {
                val bytes = localFile.readBytes()
                val success = repository.uploadItemImage(item.id, bytes)
                if (success) {
                    val updated = item.copy(imageUrl = "images/${item.id}.jpg")
                    withContext(Dispatchers.IO) { db.itemDao().updateItem(updated) }
                    uploadedCount++
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to upload image ${item.id}: ${e.message}")
            }
        }
        Logger.log(TAG, "Uploaded $uploadedCount images")
    }

    private suspend fun uploadUnsyncedFolderImages() {
        val appContext = getApplication<Application>().applicationContext
        val allFolders = withContext(Dispatchers.IO) { db.folderDao().getAllFolders() }
        val foldersToUpload = allFolders.filter { folder ->
            val f = ImageUtils.getLocalImageFile(appContext, "folder_${folder.id}")
            f != null && f.exists() && f.length() > 0L && folder.iconUrl.isNullOrEmpty()
        }

        if (foldersToUpload.isEmpty()) {
            Logger.log(TAG, "No folder images to upload")
            return
        }

        var uploadedCount = 0
        val total = foldersToUpload.size

        foldersToUpload.forEachIndexed { index, folder ->
            syncProgress.postValue(
                SyncProgress(
                    SyncPhase.UPLOADING_PHOTOS,
                    index + 1,
                    total,
                    "Загрузка иконки: ${folder.name}"
                )
            )

            val localFile = ImageUtils.getLocalImageFile(appContext, "folder_${folder.id}")
            if (localFile == null || !localFile.exists()) return@forEachIndexed

            try {
                val bytes = localFile.readBytes()
                val success = repository.uploadFolderImage(folder.id, bytes)
                if (success) {
                    val updated = folder.copy(iconUrl = "folder_${folder.id}.jpg")
                    withContext(Dispatchers.IO) {
                        db.folderDao().updateFolder(updated)
                        repository.updateFolderOnDisk(updated)
                    }
                    uploadedCount++
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to upload folder image ${folder.id}: ${e.message}")
            }
        }
        Logger.log(TAG, "Uploaded $uploadedCount folder images")
    }

    private suspend fun syncImages() {
        val appContext = getApplication<Application>().applicationContext
        val allItems = withContext(Dispatchers.IO) { db.itemDao().getAllItemsRaw() }
        val itemsToDownload = allItems.filter { item ->
            if (item.imageUrl.isNullOrEmpty()) return@filter false
            val f = ImageUtils.getLocalImageFile(appContext, item.id)
            f == null || !f.exists()
        }

        if (itemsToDownload.isEmpty()) {
            Logger.log(TAG, "No images to download")
            return
        }

        var downloadedCount = 0
        val total = itemsToDownload.size

        itemsToDownload.forEachIndexed { index, item ->
            syncProgress.postValue(
                SyncProgress(
                    SyncPhase.DOWNLOADING_PHOTOS,
                    index + 1,
                    total,
                    "Скачивание фото: ${item.name}"
                )
            )

            try {
                val bitmap = repository.downloadItemImage(item.id)
                if (bitmap != null) {
                    val bytes = ImageUtils.bitmapToJpegBytes(bitmap, 85)
                    ImageUtils.saveImageLocally(appContext, item.id, bytes)
                    downloadedCount++
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to download image ${item.id}: ${e.message}")
            }
        }
        Logger.log(TAG, "Images downloaded: $downloadedCount")
        loadContents()
    }

    private suspend fun syncFolderImages() {
        val appContext = getApplication<Application>().applicationContext
        val allFolders = withContext(Dispatchers.IO) { db.folderDao().getAllFolders() }
        val foldersToDownload = allFolders.filter { folder ->
            val f = ImageUtils.getLocalImageFile(appContext, "folder_${folder.id}")
            f == null || !f.exists() || f.length() == 0L
        }

        if (foldersToDownload.isEmpty()) return

        var downloadedCount = 0
        val total = foldersToDownload.size

        foldersToDownload.forEachIndexed { index, folder ->
            syncProgress.postValue(
                SyncProgress(
                    SyncPhase.DOWNLOADING_PHOTOS,
                    index + 1,
                    total,
                    "Скачивание иконки: ${folder.name}"
                )
            )

            try {
                val bitmap = repository.downloadFolderImage(folder.id)
                if (bitmap != null) {
                    val bytes = ImageUtils.bitmapToJpegBytes(bitmap, 85)
                    if (bytes != null) {
                        ImageUtils.saveImageLocally(appContext, "folder_${folder.id}", bytes)
                        if (folder.iconUrl.isNullOrEmpty()) {
                            val updated = folder.copy(iconUrl = "folder_${folder.id}.jpg")
                            withContext(Dispatchers.IO) { db.folderDao().updateFolder(updated) }
                        }
                        downloadedCount++
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to download folder image ${folder.id}: ${e.message}")
            }
        }
        Logger.log(TAG, "Folder images downloaded: $downloadedCount")
    }

    private suspend fun ensureValidToken(): Boolean {
        val token = tokenStorage.getAccessToken() ?: return false
        val auth = "OAuth $token"
        val api = com.family.base.data.remote.YandexDiskApi.getInstance()
        val folderName = tokenStorage.getFolderName() ?: "BAZA"
        val rootPath = "/$folderName"
        return try {
            val response = api.getDiskResources(auth, rootPath)
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
                if (local == null) {
                    db.folderDao().insertFolder(diskFolder)
                } else if (diskFolder.updatedAt > local.updatedAt) {
                    val merged = diskFolder.copy(iconUrl = diskFolder.iconUrl ?: local.iconUrl)
                    db.folderDao().updateFolder(merged)
                }
            }

            diskItems.forEach { diskItem ->
                val local = db.itemDao().getItemById(diskItem.id)
                if (local == null) {
                    db.itemDao().insertItem(diskItem)
                } else if (diskItem.updatedDate > local.updatedDate) {
                    val merged = diskItem.copy(imageUrl = diskItem.imageUrl ?: local.imageUrl)
                    db.itemDao().updateItem(merged)
                }
            }

            val diskLastModified = repository.getDiskLastModified()
            val localLastModified = syncInfoDao.getLastModified()
            if (diskLastModified > localLastModified) syncInfoDao.setLastModified(diskLastModified)
        }
    }

    private suspend fun processPendingChangesInternal(): Int {
        val pending = syncQueueDao.getAllPending()
        if (pending.isEmpty()) return 0
        if (!isInternetAvailable()) return 0

        var successCount = 0
        val total = pending.size

        for ((index, entry) in pending.withIndex()) {
            try {
                val displayName = when (entry.entityType) {
                    "folder" -> db.folderDao().getFolderById(entry.entityId)?.name ?: "папка"
                    "item" -> db.itemDao().getItemById(entry.entityId)?.name ?: "предмет"
                    else -> "объект"
                }

                syncProgress.postValue(
                    SyncProgress(
                        SyncPhase.SENDING,
                        index + 1,
                        total,
                        "Отправка: $displayName"
                    )
                )

                when (entry.entityType) {
                    "folder" -> applyFolderChange(entry)
                    "item" -> applyItemChange(entry)
                }
                syncQueueDao.removeFromQueueById(entry.id)
                successCount++
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to process pending entry ${entry.id} (${entry.entityType}/${entry.action}): ${e.message}")
            }
        }
        return successCount
    }

    private suspend fun applyFolderChange(entry: SyncQueueEntity) {
        when (entry.action) {
            "create" -> db.folderDao().getFolderById(entry.entityId)?.let {
                repository.createFolderOnDisk(it)
                val appContext = getApplication<Application>().applicationContext
                val localFile = ImageUtils.getLocalImageFile(appContext, "folder_${it.id}")
                if (localFile != null && localFile.exists() && it.iconUrl.isNullOrEmpty()) {
                    try {
                        val success = repository.uploadFolderImage(it.id, localFile.readBytes())
                        if (success) {
                            val updated = it.copy(iconUrl = "folder_${it.id}.jpg")
                            db.folderDao().updateFolder(updated)
                            repository.updateFolderOnDisk(updated)
                        }
                    } catch (e: Exception) {
                        Logger.log(TAG, "applyFolderChange: upload folder image failed: ${e.message}")
                    }
                }
            }
            "update" -> db.folderDao().getFolderById(entry.entityId)?.let {
                val fresh = it.copy(updatedAt = System.currentTimeMillis())
                db.folderDao().updateFolder(fresh)
                repository.updateFolderOnDisk(fresh)
            }
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
                val fresh = it.copy(updatedDate = System.currentTimeMillis())
                db.itemDao().updateItem(fresh)
                repository.updateItemOnDisk(fresh)
                uploadItemImageIfExists(fresh)
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
        } else {
            syncStatus.postValue(SyncStatus.SYNCED)
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
                    enqueue("item", itemId, "update", item.parentId)

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
                    enqueue("item", itemId, "update", item.parentId)

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
                    enqueue("item", itemId, "update", item.parentId)

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
                val item = db.itemDao().getItemById(itemId)
                db.itemDao().unarchiveItem(itemId, System.currentTimeMillis())
                enqueue("item", itemId, "update", item?.parentId)

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

    // ============================================================
    // СПИСАНИЕ ЧАСТИ КОЛИЧЕСТВА (write-off)
    // ============================================================
    /**
     * Списывает [count] штук предмета.
     *
     * - Если count < quantity → quantity -= count, запись в историю («списано N шт.»), предмет остаётся в базе.
     * - Если count == quantity → весь предмет уходит в архив с причиной [reason], запись в историю («в архив»).
     * - Если count > quantity → ничего не делаем (вызывающий код должен это предотвратить).
     *
     * @param reason — ключ причины для архивации (используется только при полном списании).
     * @param note — комментарий (опционально).
     */
    fun writeOffItem(itemId: String, count: Int, reason: String?, note: String?) {
        Logger.log(TAG, "writeOffItem: itemId=$itemId, count=$count, reason=$reason")
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item == null) {
                    Logger.log(TAG, "writeOffItem: item not found: $itemId")
                    return@launch
                }

                if (count <= 0) {
                    Logger.log(TAG, "writeOffItem: count <= 0, ignoring")
                    return@launch
                }

                if (count > item.quantity) {
                    Logger.log(TAG, "writeOffItem: count ($count) > quantity (${item.quantity}), ignoring")
                    return@launch
                }

                val now = System.currentTimeMillis()

                if (count == item.quantity) {
                    // ===== Списываем всё → в архив =====
                    val reasonKey = reason ?: "used_up"
                    db.itemDao().archiveItem(itemId, reasonKey, now, note)
                    enqueue("item", itemId, "update", item.parentId)

                    val history = HistoryEntry(
                        itemId = itemId,
                        action = "write_off",
                        oldValue = "${item.quantity} шт.",
                        newValue = "Списано всё → в архиве (${getArchiveReasonText(reasonKey)}${if (!note.isNullOrEmpty()) ": $note" else ""})",
                        changedBy = currentUser
                    )
                    db.historyDao().insertEntry(history)

                    Logger.log(TAG, "writeOffItem: fully written off → archived ($reasonKey)")

                } else {
                    // ===== Частичное списание =====
                    val newQty = item.quantity - count
                    val updated = item.copy(
                        quantity = newQty,
                        updatedDate = now,
                        updatedBy = currentUser
                    )
                    updated.computeExpiryFields()
                    db.itemDao().updateItem(updated)
                    enqueue("item", itemId, "update", item.parentId)

                    val history = HistoryEntry(
                        itemId = itemId,
                        action = "write_off",
                        oldValue = "${item.quantity} шт.",
                        newValue = "${newQty} шт. (списано $count${if (!note.isNullOrEmpty()) ", $note" else ""})",
                        changedBy = currentUser
                    )
                    db.historyDao().insertEntry(history)

                    Logger.log(TAG, "writeOffItem: partial write-off, quantity $newQty")
                }

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error writing off item: ${e.message}", e)
            }
        }
    }

    // ===== СТАТУС: used_up «Израсходовано» — САМЫМ ПЕРВЫМ =====
    private fun getArchiveReasonText(reason: String): String {
        return when (reason) {
            "used_up" -> "🧴 Израсходовано"
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

                    val appContext = getApplication<Application>().applicationContext
                    val localFile = ImageUtils.getLocalImageFile(appContext, item.id)
                    if (localFile != null && localFile.exists()) {
                        val bytes = localFile.readBytes()
                        ImageUtils.saveImageLocally(appContext, copy.id, bytes)
                    }

                    enqueue("item", copy.id, "create", copy.parentId)

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
            enqueue("folder", folder.id, "create", currentFolderId)
            loadContents()
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
                    enqueue("folder", folderId, "update", folder.parentId)
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
                val folder = db.folderDao().getFolderById(folderId)

                db.itemDao().moveItemsToRoot(folderId)
                db.folderDao().moveSubfoldersToRoot(folderId)

                Logger.log(TAG, "deleteFolder: moved items/subfolders to root, folderId=$folderId")

                db.folderDao().deleteFolderById(folderId)
                enqueue("folder", folderId, "delete", folder?.parentId)
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
                    enqueue("folder", folderId, "update", newParentId)
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
                    val updated = item.copy(
                        parentId = newParentId,
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = currentUser
                    )
                    updated.computeExpiryFields()
                    db.itemDao().updateItem(updated)
                    enqueue("item", itemId, "update", newParentId)
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error moving item: ${e.message}")
            }
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
                    val updated = item.copy(
                        quantity = newQty,
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = currentUser
                    )
                    updated.computeExpiryFields()
                    db.itemDao().updateItem(updated)
                    enqueue("item", itemId, "update", item.parentId)
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error updating quantity: ${e.message}")
            }
        }
    }

    fun updateItemFull(item: ItemEntity) {
        viewModelScope.launch {
            try {
                val updated = item.copy(
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = currentUser
                )
                updated.computeExpiryFields()
                db.itemDao().updateItem(updated)
                enqueue("item", item.id, "update", item.parentId)
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error updating item: ${e.message}")
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
                    enqueue("item", itemId, "delete", item.parentId)
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

    fun forceSync() {
        if (forceSyncJob?.isActive == true) {
            Logger.log(TAG, "forceSync: already running, skipping")
            return
        }
        forceSyncRequested = true
        syncResultMessage.postValue("⏳ Синхронизация…")
        forceSyncJob = applicationScope.launch { syncWithDisk() }
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
        val connectivityManager = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
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
