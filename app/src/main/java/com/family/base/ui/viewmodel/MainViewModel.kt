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
        get() = tokenStorage.getCurrentUser()
            ?: tokenStorage.getUserDisplayName()
            ?: "Пользователь"

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
            val allFoldersCount = db.folderDao().getAllFolders().size
            val orphans = db.itemDao().getOrphanItems()
            if (orphans.isNotEmpty() && allFoldersCount > 0) {
                Logger.log(TAG, "Found ${orphans.size} orphan items, moving to root")
                db.itemDao().fixOrphanItems()
                Logger.log(TAG, "Orphan items fixed")
            } else if (orphans.isNotEmpty()) {
                Logger.log(TAG, "Found ${orphans.size} orphan items but no folders — SKIP fix to protect data")
            } else {
                Logger.log(TAG, "No orphan items found")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error fixing orphan items: ${e.message}")
        }

        try {
            val orphanNestedItems = db.itemDao().getOrphanNestedItems()
            if (orphanNestedItems.isNotEmpty()) {
                Logger.log(TAG, "Found ${orphanNestedItems.size} orphan nested items, detaching")
                db.itemDao().fixOrphanNestedItems()
                Logger.log(TAG, "Orphan nested items fixed")
            } else {
                Logger.log(TAG, "No orphan nested items found")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error fixing orphan nested items: ${e.message}")
        }

        try {
            val orphanNestedFolders = db.folderDao().getOrphanNestedFolders()
            if (orphanNestedFolders.isNotEmpty()) {
                Logger.log(TAG, "Found ${orphanNestedFolders.size} orphan nested folders, detaching")
                db.folderDao().fixOrphanNestedFolders()
                Logger.log(TAG, "Orphan nested folders fixed")
            } else {
                Logger.log(TAG, "No orphan nested folders found")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error fixing orphan nested folders: ${e.message}")
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
        parentId: String?,
        parentItemId: String? = null
    ) {
        syncQueueDao.addToQueue(
            SyncQueueEntity(
                entityType = entityType,
                entityId = entityId,
                action = action,
                parentId = parentId,
                parentItemId = parentItemId,
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

            enqueue("item", item.id, "create", item.parentId, item.parentItemId)

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
            val prepared = items.map { it.copy(parentId = folderId, parentItemId = null) }

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
                            parentItemId = null,
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
    // ДЕТИ (проверка / отвязка / безопасное удаление)
    // ============================================================

    fun getChildrenCount(itemId: String, callback: (Pair<Int, Int>) -> Unit) {
        viewModelScope.launch {
            try {
                val result = repository.countChildren(itemId)
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                Logger.log(TAG, "Error getChildrenCount: ${e.message}")
                withContext(Dispatchers.Main) { callback(Pair(0, 0)) }
            }
        }
    }

    fun detachAllChildren(itemId: String, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val count = repository.detachAllChildren(itemId)

                db.itemDao().getItemById(itemId)?.let { parent ->
                    enqueue("item", itemId, "update", parent.parentId, parent.parentItemId)
                }

                Logger.log(TAG, "detachAllChildren: item=$itemId, count=$count")
                withContext(Dispatchers.Main) { onDone(count) }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error detachAllChildren: ${e.message}")
                withContext(Dispatchers.Main) { onDone(0) }
            }
        }
    }

    fun detachAllChildrenAndDelete(itemId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item == null) {
                    withContext(Dispatchers.Main) { onDone(false) }
                    return@launch
                }

                val detached = repository.detachAllChildren(itemId)
                Logger.log(TAG, "detachAllChildrenAndDelete: detached $detached children from $itemId")

                db.itemDao().deleteItem(item)
                val appContext = getApplication<Application>().applicationContext
                ImageUtils.deleteLocalImage(appContext, itemId)
                enqueue("item", itemId, "delete", item.parentId, item.parentItemId)

                Logger.log(TAG, "detachAllChildrenAndDelete: parent $itemId deleted")
                withContext(Dispatchers.Main) { onDone(true) }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error detachAllChildrenAndDelete: ${e.message}")
                withContext(Dispatchers.Main) { onDone(false) }
            }
        }
    }

    fun detachAllChildrenAndArchive(itemId: String, reason: String, note: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item == null) {
                    withContext(Dispatchers.Main) { onDone(false) }
                    return@launch
                }

                val detached = repository.detachAllChildren(itemId)
                Logger.log(TAG, "detachAllChildrenAndArchive: detached $detached children from $itemId")

                val now = System.currentTimeMillis()
                db.itemDao().archiveItem(itemId, reason, now, note)
                enqueue("item", itemId, "update", item.parentId, item.parentItemId)

                db.historyDao().insertEntry(
                    HistoryEntry(
                        itemId = itemId,
                        action = "archive",
                        oldValue = "В базе (отвязано детей: $detached)",
                        newValue = "В архиве (${getArchiveReasonText(reason)}${if (!note.isNullOrEmpty()) ": $note" else ""})",
                        changedBy = currentUser
                    )
                )

                Logger.log(TAG, "detachAllChildrenAndArchive: parent $itemId archived")
                withContext(Dispatchers.Main) { onDone(true) }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error detachAllChildrenAndArchive: ${e.message}")
                withContext(Dispatchers.Main) { onDone(false) }
            }
        }
    }

    // ============================================================
    // B-5: ВЛОЖЕННЫЕ (секция «📦 Вложенные»)
    // ============================================================

    fun getNestedContent(
        parentItemId: String,
        callback: (List<FolderEntity>, List<ItemEntity>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val folders = withContext(Dispatchers.IO) {
                    repository.getNestedFolders(parentItemId)
                }
                val items = withContext(Dispatchers.IO) {
                    repository.getNestedItems(parentItemId)
                }
                withContext(Dispatchers.Main) { callback(folders, items) }
            } catch (e: Exception) {
                Logger.log(TAG, "Error getNestedContent: ${e.message}")
                withContext(Dispatchers.Main) { callback(emptyList(), emptyList()) }
            }
        }
    }

    fun createFolderInItem(
        name: String,
        parentItemId: String,
        onDone: (String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val parentItem = withContext(Dispatchers.IO) { db.itemDao().getItemById(parentItemId) }
                if (parentItem == null) {
                    Logger.log(TAG, "createFolderInItem: parent item not found $parentItemId")
                    withContext(Dispatchers.Main) { onDone(null) }
                    return@launch
                }

                val parentFolderId = parentItem.parentId
                val folder = withContext(Dispatchers.IO) {
                    repository.createFolderInItem(
                        name = name,
                        parentItemId = parentItemId,
                        parentFolderId = parentFolderId,
                        creator = currentUser
                    )
                }

                enqueue("folder", folder.id, "create", parentFolderId, parentItemId)

                withContext(Dispatchers.IO) {
                    db.historyDao().insertEntry(
                        HistoryEntry(
                            itemId = parentItemId,
                            action = "add_nested_folder",
                            oldValue = null,
                            newValue = "📁 $name",
                            changedBy = currentUser
                        )
                    )
                }

                Logger.log(TAG, "createFolderInItem: created folder ${folder.id} '${folder.name}' in item $parentItemId")
                withContext(Dispatchers.Main) { onDone(folder.id) }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error createFolderInItem: ${e.message}")
                withContext(Dispatchers.Main) { onDone(null) }
            }
        }
    }

    // ============================================================
    // B-5-FIX: ПРОВЕРКА ДЕТЕЙ ДЛЯ ПАПКИ
    // ============================================================

    fun getFolderChildrenCount(folderId: String, callback: (Pair<Int, Int>) -> Unit) {
        viewModelScope.launch {
            try {
                val folderCount = withContext(Dispatchers.IO) {
                    db.folderDao().getSubfolderCountInFolder(folderId)
                }
                val itemCount = withContext(Dispatchers.IO) {
                    db.folderDao().getItemCountInFolder(folderId)
                }
                withContext(Dispatchers.Main) { callback(Pair(folderCount, itemCount)) }
            } catch (e: Exception) {
                Logger.log(TAG, "Error getFolderChildrenCount: ${e.message}")
                withContext(Dispatchers.Main) { callback(Pair(0, 0)) }
            }
        }
    }

    fun detachAllFolderChildren(folderId: String, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val folder = withContext(Dispatchers.IO) { db.folderDao().getFolderById(folderId) }
                if (folder == null) {
                    withContext(Dispatchers.Main) { onDone(0) }
                    return@launch
                }

                val subfolders = withContext(Dispatchers.IO) {
                    db.folderDao().getFoldersByParent(folderId)
                }
                val items = withContext(Dispatchers.IO) {
                    db.itemDao().getItemsByParent(folderId)
                }

                val now = System.currentTimeMillis()

                subfolders.forEach { sub ->
                    val updated = sub.copy(parentId = null, updatedAt = now)
                    withContext(Dispatchers.IO) { db.folderDao().updateFolder(updated) }
                    enqueue("folder", sub.id, "update", null, null)
                }

                items.forEach { item ->
                    val updated = item.copy(parentId = null, updatedDate = now, updatedBy = currentUser)
                    updated.computeExpiryFields()
                    withContext(Dispatchers.IO) { db.itemDao().updateItem(updated) }
                    enqueue("item", item.id, "update", null, item.parentItemId)
                }

                val total = subfolders.size + items.size
                Logger.log(
                    TAG,
                    "detachAllFolderChildren: folder=$folderId, subfolders=${subfolders.size}, items=${items.size}"
                )

                withContext(Dispatchers.Main) { onDone(total) }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error detachAllFolderChildren: ${e.message}")
                withContext(Dispatchers.Main) { onDone(0) }
            }
        }
    }

    fun detachAllFolderChildrenAndDelete(folderId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                detachAllFolderChildren(folderId) { /* ignore count */ }

                val folder = withContext(Dispatchers.IO) { db.folderDao().getFolderById(folderId) }
                if (folder != null) {
                    withContext(Dispatchers.IO) { db.folderDao().deleteFolderById(folderId) }
                    enqueue("folder", folderId, "delete", folder.parentId, folder.parentItemId)
                }

                withContext(Dispatchers.Main) { onDone(true) }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error detachAllFolderChildrenAndDelete: ${e.message}")
                withContext(Dispatchers.Main) { onDone(false) }
            }
        }
    }

    // ============================================================
    // B-5-FIX-2: ПРОВЕРКА ДЕТЕЙ ДЛЯ АРХИВАЦИИ / ПОЛНОГО СПИСАНИЯ
    // ============================================================

    /**
     * Формирует сообщение для диалога «Нельзя архивировать».
     * Возвращает null, если детей нет.
     */
    private suspend fun buildArchiveBlockMessage(itemId: String): String? {
        return try {
            val children = repository.countChildren(itemId)
            val foldersCount = children.first
            val itemsCount = children.second
            val total = foldersCount + itemsCount

            if (total == 0) null
            else buildString {
                val item = db.itemDao().getItemById(itemId)
                val name = item?.name ?: "предмет"
                append("У предмета «$name» есть вложенные:\n\n")
                if (foldersCount > 0) append("📁 Папок: $foldersCount\n")
                if (itemsCount > 0) append("📦 Предметов: $itemsCount\n")
                append("\nНельзя архивировать, пока есть вложенные.\n")
                append("Сначала отвяжите их — они поднимутся в ту же папку, где лежит этот предмет.")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "buildArchiveBlockMessage error: ${e.message}")
            null
        }
    }

    // ============================================================
    // СИНХРОНИЗАЦИЯ (ИНКРЕМЕНТАЛЬНАЯ)
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

                val token = tokenStorage.getAccessToken()
                if (token.isNullOrEmpty()) {
                    Logger.log(TAG, "syncWithDisk: no token, skipping sync")
                    syncStatus.postValue(SyncStatus.OFFLINE)
                    syncProgress.postValue(null)
                    return@launch
                }

                if (!isInternetAvailable()) {
                    Logger.log(TAG, "syncWithDisk: no internet, skipping sync")
                    syncStatus.postValue(SyncStatus.OFFLINE)
                    checkPendingChanges()
                    notifySyncResult("Нет сети. Изменения сохранены локально")
                    syncProgress.postValue(null)
                    return@launch
                }

                if (!ensureValidToken()) {
                    syncStatus.postValue(SyncStatus.OFFLINE)
                    notifySyncResult("Не удалось авторизоваться")
                    syncProgress.postValue(null)
                    return@launch
                }

                syncStatus.postValue(SyncStatus.SYNCING)

                val tMetrics = System.currentTimeMillis()
                val localItemsCount = withContext(Dispatchers.IO) { db.itemDao().getAllItemsRaw().size }
                val localFoldersCount = withContext(Dispatchers.IO) { db.folderDao().getAllFolders().size }
                val pendingCount = withContext(Dispatchers.IO) { syncQueueDao.getPendingCount() }
                val localLastModified = withContext(Dispatchers.IO) { syncInfoDao.getLastModified() }
                val diskLastModified: Long? = withContext(Dispatchers.IO) { repository.getDiskLastModified() }
                Logger.log(TAG, "TIMING: collect metrics took ${System.currentTimeMillis() - tMetrics}ms")

                val isFirstLaunch = (localItemsCount == 0 && localFoldersCount == 0)

                Logger.log(
                    TAG,
                    "syncWithDisk: local items=$localItemsCount, folders=$localFoldersCount, " +
                        "pending=$pendingCount, localModified=$localLastModified, " +
                        "diskModified=${diskLastModified ?: "null (network error)"}, " +
                        "isFirstLaunch=$isFirstLaunch"
                )

                if (isFirstLaunch) {
                    Logger.log(TAG, "syncWithDisk: FIRST LAUNCH — download only (no upload)")

                    syncProgress.postValue(
                        SyncProgress(SyncPhase.DOWNLOADING, 0, 1, "Первый запуск: загрузка данных с Диска…")
                    )

                    val downloadResult = repository.downloadDataFromDisk()
                    syncProgress.postValue(SyncProgress(SyncPhase.DOWNLOADING, 1, 1, "Слияние данных…"))
                    mergeData(
                        downloadResult.folders,
                        downloadResult.items,
                        downloadResult.foldersError,
                        downloadResult.itemsError
                    )
                    downloadedCount = downloadResult.folders.size + downloadResult.items.size

                    val tPhotoBlock = System.currentTimeMillis()
                    syncImages()
                    syncFolderImages()
                    Logger.log(TAG, "TIMING: photo block (first launch) took ${System.currentTimeMillis() - tPhotoBlock}ms")

                    if (diskLastModified != null && diskLastModified > 0L) {
                        withContext(Dispatchers.IO) { syncInfoDao.setLastModified(diskLastModified) }
                    }

                    finalizeSync(uploadedCount, downloadedCount, startedAt, localItemsCount, localFoldersCount)
                    return@launch
                }

                if (pendingCount > 0) {
                    Logger.log(TAG, "syncWithDisk: pending=$pendingCount → full upload")

                    syncProgress.postValue(
                        SyncProgress(
                            SyncPhase.SENDING,
                            0,
                            1,
                            "Отправка данных ($localItemsCount предм., $localFoldersCount папок, $pendingCount в очереди)…"
                        )
                    )

                    val tUploadItems = System.currentTimeMillis()
                    val uploadedItems = repository.uploadAllItemsToDisk()
                    Logger.log(TAG, "TIMING: uploadAllItemsToDisk #1 took ${System.currentTimeMillis() - tUploadItems}ms")

                    val tUploadFolders = System.currentTimeMillis()
                    val uploadedFolders = repository.uploadAllFoldersToDisk()
                    Logger.log(TAG, "TIMING: uploadAllFoldersToDisk took ${System.currentTimeMillis() - tUploadFolders}ms")

                    Logger.log(TAG, "syncWithDisk: full upload items=$uploadedItems, folders=$uploadedFolders")

                    val tPending = System.currentTimeMillis()
                    uploadedCount = processPendingChangesInternal()
                    Logger.log(TAG, "TIMING: processPendingChangesInternal took ${System.currentTimeMillis() - tPending}ms")

                    if (uploadedCount > 0 || !uploadedItems) {
                        val tFinal = System.currentTimeMillis()
                        val refreshed = repository.uploadAllItemsToDisk()
                        Logger.log(TAG, "TIMING: uploadAllItemsToDisk #2 (final) took ${System.currentTimeMillis() - tFinal}ms, success=$refreshed")
                        if (refreshed) {
                            val fresh = withContext(Dispatchers.IO) { repository.getDiskLastModified() }
                            if (fresh != null && fresh > 0L) {
                                withContext(Dispatchers.IO) { syncInfoDao.setLastModified(fresh) }
                            }
                        }
                    } else {
                        if (uploadedItems || uploadedFolders) {
                            val fresh = withContext(Dispatchers.IO) { repository.getDiskLastModified() }
                            if (fresh != null && fresh > 0L) {
                                withContext(Dispatchers.IO) { syncInfoDao.setLastModified(fresh) }
                            }
                        }
                    }
                } else {
                    Logger.log(TAG, "syncWithDisk: pending=0 → SKIP upload (nothing changed locally)")
                }

                val shouldDownload = (diskLastModified != null) && (diskLastModified > localLastModified)

                if (shouldDownload) {
                    Logger.log(TAG, "syncWithDisk: disk modified ($diskLastModified) > local ($localLastModified) → download")

                    syncProgress.postValue(SyncProgress(SyncPhase.DOWNLOADING, 0, 1, "Получение данных…"))

                    val tDownload = System.currentTimeMillis()
                    val downloadResult = repository.downloadDataFromDisk()
                    Logger.log(TAG, "TIMING: downloadDataFromDisk took ${System.currentTimeMillis() - tDownload}ms")

                    syncProgress.postValue(SyncProgress(SyncPhase.DOWNLOADING, 1, 1, "Слияние данных…"))

                    val tMerge = System.currentTimeMillis()
                    mergeData(
                        downloadResult.folders,
                        downloadResult.items,
                        downloadResult.foldersError,
                        downloadResult.itemsError
                    )
                    Logger.log(TAG, "TIMING: mergeData took ${System.currentTimeMillis() - tMerge}ms")

                    downloadedCount = downloadResult.folders.size + downloadResult.items.size

                    if (diskLastModified != null && diskLastModified > 0L) {
                        withContext(Dispatchers.IO) { syncInfoDao.setLastModified(diskLastModified) }
                    }
                } else {
                    if (diskLastModified == null) {
                        Logger.log(TAG, "syncWithDisk: disk modified unknown (network error) → SKIP download")
                    } else {
                        Logger.log(TAG, "syncWithDisk: disk not modified since last sync → SKIP download")
                    }
                }

                val nothingChanged = (pendingCount == 0)
                    && (diskLastModified != null)
                    && (diskLastModified <= localLastModified)

                val tPhotoBlock = System.currentTimeMillis()
                if (nothingChanged) {
                    Logger.log(TAG, "syncWithDisk: nothing changed → SKIP all photo checks")
                } else {
                    val tUploadImages = System.currentTimeMillis()
                    uploadUnsyncedImages()
                    Logger.log(TAG, "TIMING: uploadUnsyncedImages took ${System.currentTimeMillis() - tUploadImages}ms")

                    val tUploadFolderImages = System.currentTimeMillis()
                    uploadUnsyncedFolderImages()
                    Logger.log(TAG, "TIMING: uploadUnsyncedFolderImages took ${System.currentTimeMillis() - tUploadFolderImages}ms")

                    val tSyncImages = System.currentTimeMillis()
                    syncImages()
                    Logger.log(TAG, "TIMING: syncImages took ${System.currentTimeMillis() - tSyncImages}ms")

                    val tSyncFolderImages = System.currentTimeMillis()
                    syncFolderImages()
                    Logger.log(TAG, "TIMING: syncFolderImages took ${System.currentTimeMillis() - tSyncFolderImages}ms")
                }
                Logger.log(TAG, "TIMING: photo block total took ${System.currentTimeMillis() - tPhotoBlock}ms")

                finalizeSync(uploadedCount, downloadedCount, startedAt, localItemsCount, localFoldersCount)

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

    private suspend fun finalizeSync(
        uploadedCount: Int,
        downloadedCount: Int,
        startedAt: Long,
        localItemsCount: Int,
        localFoldersCount: Int
    ) {
        val tPendingCount = System.currentTimeMillis()
        val pendingCount = syncQueueDao.getPendingCount()
        Logger.log(TAG, "TIMING: finalizeSync getPendingCount took ${System.currentTimeMillis() - tPendingCount}ms")

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

        val tLoad = System.currentTimeMillis()
        loadContents()
        Logger.log(TAG, "TIMING: finalizeSync loadContents took ${System.currentTimeMillis() - tLoad}ms")

        val elapsed = System.currentTimeMillis() - startedAt
        Logger.log(
            TAG,
            "Sync finished in ${elapsed}ms: uploaded=$uploadedCount, downloaded=$downloadedCount, " +
                "pending=$pendingCount, localItems=$localItemsCount, localFolders=$localFoldersCount"
        )

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
    }

    private fun notifySyncResult(message: String) {
        syncResultMessage.postValue(message)
        applicationScope.launch {
            delay(2000)
            syncResultMessage.postValue(null)
        }
    }

    private suspend fun uploadUnsyncedImages() {
        val t0 = System.currentTimeMillis()

        if (!repository.isNetworkAvailable()) {
            Logger.log(TAG, "uploadUnsyncedImages: network blocked, skipping")
            return
        }

        val appContext = getApplication<Application>().applicationContext
        val allItems = withContext(Dispatchers.IO) { db.itemDao().getAllItemsRaw() }

        val itemsWithLocalPhoto = allItems.filter { item ->
            if (!item.imageUrl.isNullOrEmpty()) return@filter false
            val f = ImageUtils.getLocalImageFile(appContext, item.id)
            f != null && f.exists() && f.length() > 0L
        }

        if (itemsWithLocalPhoto.isEmpty()) {
            Logger.log(TAG, "No new local photos to upload (all have imageUrl)")
            Logger.log(TAG, "TIMING: uploadUnsyncedImages (empty) took ${System.currentTimeMillis() - t0}ms")
            return
        }

        Logger.log(TAG, "Checking ${itemsWithLocalPhoto.size} new local photos against disk")

        var uploadedCount = 0
        var skippedCount = 0
        val total = itemsWithLocalPhoto.size

        itemsWithLocalPhoto.forEachIndexed { index, item ->
            val existsOnDisk = repository.itemImageExistsOnDisk(item.id)
            if (existsOnDisk == true) {
                val updated = item.copy(imageUrl = "images/${item.id}.jpg")
                withContext(Dispatchers.IO) { db.itemDao().updateItem(updated) }
                skippedCount++
                return@forEachIndexed
            }

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
                    uploadedCount++
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to upload image ${item.id}: ${e.message}")
            }
        }
        Logger.log(TAG, "Images upload: uploaded=$uploadedCount, skipped(exists on disk)=$skippedCount, total=$total")
        Logger.log(TAG, "TIMING: uploadUnsyncedImages took ${System.currentTimeMillis() - t0}ms")
    }

    private suspend fun uploadUnsyncedFolderImages() {
        val t0 = System.currentTimeMillis()

        if (!repository.isNetworkAvailable()) {
            Logger.log(TAG, "uploadUnsyncedFolderImages: network blocked, skipping")
            return
        }

        val appContext = getApplication<Application>().applicationContext
        val allFolders = withContext(Dispatchers.IO) { db.folderDao().getAllFolders() }

        val foldersWithLocalIcon = allFolders.filter { folder ->
            if (!folder.iconUrl.isNullOrEmpty()) return@filter false
            val f = ImageUtils.getLocalImageFile(appContext, "folder_${folder.id}")
            f != null && f.exists() && f.length() > 0L
        }

        if (foldersWithLocalIcon.isEmpty()) {
            Logger.log(TAG, "No new local folder icons to upload (all have iconUrl)")
            Logger.log(TAG, "TIMING: uploadUnsyncedFolderImages (empty) took ${System.currentTimeMillis() - t0}ms")
            return
        }

        Logger.log(TAG, "Checking ${foldersWithLocalIcon.size} new local folder icons against disk")

        var uploadedCount = 0
        var skippedCount = 0
        val total = foldersWithLocalIcon.size

        foldersWithLocalIcon.forEachIndexed { index, folder ->
            val existsOnDisk = repository.folderImageExistsOnDisk(folder.id)
            if (existsOnDisk == true) {
                val updated = folder.copy(iconUrl = "folder_${folder.id}.jpg")
                withContext(Dispatchers.IO) { db.folderDao().updateFolder(updated) }
                skippedCount++
                return@forEachIndexed
            }

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
                    withContext(Dispatchers.IO) { db.folderDao().updateFolder(updated) }
                    uploadedCount++
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to upload folder image ${folder.id}: ${e.message}")
            }
        }
        Logger.log(TAG, "Folder icons upload: uploaded=$uploadedCount, skipped(exists on disk)=$skippedCount, total=$total")
        Logger.log(TAG, "TIMING: uploadUnsyncedFolderImages took ${System.currentTimeMillis() - t0}ms")
    }

    private suspend fun syncImages() {
        if (!repository.isNetworkAvailable()) {
            Logger.log(TAG, "syncImages: network blocked, skipping")
            return
        }

        val appContext = getApplication<Application>().applicationContext
        val allItems = withContext(Dispatchers.IO) { db.itemDao().getAllItemsRaw() }
        val itemsToDownload = allItems.filter { item ->
            if (item.imageUrl.isNullOrEmpty()) return@filter false
            val f = ImageUtils.getLocalImageFile(appContext, item.id)
            f == null || !f.exists() || f.length() == 0L
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
        if (!repository.isNetworkAvailable()) {
            Logger.log(TAG, "syncFolderImages: network blocked, skipping")
            return
        }

        val appContext = getApplication<Application>().applicationContext
        val allFolders = withContext(Dispatchers.IO) { db.folderDao().getAllFolders() }
        val foldersToDownload = allFolders.filter { folder ->
            if (folder.iconUrl.isNullOrEmpty()) return@filter false
            val f = ImageUtils.getLocalImageFile(appContext, "folder_${folder.id}")
            f == null || !f.exists() || f.length() == 0L
        }

        if (foldersToDownload.isEmpty()) {
            Logger.log(TAG, "No folder images to download")
            return
        }

        Logger.log(TAG, "Checking ${foldersToDownload.size} folder icons to download")

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
        } catch (e: Exception) {
            Logger.log(TAG, "ensureValidToken: network error, assuming token ok: ${e.message}")
            true
        }
    }

    private suspend fun mergeData(
        diskFolders: List<FolderEntity>,
        diskItems: List<ItemEntity>,
        foldersError: Boolean,
        itemsError: Boolean
    ) {
        withContext(Dispatchers.IO) {
            val localFoldersCount = db.folderDao().getAllFolders().size
            val localItemsCount = db.itemDao().getAllItemsRaw().size

            if (foldersError && localFoldersCount > 0) {
                Logger.log(TAG, "mergeData: folders download error (local=$localFoldersCount) — SKIP to protect data")
            } else if (diskFolders.isEmpty() && localFoldersCount > 0) {
                Logger.log(TAG, "mergeData: disk folders empty but local has $localFoldersCount — SKIP to protect data")
            } else {
                diskFolders.forEach { diskFolder ->
                    val local = db.folderDao().getFolderById(diskFolder.id)
                    if (local == null) {
                        db.folderDao().insertFolder(diskFolder)
                    } else if (diskFolder.updatedAt > local.updatedAt) {
                        val merged = diskFolder.copy(
                            iconUrl = diskFolder.iconUrl ?: local.iconUrl,
                            parentItemId = diskFolder.parentItemId ?: local.parentItemId
                        )
                        db.folderDao().updateFolder(merged)
                    }
                }
            }

            if (itemsError && localItemsCount > 0) {
                Logger.log(TAG, "mergeData: items download error (local=$localItemsCount) — SKIP to protect data")
            } else if (diskItems.isEmpty() && localItemsCount > 0) {
                Logger.log(TAG, "mergeData: disk items empty but local has $localItemsCount — SKIP to protect data")
            } else {
                diskItems.forEach { diskItem ->
                    val local = db.itemDao().getItemById(diskItem.id)
                    if (local == null) {
                        db.itemDao().insertItem(diskItem)
                    } else if (diskItem.updatedDate > local.updatedDate) {
                        val merged = diskItem.copy(
                            imageUrl = diskItem.imageUrl ?: local.imageUrl,
                            parentItemId = diskItem.parentItemId ?: local.parentItemId
                        )
                        db.itemDao().updateItem(merged)
                    }
                }
            }

            val diskLastModified = repository.getDiskLastModified()
            val localLastModified = syncInfoDao.getLastModified()
            if (diskLastModified != null && diskLastModified > localLastModified) {
                syncInfoDao.setLastModified(diskLastModified)
            }
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

                val tEntry = System.currentTimeMillis()
                when (entry.entityType) {
                    "folder" -> applyFolderChange(entry)
                    "item" -> applyItemChange(entry)
                }
                Logger.log(TAG, "TIMING: applyChange for ${entry.entityType}/${entry.entityId} took ${System.currentTimeMillis() - tEntry}ms")

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
                val t2 = System.currentTimeMillis()
                uploadItemImageIfExists(it)
                Logger.log(TAG, "TIMING: applyItemChange uploadItemImageIfExists took ${System.currentTimeMillis() - t2}ms")
            }
            "update" -> db.itemDao().getItemById(entry.entityId)?.let {
                val fresh = it.copy(updatedDate = System.currentTimeMillis())
                db.itemDao().updateItem(fresh)
                val t2 = System.currentTimeMillis()
                uploadItemImageIfExists(fresh)
                Logger.log(TAG, "TIMING: applyItemChange uploadItemImageIfExists took ${System.currentTimeMillis() - t2}ms")
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
                    enqueue("item", itemId, "update", item.parentId, item.parentItemId)

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
                    enqueue("item", itemId, "update", item.parentId, item.parentItemId)

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

    /**
     * B-5-FIX-2: Архивирует предмет.
     * Если у предмета есть вложенные — архивация БЛОКИРУЕТСЯ,
     * вызывается onBlocked с готовым сообщением для диалога.
     * Если детей нет — архивация выполняется, onBlocked не вызывается.
     *
     * @param onBlocked вызывается на главном потоке. Аргумент — текст сообщения.
     */
    fun archiveItem(
        itemId: String,
        reason: String,
        note: String?,
        onBlocked: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                val blockMsg = buildArchiveBlockMessage(itemId)
                if (blockMsg != null) {
                    Logger.log(TAG, "archiveItem: BLOCKED for $itemId (has children)")
                    withContext(Dispatchers.Main) { onBlocked?.invoke(blockMsg) }
                    return@launch
                }

                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    db.itemDao().archiveItem(itemId, reason, System.currentTimeMillis(), note)
                    enqueue("item", itemId, "update", item.parentId, item.parentItemId)

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
                if (item == null) {
                    Logger.log(TAG, "unarchiveItem: item not found $itemId")
                    return@launch
                }

                val originalId = item.originalId

                if (originalId == null) {
                    db.itemDao().unarchiveItem(itemId, System.currentTimeMillis())
                    enqueue("item", itemId, "update", item.parentId, item.parentItemId)

                    val history = HistoryEntry(
                        itemId = itemId,
                        action = "unarchive",
                        oldValue = "В архиве",
                        newValue = "Вернули в базу",
                        changedBy = currentUser
                    )
                    db.historyDao().insertEntry(history)

                    Logger.log(TAG, "unarchiveItem: обычный возврат $itemId")
                } else {
                    val original = db.itemDao().getItemById(originalId)

                    if (original != null && !original.isArchived) {
                        val newQty = original.quantity + item.quantity
                        val updatedOriginal = original.copy(
                            quantity = newQty,
                            updatedDate = System.currentTimeMillis(),
                            updatedBy = currentUser
                        )
                        updatedOriginal.computeExpiryFields()
                        db.itemDao().updateItem(updatedOriginal)
                        enqueue("item", originalId, "update", original.parentId, original.parentItemId)

                        db.itemDao().deleteItemById(itemId)
                        enqueue("item", itemId, "delete", item.parentId, item.parentItemId)

                        val appContext = getApplication<Application>().applicationContext
                        ImageUtils.deleteLocalImage(appContext, itemId)

                        val history = HistoryEntry(
                            itemId = originalId,
                            action = "unarchive_part",
                            oldValue = "${original.quantity} шт.",
                            newValue = "$newQty шт. (возвращено ${item.quantity})",
                            changedBy = currentUser
                        )
                        db.historyDao().insertEntry(history)

                        Logger.log(TAG, "unarchiveItem: часть возвращена в оригинал $originalId, qty=$newQty")
                    } else {
                        val restored = item.copy(
                            isArchived = false,
                            archivedReason = null,
                            archivedDate = null,
                            archivedNote = null,
                            originalId = null,
                            updatedDate = System.currentTimeMillis(),
                            updatedBy = currentUser
                        )
                        restored.computeExpiryFields()
                        db.itemDao().updateItem(restored)
                        enqueue("item", itemId, "update", restored.parentId, restored.parentItemId)

                        val history = HistoryEntry(
                            itemId = itemId,
                            action = "unarchive",
                            oldValue = "В архиве",
                            newValue = "Вернули в базу (оригинал не найден)",
                            changedBy = currentUser
                        )
                        db.historyDao().insertEntry(history)

                        Logger.log(TAG, "unarchiveItem: оригинал не найден, часть становится активной $itemId")
                    }
                }

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error unarchiving item: ${e.message}", e)
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
    // СПИСАНИЕ ЧАСТИ КОЛИЧЕСТВА (write-off → архив)
    // ============================================================
    /**
     * B-5-FIX-2: Списывает предмет (частично или полностью).
     *
     * Логика:
     *  - Частичное списание (count < quantity) → РАЗРЕШЕНО всегда.
     *    Предмет остаётся, дети остаются.
     *  - Полное списание (count == quantity) → это АРХИВАЦИЯ.
     *    Если у предмета есть дети → БЛОКИРУЕТСЯ, вызывается onBlocked.
     *
     * @param onBlocked вызывается на главном потоке. Аргумент — текст сообщения.
     */
    fun writeOffItem(
        itemId: String,
        count: Int,
        reason: String?,
        note: String?,
        onBlocked: ((String) -> Unit)? = null
    ) {
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

                // B-5-FIX-2: полное списание = архивация → проверяем детей
                val isFullWriteOff = (count == item.quantity)
                if (isFullWriteOff) {
                    val blockMsg = buildArchiveBlockMessage(itemId)
                    if (blockMsg != null) {
                        Logger.log(TAG, "writeOffItem: BLOCKED for $itemId (full write-off = archive, has children)")
                        withContext(Dispatchers.Main) { onBlocked?.invoke(blockMsg) }
                        return@launch
                    }
                }

                val now = System.currentTimeMillis()
                val appContext = getApplication<Application>().applicationContext
                val reasonKey = reason ?: "used_up"

                if (count == item.quantity) {
                    db.itemDao().archiveItem(itemId, reasonKey, now, note)
                    enqueue("item", itemId, "update", item.parentId, item.parentItemId)

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
                    val newId = java.util.UUID.randomUUID().toString()

                    val archivedPart = item.copy(
                        id = newId,
                        quantity = count,
                        isArchived = true,
                        archivedReason = reasonKey,
                        archivedDate = now,
                        archivedNote = note,
                        originalId = itemId,
                        imageUrl = null,
                        addedDate = now,
                        updatedDate = now,
                        updatedBy = currentUser
                    )
                    archivedPart.computeExpiryFields()

                    withContext(Dispatchers.IO) {
                        db.itemDao().insertItem(archivedPart)
                    }
                    enqueue("item", newId, "create", item.parentId, item.parentItemId)

                    try {
                        val localFile = ImageUtils.getLocalImageFile(appContext, item.id)
                        if (localFile != null && localFile.exists()) {
                            val bytes = localFile.readBytes()
                            ImageUtils.saveImageLocally(appContext, newId, bytes)
                            Logger.log(TAG, "writeOffItem: photo copied to $newId")
                        }
                    } catch (e: Exception) {
                        Logger.log(TAG, "writeOffItem: failed to copy photo: ${e.message}")
                    }

                    val newQty = item.quantity - count
                    if (newQty == 0) {
                        withContext(Dispatchers.IO) {
                            db.itemDao().deleteItemById(itemId)
                        }
                        enqueue("item", itemId, "delete", item.parentId, item.parentItemId)
                        ImageUtils.deleteLocalImage(appContext, itemId)

                        val history = HistoryEntry(
                            itemId = newId,
                            action = "write_off",
                            oldValue = "Отделено от «${item.name}»",
                            newValue = "Списано $count шт. (оригинал удалён, ${getArchiveReasonText(reasonKey)})",
                            changedBy = currentUser
                        )
                        db.historyDao().insertEntry(history)

                        Logger.log(TAG, "writeOffItem: partial → original deleted (qty was $count)")
                    } else {
                        val updatedOriginal = item.copy(
                            quantity = newQty,
                            updatedDate = now,
                            updatedBy = currentUser
                        )
                        updatedOriginal.computeExpiryFields()
                        withContext(Dispatchers.IO) {
                            db.itemDao().updateItem(updatedOriginal)
                        }
                        enqueue("item", itemId, "update", item.parentId, item.parentItemId)

                        val historyNew = HistoryEntry(
                            itemId = newId,
                            action = "write_off",
                            oldValue = "Отделено от «${item.name}»",
                            newValue = "Списано $count шт. (${getArchiveReasonText(reasonKey)}${if (!note.isNullOrEmpty()) ": $note" else ""})",
                            changedBy = currentUser
                        )
                        db.historyDao().insertEntry(historyNew)

                        val historyOld = HistoryEntry(
                            itemId = itemId,
                            action = "write_off_part",
                            oldValue = "${item.quantity} шт.",
                            newValue = "$newQty шт. (списано $count)",
                            changedBy = currentUser
                        )
                        db.historyDao().insertEntry(historyOld)

                        Logger.log(TAG, "writeOffItem: partial → new archive entry $newId ($count шт.), original=$newQty шт.")
                    }
                }

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error writing off item: ${e.message}", e)
            }
        }
    }

    // ============================================================
    // РЕВИЗИЯ ПРЕДМЕТА
    // ============================================================
    fun markRevision(itemId: String) {
        Logger.log(TAG, "markRevision: itemId=$itemId")
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item == null) {
                    Logger.log(TAG, "markRevision: item not found: $itemId")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val updated = item.copy(
                    lastRevisionDate = now,
                    updatedDate = now,
                    updatedBy = currentUser
                )
                updated.computeExpiryFields()
                db.itemDao().updateItem(updated)
                enqueue("item", itemId, "update", item.parentId, item.parentItemId)

                val history = HistoryEntry(
                    itemId = itemId,
                    action = "revision",
                    oldValue = item.lastRevisionDate?.let { formatDateShort(it) } ?: "не проводилась",
                    newValue = formatDateShort(now),
                    changedBy = currentUser
                )
                db.historyDao().insertEntry(history)

                Logger.log(TAG, "markRevision: done for $itemId")

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error marking revision: ${e.message}", e)
            }
        }
    }

    private fun formatDateShort(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ ЧАСТИ КОЛИЧЕСТВА (split + move)
    // ============================================================
    fun splitAndMoveItem(itemId: String, count: Int, newParentId: String?) {
        Logger.log(TAG, "splitAndMoveItem: itemId=$itemId, count=$count, newParentId=$newParentId")
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item == null) {
                    Logger.log(TAG, "splitAndMoveItem: item not found: $itemId")
                    return@launch
                }

                if (count <= 0) {
                    Logger.log(TAG, "splitAndMoveItem: count <= 0, ignoring")
                    return@launch
                }

                if (count >= item.quantity) {
                    Logger.log(TAG, "splitAndMoveItem: count >= quantity → full move")
                    moveItem(itemId, newParentId)
                    return@launch
                }

                val appContext = getApplication<Application>().applicationContext
                val now = System.currentTimeMillis()
                val newId = java.util.UUID.randomUUID().toString()

                val copy = item.copy(
                    id = newId,
                    quantity = count,
                    parentId = newParentId,
                    addedDate = now,
                    updatedDate = now,
                    updatedBy = currentUser
                )
                copy.computeExpiryFields()

                withContext(Dispatchers.IO) {
                    db.itemDao().insertItem(copy)
                }
                enqueue("item", newId, "create", newParentId, null)

                try {
                    val localFile = ImageUtils.getLocalImageFile(appContext, item.id)
                    if (localFile != null && localFile.exists()) {
                        val bytes = localFile.readBytes()
                        ImageUtils.saveImageLocally(appContext, newId, bytes)
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "splitAndMoveItem: failed to copy photo: ${e.message}")
                }

                val newQty = item.quantity - count
                val updated = item.copy(
                    quantity = newQty,
                    updatedDate = now,
                    updatedBy = currentUser
                )
                updated.computeExpiryFields()
                withContext(Dispatchers.IO) {
                    db.itemDao().updateItem(updated)
                }
                enqueue("item", itemId, "update", item.parentId, item.parentItemId)

                val folderName = getFolderNameById(newParentId)

                withContext(Dispatchers.IO) {
                    db.historyDao().insertEntry(
                        HistoryEntry(
                            itemId = newId,
                            action = "split_in",
                            oldValue = "Отделено от «${item.name}»",
                            newValue = "$count шт. → $folderName",
                            changedBy = currentUser
                        )
                    )
                    db.historyDao().insertEntry(
                        HistoryEntry(
                            itemId = itemId,
                            action = "split_out",
                            oldValue = "${item.quantity} шт.",
                            newValue = "$newQty шт. (отделено $count → $folderName)",
                            changedBy = currentUser
                        )
                    )
                }

                Logger.log(TAG, "splitAndMoveItem: done. new=$newId ($count шт.), original=$newQty шт.")

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error in splitAndMoveItem: ${e.message}", e)
            }
        }
    }

    private suspend fun getFolderNameById(folderId: String?): String {
        if (folderId == null) return "Корень"
        return try {
            db.folderDao().getFolderById(folderId)?.name ?: "Корень"
        } catch (e: Exception) {
            "Корень"
        }
    }

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

                    enqueue("item", copy.id, "create", copy.parentId, copy.parentItemId)

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
            parentItemId = null,
            createdBy = currentUser,
            path = name
        )

        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.folderDao().insertFolder(folder) }
            enqueue("folder", folder.id, "create", currentFolderId, null)
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
                    enqueue("folder", folderId, "update", folder.parentId, folder.parentItemId)
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
                enqueue("folder", folderId, "delete", folder?.parentId, folder?.parentItemId)
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleting folder: ${e.message}")
            }
        }
    }

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ ПАПОК И ПРЕДМЕТОВ
    // ============================================================

    private suspend fun isFolderDescendantOf(folderId: String, potentialAncestorId: String): Boolean {
        if (folderId == potentialAncestorId) return true
        var currentId: String? = potentialAncestorId
        var depth = 0
        while (currentId != null && depth < 50) {
            if (currentId == folderId) return true
            val folder = db.folderDao().getFolderById(currentId) ?: break
            currentId = folder.parentId
            depth++
        }
        return false
    }

    private suspend fun isItemDescendantOf(itemId: String, potentialAncestorItemId: String): Boolean {
        if (itemId == potentialAncestorItemId) return true
        var currentId: String? = potentialAncestorItemId
        var depth = 0
        while (currentId != null && depth < 50) {
            if (currentId == itemId) return true
            val item = db.itemDao().getItemById(currentId) ?: break
            currentId = item.parentItemId
            depth++
        }
        return false
    }

    fun moveFolder(folderId: String, newParentId: String?) {
        viewModelScope.launch {
            try {
                val folder = db.folderDao().getFolderById(folderId)
                if (folder != null) {
                    if (newParentId != null && isFolderDescendantOf(folderId, newParentId)) {
                        Logger.log(TAG, "moveFolder: CYCLE detected, aborting (folder=$folderId, newParent=$newParentId)")
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                getApplication(),
                                "Нельзя переместить папку в себя или в свою подпапку",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }

                    val updated = folder.copy(
                        parentId = newParentId,
                        parentItemId = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    db.folderDao().updateFolder(updated)
                    enqueue("folder", folderId, "update", newParentId, null)
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
                        parentItemId = null,
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = currentUser
                    )
                    updated.computeExpiryFields()
                    db.itemDao().updateItem(updated)
                    enqueue("item", itemId, "update", newParentId, null)
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
                    enqueue("item", itemId, "update", item.parentId, item.parentItemId)
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
                enqueue("item", item.id, "update", item.parentId, item.parentItemId)
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
                    enqueue("item", itemId, "delete", item.parentId, item.parentItemId)
                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleting item: ${e.message}")
            }
        }
    }

    fun deleteItemSafely(itemId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val children = repository.countChildren(itemId)
                val total = children.first + children.second

                if (total > 0) {
                    val msg = "Нельзя удалить: вложенных ${children.first} папок, ${children.second} предметов"
                    Logger.log(TAG, "deleteItemSafely: BLOCKED for $itemId — $msg")
                    withContext(Dispatchers.Main) { onError(msg) }
                    return@launch
                }

                val item = db.itemDao().getItemById(itemId)
                if (item == null) {
                    withContext(Dispatchers.Main) { onError("Предмет не найден") }
                    return@launch
                }

                db.itemDao().deleteItem(item)
                val appContext = getApplication<Application>().applicationContext
                ImageUtils.deleteLocalImage(appContext, itemId)
                enqueue("item", itemId, "delete", item.parentId, item.parentItemId)

                Logger.log(TAG, "deleteItemSafely: deleted $itemId")
                withContext(Dispatchers.Main) { onSuccess() }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleteItemSafely: ${e.message}")
                withContext(Dispatchers.Main) { onError("Ошибка удаления: ${e.message}") }
            }
        }
    }

    fun deleteFolderSafely(folderId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val folderCount = withContext(Dispatchers.IO) {
                    db.folderDao().getSubfolderCountInFolder(folderId)
                }
                val itemCount = withContext(Dispatchers.IO) {
                    db.folderDao().getItemCountInFolder(folderId)
                }
                val total = folderCount + itemCount

                if (total > 0) {
                    val msg = "Нельзя удалить: внутри $folderCount подпапок, $itemCount предметов"
                    Logger.log(TAG, "deleteFolderSafely: BLOCKED for $folderId — $msg")
                    withContext(Dispatchers.Main) { onError(msg) }
                    return@launch
                }

                val folder = db.folderDao().getFolderById(folderId)
                if (folder == null) {
                    withContext(Dispatchers.Main) { onError("Папка не найдена") }
                    return@launch
                }

                db.folderDao().deleteFolderById(folderId)
                enqueue("folder", folderId, "delete", folder.parentId, folder.parentItemId)

                Logger.log(TAG, "deleteFolderSafely: deleted $folderId")
                withContext(Dispatchers.Main) { onSuccess() }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleteFolderSafely: ${e.message}")
                withContext(Dispatchers.Main) { onError("Ошибка удаления: ${e.message}") }
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
