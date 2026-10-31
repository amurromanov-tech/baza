package com.family.base.ui.viewmodel

import com.family.base.util.ImageUtils
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.family.base.BaseApplication
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.*
import com.family.base.data.repository.CatalogRepository
import com.family.base.data.repository.GalleryRepository
import com.family.base.data.repository.TaskRepository
import com.family.base.ui.SyncStatus
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /**
     * 🆕 v20.4.9: репозиторий галереи.
     * Используется только для ensureGalleryFolder — создание системной
     * папки «🖼️ Галерея» при первом запуске. Листинг фото идёт из
     * GalleryViewModel (у него свой экземпляр GalleryRepository).
     */
    private val galleryRepository = GalleryRepository(db)

    private val tokenStorage = TokenStorage(application)
    private val lockDao = db.lockDao()
    private val syncInfoDao = db.syncInfoDao()
    private val settingsDao = db.settingsDao()

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

    private val syncViewModel: SyncViewModel by lazy {
        SyncViewModel(
            db = db,
            repository = repository,
            tokenStorage = tokenStorage,
            syncInfoDao = syncInfoDao,
            appContextProvider = { getApplication<Application>().applicationContext },
            onSyncStatusChanged = { status -> syncStatus.postValue(status) },
            onSyncProgressChanged = { progress -> syncProgress.postValue(progress) },
            onSyncResultMessage = { message ->
                if (message.isEmpty()) {
                    syncResultMessage.postValue(null)
                } else {
                    syncResultMessage.postValue(message)
                }
            }
        )
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

    private companion object {
        const val PREFS_SORT_ORDER = "sort_order"
        const val SORT_BY_NAME = "По имени"
        const val SORT_BY_EXPIRY = "По сроку годности"
        const val SORT_BY_PRICE = "По цене"
        const val SORT_BY_ADDED = "По дате добавления"
    }

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

        // ============================================================
        // 🆕 v20.4.9: системная папка «🖼️ Галерея».
        // Создаётся рядом с «🏠 Домашние дела» — один раз при первом
        // запуске. Содержимое (фото) живёт на Диске в /Романовы,
        // в локальной БД хранится только запись папки для отображения
        // в корне и перехвата клика в MainActivity.
        // ============================================================
        try {
            galleryRepository.ensureGalleryFolder(currentUser)
            Logger.log(TAG, "checkFirstLaunch: system folder 'gallery' ensured")
        } catch (e: Exception) {
            Logger.log(TAG, "Error ensuring gallery folder: ${e.message}")
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

    // ============================================================
    // ЗАГРУЗКА СОДЕРЖИМОГО С СОРТИРОВКОЙ
    // ============================================================
    fun loadContents() {
        viewModelScope.launch {
            try {
                val entries = mutableListOf<Any>()
                withContext(Dispatchers.IO) {
                    val folders = repository.getFolders(currentFolderId)
                    val allItemsFromDb = db.itemDao().getAllItems()
                    allItems = allItemsFromDb

                    if (!searchQuery.isNullOrEmpty()) {
                        val q = searchQuery!!
                        val foundItems = allItemsFromDb
                            .filter { it.name.contains(q, ignoreCase = true) }
                            .sortedBy { it.name.lowercase() }
                        entries.addAll(foundItems)
                        return@withContext
                    }

                    val itemsAtLevel = repository.getItems(currentFolderId)

                    val itemsWithChildrenIds: Set<String> =
                        db.itemDao().getItemIdsWithChildren().toSet()

                    val (itemsWithChildren, itemsWithoutChildren) =
                        itemsAtLevel.partition { it.id in itemsWithChildrenIds }

                    val topBlock = mutableListOf<Any>()
                    topBlock.addAll(folders.sortedBy { it.name.lowercase() })
                    topBlock.addAll(itemsWithChildren.sortedBy { it.name.lowercase() })

                    val sortOrder = readSortOrder()
                    val bottomBlock = sortItems(itemsWithoutChildren, sortOrder)

                    entries.addAll(topBlock)
                    entries.addAll(bottomBlock)

                    Logger.log(
                        TAG,
                        "loadContents: folder=$currentFolderId, " +
                            "top(folders=${folders.size}+withChildren=${itemsWithChildren.size}), " +
                            "bottom(withoutChildren=${bottomBlock.size}, sort=$sortOrder)"
                    )
                }
                currentEntries.postValue(entries)
            } catch (e: Exception) {
                Logger.log(TAG, "Error in loadContents: ${e.message}")
            }
        }
    }

    private fun readSortOrder(): String {
        return try {
            val prefs = getApplication<Application>()
                .getSharedPreferences(BaseApplication.PREFS_NAME, Application.MODE_PRIVATE)
            prefs.getString(PREFS_SORT_ORDER, SORT_BY_NAME) ?: SORT_BY_NAME
        } catch (e: Exception) {
            Logger.log(TAG, "readSortOrder error: ${e.message}")
            SORT_BY_NAME
        }
    }

    private fun sortItems(items: List<ItemEntity>, sortOrder: String): List<ItemEntity> {
        return when (sortOrder) {
            SORT_BY_EXPIRY -> items.sortedWith(
                compareBy(
                    { item ->
                        when {
                            item.isExpired -> 0
                            item.daysUntilExpiry in 0..3 -> 1
                            item.daysUntilExpiry != Int.MAX_VALUE -> 2
                            else -> 3
                        }
                    },
                    { it.daysUntilExpiry },
                    { it.name.lowercase() }
                )
            )

            SORT_BY_PRICE -> items.sortedWith(
                compareBy<ItemEntity>(
                    { it.price == null || it.price == 0.0 },
                    { it.price ?: Double.MAX_VALUE }
                ).thenBy { it.name.lowercase() }
            )

            SORT_BY_ADDED -> items.sortedWith(
                compareByDescending<ItemEntity> { it.addedDate }
                    .thenBy { it.name.lowercase() }
            )

            else -> items.sortedBy { it.name.lowercase() }
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
    // СИНХРОНИЗАЦИЯ (делегат SyncViewModel)
    // ============================================================

    fun syncWithDisk() {
        syncViewModel.syncWithDisk()
    }

    fun forceSync(): Boolean {
        return syncViewModel.forceSync()
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
        onBlocked: ((String) -> Unit)? = null,
        onDone: ((archived: Boolean) -> Unit)? = null
    ) {
        viewModelScope.launch {
            catalogViewModel.writeOffItem(
                itemId = itemId,
                count = count,
                reason = reason,
                note = note,
                onBlocked = { msg ->
                    viewModelScope.launch {
                        withContext(Dispatchers.Main) { onBlocked?.invoke(msg) }
                    }
                },
                onDone = { archived ->
                    viewModelScope.launch {
                        withContext(Dispatchers.Main) { onDone?.invoke(archived) }
                    }
                }
            )
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
    // ПРОЧЕЕ
    // ============================================================

    suspend fun getAllFolders(): List<FolderEntity> {
        return withContext(Dispatchers.IO) { repository.getAllFolders() }
    }

    suspend fun uploadFolderImage(folderId: String, imageBytes: ByteArray): Boolean {
        return repository.uploadFolderImage(folderId, imageBytes)
    }

    suspend fun downloadItemImageBytes(itemId: String): ByteArray? {
        return repository.downloadItemImageBytes(itemId)
    }

    // ============================================================
    // «ДЕЛА НА СЕГОДНЯ» ДЛЯ ПАРЯЩЕГО ОКНА ПРИ СТАРТЕ
    // ============================================================

    suspend fun getTodayTasks(nowMs: Long, endOfDayMs: Long): List<TaskEntity> {
        return taskRepository.getTodayTasks(nowMs, endOfDayMs)
    }
}
