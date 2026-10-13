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
import com.family.base.data.repository.TaskRepository
import com.family.base.data.sync.ChangelogParser
import com.family.base.data.sync.SyncManager
import com.family.base.data.sync.SyncManagerApi
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
    private val taskRepository = TaskRepository(application.applicationContext, db)
    private val tokenStorage = TokenStorage(application)
    private val lockDao = db.lockDao()
    private val syncInfoDao = db.syncInfoDao()
    private val settingsDao = db.settingsDao()

    // 🆕 A1: делегаты
    private val historyViewModel = HistoryViewModel(db) { currentUser }

    private val catalogViewModel: CatalogViewModel by lazy {
        CatalogViewModel(
            db = db,
            repository = repository,
            historyViewModel = historyViewModel,
            currentUserProvider = { currentUser },
            currentFolderIdProvider = { currentFolderId },
            appContextProvider = { getApplication<Application>().applicationContext },
            onContentsChanged = { loadContents() }
        )
    }

    // 🆕 v16 Шаг 3.4: changelog-синхронизация
    private val syncManagerApi = SyncManagerApi()
    private val changelogParser by lazy {
        ChangelogParser(db, com.family.base.data.local.DeviceIdProvider.get(application.applicationContext))
    }
    private val syncManager by lazy {
        SyncManager(db, changelogParser, syncManagerApi)
    }

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
    // ИСТОРИЯ (делегат HistoryViewModel)
    // ============================================================

    private suspend fun writeHistory(
        itemId: String,
        itemName: String?,
        action: String,
        oldValue: String?,
        newValue: String?
    ) {
        historyViewModel.writeHistory(itemId, itemName, action, oldValue, newValue)
    }

    private suspend fun migrateOldHistoryEntries() {
        historyViewModel.migrateOldHistoryEntries()
    }

    // ============================================================
    // СТАРТОВАЯ ИНИЦИАЛИЗАЦИЯ
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
            taskRepository.ensureSystemFolder(currentUser)
            Logger.log(TAG, "checkFirstLaunch: system folder 'home_tasks' ensured")
        } catch (e: Exception) {
            Logger.log(TAG, "Error ensuring system folder: ${e.message}")
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

        try {
            migrateOldHistoryEntries()
        } catch (e: Exception) {
            Logger.log(TAG, "Error migrating old history entries: ${e.message}", e)
        }

        try {
            val lm = syncInfoDao.getLastModified()
            if (lm >= 9999999999999L) {
                Logger.log(TAG, "RESET fake last_modified ($lm) → 0")
                syncInfoDao.setLastModified(0L)
            } else {
                Logger.log(TAG, "last_modified OK: $lm")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error resetting last_modified: ${e.message}")
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
    // ПРЕДМЕТЫ — ДЕЛЕГИРУЮТСЯ В CatalogViewModel
    // ============================================================

    fun createItem(item: ItemEntity, imageBytes: ByteArray? = null) {
        viewModelScope.launch {
            catalogViewModel.createItem(item, imageBytes)
        }
    }

    suspend fun createItemsBatch(items: List<ItemEntity>, folderId: String?): Int {
        val n = catalogViewModel.createItemsBatch(items, folderId)
        if (n > 0) syncStatus.postValue(SyncStatus.PENDING)
        return n
    }

    fun getChildrenCount(itemId: String, callback: (Pair<Int, Int>) -> Unit) {
        viewModelScope.launch {
            try {
                val result = catalogViewModel.getChildrenCount(itemId)
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback(Pair(0, 0)) }
            }
        }
    }

    fun detachAllChildren(itemId: String, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = catalogViewModel.detachAllChildren(itemId)
            withContext(Dispatchers.Main) { onDone(count) }
        }
    }

    fun detachAllChildrenAndDelete(itemId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = catalogViewModel.detachAllChildrenAndDelete(itemId)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun detachAllChildrenAndArchive(itemId: String, reason: String, note: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = catalogViewModel.detachAllChildrenAndArchive(itemId, reason, note)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun getNestedContent(
        parentItemId: String,
        callback: (List<FolderEntity>, List<ItemEntity>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val (folders, items) = catalogViewModel.getNestedContent(parentItemId)
                withContext(Dispatchers.Main) { callback(folders, items) }
            } catch (e: Exception) {
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
            val id = catalogViewModel.createFolderInItem(name, parentItemId)
            withContext(Dispatchers.Main) { onDone(id) }
        }
    }

    fun getFolderChildrenCount(folderId: String, callback: (Pair<Int, Int>) -> Unit) {
        viewModelScope.launch {
            try {
                val result = catalogViewModel.getFolderChildrenCount(folderId)
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback(Pair(0, 0)) }
            }
        }
    }

    fun detachAllFolderChildren(folderId: String, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val total = catalogViewModel.detachAllFolderChildren(folderId)
            withContext(Dispatchers.Main) { onDone(total) }
        }
    }

    fun detachAllFolderChildrenAndDelete(folderId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = catalogViewModel.detachAllFolderChildrenAndDelete(folderId)
            withContext(Dispatchers.Main) { onDone(ok) }
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
                val outboxPendingCount = withContext(Dispatchers.IO) { db.outboxDao().getPendingCount() }
                val localLastModified = withContext(Dispatchers.IO) { syncInfoDao.getLastModified() }
                val diskLastModified: Long? = withContext(Dispatchers.IO) { repository.getDiskLastModified() }
                Logger.log(TAG, "TIMING: collect metrics took ${System.currentTimeMillis() - tMetrics}ms")

                val isFirstLaunch = (localItemsCount == 0 && localFoldersCount == 0)

                Logger.log(
                    TAG,
                    "syncWithDisk: local items=$localItemsCount, folders=$localFoldersCount, " +
                        "outboxPending=$outboxPendingCount, localModified=$localLastModified, " +
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
                        downloadResult.history,
                        downloadResult.tasks,
                        downloadResult.foldersError,
                        downloadResult.itemsError,
                        downloadResult.historyError,
                        downloadResult.tasksError
                    )
                    downloadedCount = downloadResult.folders.size +
                        downloadResult.items.size +
                        downloadResult.history.size +
                        downloadResult.tasks.size

                    val tPhotoBlock = System.currentTimeMillis()
                    syncImages()
                    syncFolderImages()
                    Logger.log(TAG, "TIMING: photo block (first launch) took ${System.currentTimeMillis() - tPhotoBlock}ms")

                    if (diskLastModified != null && diskLastModified > 0L) {
                        withContext(Dispatchers.IO) { syncInfoDao.setLastModified(diskLastModified) }
                    }

                    try {
                        syncProgress.postValue(
                            SyncProgress(SyncPhase.SENDING, 0, 1, "Синхронизация изменений (changelog)…")
                        )
                        val changelogResult = syncManager.syncChangelogs()
                        Logger.log(
                            TAG,
                            "syncWithDisk: changelog (first launch) DONE — pushed=${changelogResult.pushedBatches}/${changelogResult.pushedEntries}, " +
                                "pulled=${changelogResult.pulledBatches} applied=${changelogResult.pulledEntriesApplied} " +
                                "skipped=${changelogResult.pulledEntriesSkipped} errors=${changelogResult.pulledErrors}"
                        )
                        uploadedCount += changelogResult.pushedEntries
                        downloadedCount += changelogResult.pulledEntriesApplied
                    } catch (e: Exception) {
                        Logger.log(TAG, "syncWithDisk: changelog (first launch) error: ${e.message}", e)
                    }

                    finalizeSync(uploadedCount, downloadedCount, startedAt, localItemsCount, localFoldersCount)
                    return@launch
                }

                if (outboxPendingCount > 0) {
                    Logger.log(TAG, "syncWithDisk: outboxPending=$outboxPendingCount → changelog push")

                    syncProgress.postValue(
                        SyncProgress(
                            SyncPhase.SENDING,
                            0,
                            1,
                            "Отправка изменений ($outboxPendingCount в очереди)…"
                        )
                    )
                } else {
                    Logger.log(TAG, "syncWithDisk: outboxPending=0 → ничего не менялось локально")
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
                        downloadResult.history,
                        downloadResult.tasks,
                        downloadResult.foldersError,
                        downloadResult.itemsError,
                        downloadResult.historyError,
                        downloadResult.tasksError
                    )
                    Logger.log(TAG, "TIMING: mergeData took ${System.currentTimeMillis() - tMerge}ms")

                    downloadedCount = downloadResult.folders.size +
                        downloadResult.items.size +
                        downloadResult.history.size +
                        downloadResult.tasks.size

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

                val tPhotoBlock = System.currentTimeMillis()
                if (outboxPendingCount > 0) {
                    val tUploadImages = System.currentTimeMillis()
                    uploadUnsyncedImages()
                    Logger.log(TAG, "TIMING: uploadUnsyncedImages took ${System.currentTimeMillis() - tUploadImages}ms")

                    val tUploadFolderImages = System.currentTimeMillis()
                    uploadUnsyncedFolderImages()
                    Logger.log(TAG, "TIMING: uploadUnsyncedFolderImages took ${System.currentTimeMillis() - tUploadFolderImages}ms")
                }
                val tSyncImages = System.currentTimeMillis()
                syncImages()
                Logger.log(TAG, "TIMING: syncImages took ${System.currentTimeMillis() - tSyncImages}ms")

                val tSyncFolderImages = System.currentTimeMillis()
                syncFolderImages()
                Logger.log(TAG, "TIMING: syncFolderImages took ${System.currentTimeMillis() - tSyncFolderImages}ms")

                Logger.log(TAG, "TIMING: photo block total took ${System.currentTimeMillis() - tPhotoBlock}ms")

                try {
                    syncProgress.postValue(
                        SyncProgress(SyncPhase.SENDING, 0, 1, "Синхронизация изменений (changelog)…")
                    )
                    val changelogResult = syncManager.syncChangelogs()
                    Logger.log(
                        TAG,
                        "syncWithDisk: changelog DONE — pushed=${changelogResult.pushedBatches}/${changelogResult.pushedEntries}, " +
                            "pulled=${changelogResult.pulledBatches} applied=${changelogResult.pulledEntriesApplied} " +
                            "skipped=${changelogResult.pulledEntriesSkipped} errors=${changelogResult.pulledErrors}"
                    )
                    uploadedCount += changelogResult.pushedEntries
                    downloadedCount += changelogResult.pulledEntriesApplied
                } catch (e: Exception) {
                    Logger.log(TAG, "syncWithDisk: changelog error: ${e.message}", e)
                }

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
        val pendingCount = db.outboxDao().getPendingCount()
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
        diskHistory: List<HistoryEntry>,
        diskTasks: List<TaskEntity>,
        foldersError: Boolean,
        itemsError: Boolean,
        historyError: Boolean,
        tasksError: Boolean
    ) {
        withContext(Dispatchers.IO) {
            val localFoldersCount = db.folderDao().getAllFolders().size
            val localItemsCount = db.itemDao().getAllItemsRaw().size

            val diskLastModified = repository.getDiskLastModified() ?: 0L
            val localLastModified = syncInfoDao.getLastModified()
            val diskIsNewer = diskLastModified > localLastModified && diskLastModified > 0L

            // ---------- ПАПКИ ----------
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

                if (diskIsNewer) {
                    val diskFolderIds = diskFolders.map { it.id }.toSet()
                    val localOnlyFolders = db.folderDao().getAllFolders().filter { it.id !in diskFolderIds }
                    for (folder in localOnlyFolders) {
                        if (folder.isSystem) {
                            Logger.log(TAG, "mergeData: KEEPING system folder ${folder.id} '${folder.name}' (never delete)")
                            continue
                        }
                        if (folder.updatedAt < diskLastModified) {
                            Logger.log(TAG, "mergeData: DELETING local-only folder ${folder.id} '${folder.name}'")
                            db.folderDao().deleteFolderById(folder.id)
                        }
                    }
                }
            }

            // ---------- ПРЕДМЕТЫ ----------
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

                if (diskIsNewer) {
                    val appContext = getApplication<Application>().applicationContext
                    val diskItemIds = diskItems.map { it.id }.toSet()
                    val localOnlyItems = db.itemDao().getAllItemsRaw().filter { it.id !in diskItemIds }
                    for (item in localOnlyItems) {
                        if (item.updatedDate < diskLastModified) {
                            Logger.log(TAG, "mergeData: DELETING local-only item ${item.id} '${item.name}'")
                            db.itemDao().deleteItem(item)
                            ImageUtils.deleteLocalImage(appContext, item.id)
                        }
                    }
                }
            }

            // ---------- ИСТОРИЯ ----------
            if (historyError && diskHistory.isEmpty()) {
                Logger.log(TAG, "mergeData: history download error — SKIP")
            } else if (diskHistory.isEmpty()) {
                Logger.log(TAG, "mergeData: disk history empty — nothing to merge")
            } else {
                val localIds = db.historyDao().getAllIds().toSet()
                val toInsert = diskHistory.filter { it.id !in localIds }
                if (toInsert.isNotEmpty()) {
                    Logger.log(TAG, "mergeData: inserting ${toInsert.size} new history entries (local=${localIds.size}, disk=${diskHistory.size})")
                    db.historyDao().insertAll(toInsert)
                } else {
                    Logger.log(TAG, "mergeData: history already up to date (local=${localIds.size}, disk=${diskHistory.size})")
                }
            }

            // ---------- ЗАДАЧИ ----------
            if (tasksError && diskTasks.isEmpty()) {
                Logger.log(TAG, "mergeData: tasks download error — SKIP")
            } else if (diskTasks.isEmpty()) {
                Logger.log(TAG, "mergeData: disk tasks empty — nothing to merge")
            } else {
                val localTasks = db.taskDao().getAllForSync()
                val localMap = localTasks.associateBy { it.id }
                var insertedCount = 0
                var updatedCount = 0

                diskTasks.forEach { diskTask ->
                    val local = localMap[diskTask.id]
                    if (local == null) {
                        db.taskDao().insert(diskTask)
                        insertedCount++
                    } else if (diskTask.updatedAt > local.updatedAt) {
                        db.taskDao().update(diskTask)
                        updatedCount++
                    }
                }
                Logger.log(TAG, "mergeData: tasks merged (inserted=$insertedCount, updated=$updatedCount, local=${localTasks.size}, disk=${diskTasks.size})")
            }

            if (diskIsNewer) {
                syncInfoDao.setLastModified(diskLastModified)
            }
        }
    }

    // ============================================================
    // ЗАЙМ — ДЕЛЕГИРУЕТСЯ В CatalogViewModel
    // ============================================================

    fun lendItem(itemId: String, personName: String, note: String? = null) {
        viewModelScope.launch { catalogViewModel.lendItem(itemId, personName, note) }
    }

    fun returnItem(itemId: String) {
        viewModelScope.launch { catalogViewModel.returnItem(itemId) }
    }

    suspend fun getLentItems(): List<ItemEntity> = catalogViewModel.getLentItems()

    // ============================================================
    // АРХИВ — ДЕЛЕГИРУЕТСЯ В CatalogViewModel
    // ============================================================

    fun archiveItem(
        itemId: String,
        reason: String,
        note: String?,
        onBlocked: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            catalogViewModel.archiveItem(itemId, reason, note) { msg ->
                viewModelScope.launch {
                    withContext(Dispatchers.Main) { onBlocked?.invoke(msg) }
                }
            }
        }
    }

    fun unarchiveItem(itemId: String) {
        viewModelScope.launch { catalogViewModel.unarchiveItem(itemId) }
    }

    suspend fun getArchivedItems(): List<ItemEntity> = catalogViewModel.getArchivedItems()

    suspend fun getArchivedItemsByReason(reason: String): List<ItemEntity> =
        catalogViewModel.getArchivedItemsByReason(reason)

    // ============================================================
    // СПИСАНИЕ — ДЕЛЕГИРУЕТСЯ В CatalogViewModel
    // ============================================================

    fun writeOffItem(
        itemId: String,
        count: Int,
        reason: String?,
        note: String?,
        onBlocked: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            catalogViewModel.writeOffItem(itemId, count, reason, note) { msg ->
                viewModelScope.launch {
                    withContext(Dispatchers.Main) { onBlocked?.invoke(msg) }
                }
            }
        }
    }

    // ============================================================
    // РЕВИЗИЯ — ДЕЛЕГИРУЕТСЯ В CatalogViewModel
    // ============================================================

    fun markRevision(itemId: String) {
        viewModelScope.launch { catalogViewModel.markRevision(itemId) }
    }

    // ============================================================
    // РАЗДЕЛЕНИЕ / ПЕРЕМЕЩЕНИЕ — ДЕЛЕГИРУЕТСЯ В CatalogViewModel
    // ============================================================

    fun splitAndMoveItem(
        itemId: String,
        count: Int,
        newParentId: String?,
        newParentItemId: String? = null
    ) {
        viewModelScope.launch {
            catalogViewModel.splitAndMoveItem(itemId, count, newParentId, newParentItemId)
        }
    }

    fun copyItem(itemId: String) {
        viewModelScope.launch { catalogViewModel.copyItem(itemId) }
    }

    // ============================================================
    // ПАПКИ — ДЕЛЕГИРУЕТСЯ В CatalogViewModel
    // ============================================================

    fun createFolder(name: String) {
        viewModelScope.launch { catalogViewModel.createFolder(name) }
    }

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch { catalogViewModel.renameFolder(folderId, newName) }
    }

    fun getFolderStats(folderId: String, callback: (Pair<Int, Int>) -> Unit) {
        viewModelScope.launch {
            try {
                val result = catalogViewModel.getFolderStats(folderId)
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback(Pair(0, 0)) }
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch { catalogViewModel.deleteFolder(folderId) }
    }

    fun moveFolder(
        folderId: String,
        newParentId: String?,
        newParentItemId: String? = null
    ) {
        viewModelScope.launch {
            catalogViewModel.moveFolder(folderId, newParentId, newParentItemId)
        }
    }

    fun moveItem(
        itemId: String,
        newParentId: String?,
        newParentItemId: String? = null
    ) {
        viewModelScope.launch {
            catalogViewModel.moveItem(itemId, newParentId, newParentItemId)
        }
    }

    // ============================================================
    // ОБНОВЛЕНИЕ ПРЕДМЕТА — ДЕЛЕГИРУЕТСЯ В CatalogViewModel
    // ============================================================

    fun updateItemQuantity(itemId: String, newQty: Int) {
        viewModelScope.launch { catalogViewModel.updateItemQuantity(itemId, newQty) }
    }

    fun updateItemFull(item: ItemEntity) {
        viewModelScope.launch { catalogViewModel.updateItemFull(item) }
    }

    // ============================================================
    // УДАЛЕНИЕ — ДЕЛЕГИРУЕТСЯ В CatalogViewModel
    // ============================================================

    fun deleteItem(itemId: String) {
        viewModelScope.launch { catalogViewModel.deleteItem(itemId) }
    }

    fun deleteItemSafely(itemId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            catalogViewModel.deleteItemSafely(
                itemId = itemId,
                onSuccess = { viewModelScope.launch { withContext(Dispatchers.Main) { onSuccess() } } },
                onError = { msg -> viewModelScope.launch { withContext(Dispatchers.Main) { onError(msg) } } }
            )
        }
    }

    fun deleteFolderSafely(folderId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            catalogViewModel.deleteFolderSafely(
                folderId = folderId,
                onSuccess = { viewModelScope.launch { withContext(Dispatchers.Main) { onSuccess() } } },
                onError = { msg -> viewModelScope.launch { withContext(Dispatchers.Main) { onError(msg) } } }
            )
        }
    }

    // ============================================================
    // СИЛА-СИНК / ПОИСК / ПРОЧЕЕ
    // ============================================================

    fun forceSync(): Boolean {
        if (forceSyncJob?.isActive == true) {
            Logger.log(TAG, "forceSync: already running, skipping")
            return false
        }
        forceSyncRequested = true
        syncResultMessage.postValue("⏳ Синхронизация…")
        forceSyncJob = applicationScope.launch { syncWithDisk() }
        return true
    }

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
