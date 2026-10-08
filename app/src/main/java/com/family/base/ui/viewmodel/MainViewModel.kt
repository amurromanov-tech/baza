package com.family.base.ui.viewmodel

import com.family.base.util.ImageUtils
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.*
import com.family.base.data.model.SubtypeCatalog
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

    private suspend fun writeHistory(
        itemId: String,
        itemName: String?,
        action: String,
        oldValue: String?,
        newValue: String?
    ) {
        try {
            db.historyDao().insertEntry(
                HistoryEntry(
                    itemId = itemId,
                    itemName = itemName,
                    action = action,
                    oldValue = oldValue,
                    newValue = newValue,
                    changedBy = currentUser
                )
            )
        } catch (e: Exception) {
            Logger.log(TAG, "writeHistory error: action=$action, itemId=$itemId, ${e.message}")
        }
    }

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

    private suspend fun migrateOldHistoryEntries() {
        val settings = settingsDao.getSettings()
        if (settings?.historyMigratedV12 == true) {
            Logger.log(TAG, "migrateOldHistoryEntries: already done, skipping")
            return
        }

        Logger.log(TAG, "migrateOldHistoryEntries: START")

        val entriesWithoutName = db.historyDao().getEntriesWithoutItemName()
        Logger.log(TAG, "migrateOldHistoryEntries: found ${entriesWithoutName.size} entries without itemName")

        var nameFilledCount = 0
        for (entry in entriesWithoutName) {
            val item = db.itemDao().getItemById(entry.itemId)
            if (item != null) {
                try {
                    db.historyDao().updateItemName(entry.id, item.name)
                    nameFilledCount++
                } catch (e: Exception) {
                    Logger.log(TAG, "Failed to update itemName for entry ${entry.id}: ${e.message}")
                }
                continue
            }

            val folder = db.folderDao().getFolderById(entry.itemId)
            if (folder != null) {
                try {
                    db.historyDao().updateItemName(entry.id, folder.name)
                    nameFilledCount++
                } catch (e: Exception) {
                    Logger.log(TAG, "Failed to update itemName for folder entry ${entry.id}: ${e.message}")
                }
                continue
            }
        }
        Logger.log(TAG, "migrateOldHistoryEntries: filled itemName for $nameFilledCount entries")

        val entriesWithRawCodes = db.historyDao().getEntriesWithRawTypeCodes()
        Logger.log(TAG, "migrateOldHistoryEntries: found ${entriesWithRawCodes.size} entries with raw type codes")

        var rawFixedCount = 0
        for (entry in entriesWithRawCodes) {
            val newOld = entry.oldValue?.let { replaceRawCodes(it) }
            val newNew = entry.newValue?.let { replaceRawCodes(it) }

            if (newOld != entry.oldValue || newNew != entry.newValue) {
                try {
                    db.historyDao().updateEntryFields(
                        entryId = entry.id,
                        itemName = entry.itemName,
                        oldValue = newOld,
                        newValue = newNew
                    )
                    rawFixedCount++
                } catch (e: Exception) {
                    Logger.log(TAG, "Failed to update entry ${entry.id}: ${e.message}")
                }
            }
        }
        Logger.log(TAG, "migrateOldHistoryEntries: fixed raw codes in $rawFixedCount entries")

        try {
            val current = settingsDao.getSettings() ?: SettingsEntity()
            settingsDao.insertOrUpdateSettings(current.copy(historyMigratedV12 = true))
            Logger.log(TAG, "migrateOldHistoryEntries: DONE, flag set to true")
        } catch (e: Exception) {
            Logger.log(TAG, "Failed to set historyMigratedV12 flag: ${e.message}")
        }
    }

    private fun replaceRawCodes(text: String): String {
        var result = text

        val typeMap = mapOf(
            "thing" to "📦 Предмет",
            "food" to "🍎 Еда",
            "medicine" to "💊 Лекарство",
            "other" to "🗂 Другое"
        )
        typeMap.forEach { (code, display) ->
            result = replaceWholeWord(result, code, display)
        }

        try {
            for (type in listOf("food", "medicine", "thing", "other")) {
                val subtypes = SubtypeCatalog.getSubtypes(type)
                subtypes.forEach { subtype ->
                    if (subtype.key.isNotEmpty()) {
                        result = replaceWholeWord(result, subtype.key, subtype.displayName)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "replaceRawCodes: SubtypeCatalog error: ${e.message}")
        }

        return result
    }

    private fun replaceWholeWord(text: String, word: String, replacement: String): String {
        if (word.isEmpty()) return text
        return try {
            text.replace(Regex("\\b${Regex.escape(word)}\\b"), replacement)
        } catch (e: Exception) {
            text
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

    fun createItem(item: ItemEntity, imageBytes: ByteArray? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.itemDao().insertItem(item) }

            imageBytes?.let { bytes ->
                val appContext = getApplication<Application>().applicationContext
                ImageUtils.saveImageLocally(appContext, item.id, bytes)
            }

            enqueue("item", item.id, "create", item.parentId, item.parentItemId)

            writeHistory(
                itemId = item.id,
                itemName = item.name,
                action = "create",
                oldValue = null,
                newValue = "${item.quantity} шт."
            )

            loadContents()
        }
    }

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

            prepared.forEach { item ->
                writeHistory(
                    itemId = item.id,
                    itemName = item.name,
                    action = "create",
                    oldValue = "Импорт из чека",
                    newValue = "${item.quantity} шт."
                )
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
                    writeHistory(
                        itemId = itemId,
                        itemName = parent.name,
                        action = "detach_children",
                        oldValue = null,
                        newValue = "Отвязано детей: $count"
                    )
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

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "delete",
                    oldValue = "Было детей: $detached",
                    newValue = null
                )

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

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "archive",
                    oldValue = "В базе (отвязано детей: $detached)",
                    newValue = "В архиве (${getArchiveReasonText(reason)}${if (!note.isNullOrEmpty()) ": $note" else ""})"
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

                writeHistory(
                    itemId = parentItemId,
                    itemName = parentItem.name,
                    action = "add_nested_folder",
                    oldValue = null,
                    newValue = "📁 $name"
                )

                Logger.log(TAG, "createFolderInItem: created folder ${folder.id} '${folder.name}' in item $parentItemId")
                withContext(Dispatchers.Main) { onDone(folder.id) }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error createFolderInItem: ${e.message}")
                withContext(Dispatchers.Main) { onDone(null) }
            }
        }
    }

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

                    writeHistory(
                        itemId = folderId,
                        itemName = folder.name,
                        action = "delete_folder",
                        oldValue = "Папка",
                        newValue = null
                    )
                }

                withContext(Dispatchers.Main) { onDone(true) }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error detachAllFolderChildrenAndDelete: ${e.message}")
                withContext(Dispatchers.Main) { onDone(false) }
            }
        }
    }

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
                        downloadResult.history,
                        downloadResult.foldersError,
                        downloadResult.itemsError,
                        downloadResult.historyError
                    )
                    downloadedCount = downloadResult.folders.size + downloadResult.items.size + downloadResult.history.size

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

                    val tUploadFolders = System.currentTimeMillis()
                    val uploadedFolders = repository.uploadAllFoldersToDisk()
                    Logger.log(TAG, "TIMING: uploadAllFoldersToDisk took ${System.currentTimeMillis() - tUploadFolders}ms")

                    Logger.log(TAG, "syncWithDisk: full upload folders=$uploadedFolders")

                    val tPending = System.currentTimeMillis()
                    uploadedCount = processPendingChangesInternal()
                    Logger.log(TAG, "TIMING: processPendingChangesInternal took ${System.currentTimeMillis() - tPending}ms")

                    val tFinal = System.currentTimeMillis()
                    val refreshed = repository.uploadAllItemsToDisk()
                    Logger.log(TAG, "TIMING: uploadAllItemsToDisk (final) took ${System.currentTimeMillis() - tFinal}ms, success=$refreshed")
                    if (refreshed) {
                        val fresh = withContext(Dispatchers.IO) { repository.getDiskLastModified() }
                        if (fresh != null && fresh > 0L) {
                            withContext(Dispatchers.IO) { syncInfoDao.setLastModified(fresh) }
                        }
                    }

                    // 🆕 v13.2.0 (B-8): выгрузка истории после items
                    val tHistory = System.currentTimeMillis()
                    val uploadedHistory = repository.uploadAllHistoryToDisk()
                    Logger.log(TAG, "TIMING: uploadAllHistoryToDisk took ${System.currentTimeMillis() - tHistory}ms, success=$uploadedHistory")
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
                        downloadResult.history,
                        downloadResult.foldersError,
                        downloadResult.itemsError,
                        downloadResult.historyError
                    )
                    Logger.log(TAG, "TIMING: mergeData took ${System.currentTimeMillis() - tMerge}ms")

                    downloadedCount = downloadResult.folders.size + downloadResult.items.size + downloadResult.history.size

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
                if (pendingCount > 0) {
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
        diskHistory: List<HistoryEntry>,
        foldersError: Boolean,
        itemsError: Boolean,
        historyError: Boolean
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

            // ---------- ИСТОРИЯ (🆕 v13.2.0 B-8) ----------
            // История — журнал, union по id. Ничего не удаляем.
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

            if (diskIsNewer) {
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
                val success: Boolean = when (entry.entityType) {
                    "folder" -> applyFolderChange(entry)
                    "item" -> applyItemChange(entry)
                    else -> false
                }
                Logger.log(TAG, "TIMING: applyChange for ${entry.entityType}/${entry.entityId} took ${System.currentTimeMillis() - tEntry}ms, success=$success")

                if (success) {
                    syncQueueDao.removeFromQueueById(entry.id)
                    successCount++
                } else {
                    Logger.log(TAG, "Pending entry ${entry.id} (${entry.entityType}/${entry.action}) FAILED, keeping in queue")
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Failed to process pending entry ${entry.id} (${entry.entityType}/${entry.action}): ${e.message}")
            }
        }
        return successCount
    }

    private suspend fun applyFolderChange(entry: SyncQueueEntity): Boolean {
        return try {
            when (entry.action) {
                "create" -> {
                    val folder = db.folderDao().getFolderById(entry.entityId) ?: return false
                    val diskSuccess = repository.createFolderOnDisk(folder)
                    val appContext = getApplication<Application>().applicationContext
                    val localFile = ImageUtils.getLocalImageFile(appContext, "folder_${folder.id}")
                    if (localFile != null && localFile.exists() && folder.iconUrl.isNullOrEmpty()) {
                        try {
                            val imgSuccess = repository.uploadFolderImage(folder.id, localFile.readBytes())
                            if (imgSuccess) {
                                val updated = folder.copy(iconUrl = "folder_${folder.id}.jpg")
                                db.folderDao().updateFolder(updated)
                                repository.updateFolderOnDisk(updated)
                            }
                        } catch (e: Exception) {
                            Logger.log(TAG, "applyFolderChange: upload folder image failed: ${e.message}")
                        }
                    }
                    diskSuccess
                }
                "update" -> {
                    val folder = db.folderDao().getFolderById(entry.entityId) ?: return false
                    val fresh = folder.copy(updatedAt = System.currentTimeMillis())
                    db.folderDao().updateFolder(fresh)
                    repository.updateFolderOnDisk(fresh)
                }
                "delete" -> repository.deleteFolderOnDisk(entry.entityId)
                else -> false
            }
        } catch (e: Exception) {
            Logger.log(TAG, "applyFolderChange error: ${e.message}")
            false
        }
    }

    private suspend fun applyItemChange(entry: SyncQueueEntity): Boolean {
        return try {
            when (entry.action) {
                "create" -> {
                    val item = db.itemDao().getItemById(entry.entityId) ?: return false
                    val t2 = System.currentTimeMillis()
                    uploadItemImageIfExists(item)
                    Logger.log(TAG, "TIMING: applyItemChange uploadItemImageIfExists took ${System.currentTimeMillis() - t2}ms")
                    true
                }
                "update" -> {
                    val item = db.itemDao().getItemById(entry.entityId) ?: return false
                    val fresh = item.copy(updatedDate = System.currentTimeMillis())
                    db.itemDao().updateItem(fresh)
                    val t2 = System.currentTimeMillis()
                    uploadItemImageIfExists(fresh)
                    Logger.log(TAG, "TIMING: applyItemChange uploadItemImageIfExists took ${System.currentTimeMillis() - t2}ms")
                    true
                }
                "delete" -> repository.deleteItemOnDisk(entry.entityId)
                else -> false
            }
        } catch (e: Exception) {
            Logger.log(TAG, "applyItemChange error: ${e.message}")
            false
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

                    writeHistory(
                        itemId = itemId,
                        itemName = item.name,
                        action = "lend",
                        oldValue = "В базе",
                        newValue = "Выдан: $personName${if (!note.isNullOrEmpty()) " ($note)" else ""}"
                    )

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

                    writeHistory(
                        itemId = itemId,
                        itemName = item.name,
                        action = "return",
                        oldValue = "Выдан: ${item.lentTo}",
                        newValue = "Возвращён в базу"
                    )

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

                    writeHistory(
                        itemId = itemId,
                        itemName = item.name,
                        action = "archive",
                        oldValue = "В базе",
                        newValue = "В архиве (${getArchiveReasonText(reason)}${if (!note.isNullOrEmpty()) ": $note" else ""})"
                    )

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

                    writeHistory(
                        itemId = itemId,
                        itemName = item.name,
                        action = "unarchive",
                        oldValue = "В архиве",
                        newValue = "Вернули в базу"
                    )

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

                        writeHistory(
                            itemId = originalId,
                            itemName = original.name,
                            action = "unarchive_part",
                            oldValue = "${original.quantity} шт.",
                            newValue = "$newQty шт. (возвращено ${item.quantity})"
                        )

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

                        writeHistory(
                            itemId = itemId,
                            itemName = item.name,
                            action = "unarchive",
                            oldValue = "В архиве",
                            newValue = "Вернули в базу (оригинал не найден)"
                        )

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

                    writeHistory(
                        itemId = itemId,
                        itemName = item.name,
                        action = "write_off",
                        oldValue = "${item.quantity} шт.",
                        newValue = "Списано всё → в архиве (${getArchiveReasonText(reasonKey)}${if (!note.isNullOrEmpty()) ": $note" else ""})"
                    )

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

                        writeHistory(
                            itemId = newId,
                            itemName = item.name,
                            action = "write_off",
                            oldValue = "Отделено от «${item.name}»",
                            newValue = "Списано $count шт. (оригинал удалён, ${getArchiveReasonText(reasonKey)})"
                        )

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

                        writeHistory(
                            itemId = newId,
                            itemName = item.name,
                            action = "write_off",
                            oldValue = "Отделено от «${item.name}»",
                            newValue = "Списано $count шт. (${getArchiveReasonText(reasonKey)}${if (!note.isNullOrEmpty()) ": $note" else ""})"
                        )

                        writeHistory(
                            itemId = itemId,
                            itemName = item.name,
                            action = "write_off_part",
                            oldValue = "${item.quantity} шт.",
                            newValue = "$newQty шт. (списано $count)"
                        )

                        Logger.log(TAG, "writeOffItem: partial → new archive entry $newId ($count шт.), original=$newQty шт.")
                    }
                }

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error writing off item: ${e.message}", e)
            }
        }
    }

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

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "revision",
                    oldValue = item.lastRevisionDate?.let { formatDateShort(it) } ?: "не проводилась",
                    newValue = formatDateShort(now)
                )

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

    fun splitAndMoveItem(
        itemId: String,
        count: Int,
        newParentId: String?,
        newParentItemId: String? = null
    ) {
        Logger.log(
            TAG,
            "splitAndMoveItem: itemId=$itemId, count=$count, " +
                "newParentId=$newParentId, newParentItemId=$newParentItemId"
        )
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
                    moveItem(itemId, newParentId, newParentItemId)
                    return@launch
                }

                val resolvedParentId: String? = if (newParentItemId != null) {
                    val targetItem = db.itemDao().getItemById(newParentItemId)
                    targetItem?.parentId
                } else {
                    newParentId
                }

                if (newParentItemId != null && isItemDescendantOf(newParentItemId, itemId)) {
                    Logger.log(TAG, "splitAndMoveItem: CYCLE detected (item=$itemId → item=$newParentItemId), aborting")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(),
                            "Нельзя переместить предмет в себя или в своего потомка",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val appContext = getApplication<Application>().applicationContext
                val now = System.currentTimeMillis()
                val newId = java.util.UUID.randomUUID().toString()

                val copy = item.copy(
                    id = newId,
                    quantity = count,
                    parentId = resolvedParentId,
                    parentItemId = newParentItemId,
                    addedDate = now,
                    updatedDate = now,
                    updatedBy = currentUser
                )
                copy.computeExpiryFields()

                withContext(Dispatchers.IO) {
                    db.itemDao().insertItem(copy)
                }
                enqueue("item", newId, "create", resolvedParentId, newParentItemId)

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

                val placeName = resolvePlaceName(resolvedParentId, newParentItemId)

                writeHistory(
                    itemId = newId,
                    itemName = item.name,
                    action = "split_in",
                    oldValue = "Отделено от «${item.name}»",
                    newValue = "$count шт. → $placeName"
                )

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "split_out",
                    oldValue = "${item.quantity} шт.",
                    newValue = "$newQty шт. (отделено $count → $placeName)"
                )

                Logger.log(TAG, "splitAndMoveItem: done. new=$newId ($count шт.), original=$newQty шт.")

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error in splitAndMoveItem: ${e.message}", e)
            }
        }
    }

    private suspend fun resolvePlaceName(parentId: String?, parentItemId: String?): String {
        if (parentItemId != null) {
            val item = db.itemDao().getItemById(parentItemId)
            return if (item != null) "📦 ${item.name}" else "предмет"
        }
        if (parentId == null) return "Корень"
        return db.folderDao().getFolderById(parentId)?.name ?: "Корень"
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

                    writeHistory(
                        itemId = copy.id,
                        itemName = copy.name,
                        action = "create",
                        oldValue = "Копия «${item.name}»",
                        newValue = "${copy.quantity} шт."
                    )

                    loadContents()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error copying item: ${e.message}")
            }
        }
    }

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

            writeHistory(
                itemId = folder.id,
                itemName = folder.name,
                action = "create_folder",
                oldValue = null,
                newValue = "📁 $name"
            )

            loadContents()
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch {
            try {
                val folder = db.folderDao().getFolderById(folderId)
                if (folder != null) {
                    val oldName = folder.name
                    val updated = folder.copy(name = newName, updatedAt = System.currentTimeMillis())
                    db.folderDao().updateFolder(updated)
                    enqueue("folder", folderId, "update", folder.parentId, folder.parentItemId)

                    writeHistory(
                        itemId = folderId,
                        itemName = newName,
                        action = "rename_folder",
                        oldValue = oldName,
                        newValue = newName
                    )

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

                writeHistory(
                    itemId = folderId,
                    itemName = folder?.name,
                    action = "delete_folder",
                    oldValue = "Папка (содержимое поднято в корень)",
                    newValue = null
                )

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleting folder: ${e.message}")
            }
        }
    }

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

    fun moveFolder(
        folderId: String,
        newParentId: String?,
        newParentItemId: String? = null
    ) {
        Logger.log(
            TAG,
            "moveFolder: folderId=$folderId, newParentId=$newParentId, newParentItemId=$newParentItemId"
        )
        viewModelScope.launch {
            try {
                val folder = db.folderDao().getFolderById(folderId)
                if (folder == null) {
                    Logger.log(TAG, "moveFolder: folder not found $folderId")
                    return@launch
                }

                if (newParentItemId == null && newParentId != null) {
                    if (isFolderDescendantOf(folderId, newParentId)) {
                        Logger.log(TAG, "moveFolder: CYCLE (folder→folder), aborting")
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                getApplication(),
                                "Нельзя переместить папку в себя или в свою подпапку",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }
                }

                if (newParentItemId != null) {
                    val ancestorItems = collectAncestorItemIdsForFolder(folderId)
                    if (newParentItemId in ancestorItems) {
                        Logger.log(TAG, "moveFolder: CYCLE (folder→item), aborting")
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                getApplication(),
                                "Нельзя переместить папку внутрь связанного предмета",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }
                }

                val resolvedParentId: String? = if (newParentItemId != null) {
                    db.itemDao().getItemById(newParentItemId)?.parentId
                } else {
                    newParentId
                }

                val updated = folder.copy(
                    parentId = resolvedParentId,
                    parentItemId = newParentItemId,
                    updatedAt = System.currentTimeMillis()
                )
                db.folderDao().updateFolder(updated)
                enqueue("folder", folderId, "update", resolvedParentId, newParentItemId)

                val placeName = resolvePlaceName(resolvedParentId, newParentItemId)
                writeHistory(
                    itemId = folderId,
                    itemName = folder.name,
                    action = "move_folder",
                    oldValue = null,
                    newValue = "📁 ${folder.name} → $placeName"
                )

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error moving folder: ${e.message}")
            }
        }
    }

    private suspend fun collectAncestorItemIdsForFolder(folderId: String): Set<String> {
        val result = mutableSetOf<String>()
        var currentFolderId: String? = folderId
        var depth = 0

        while (currentFolderId != null && depth < 50) {
            depth++
            val folder = db.folderDao().getFolderById(currentFolderId) ?: break

            if (folder.parentItemId != null) {
                result.add(folder.parentItemId)
                var itemId: String? = folder.parentItemId
                var itemDepth = 0
                while (itemId != null && itemDepth < 50) {
                    itemDepth++
                    result.add(itemId)
                    val item = db.itemDao().getItemById(itemId) ?: break
                    itemId = item.parentItemId
                }
                currentFolderId = folder.parentId
            } else {
                currentFolderId = folder.parentId
            }
        }

        return result
    }

    fun moveItem(
        itemId: String,
        newParentId: String?,
        newParentItemId: String? = null
    ) {
        Logger.log(
            TAG,
            "moveItem: itemId=$itemId, newParentId=$newParentId, newParentItemId=$newParentItemId"
        )
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item == null) {
                    Logger.log(TAG, "moveItem: item not found $itemId")
                    return@launch
                }

                if (newParentItemId != null) {
                    if (isItemDescendantOf(newParentItemId, itemId)) {
                        Logger.log(TAG, "moveItem: CYCLE detected (item=$itemId → item=$newParentItemId), aborting")
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                getApplication(),
                                "Нельзя переместить предмет в себя или в своего потомка",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }
                }

                val resolvedParentId: String? = if (newParentItemId != null) {
                    db.itemDao().getItemById(newParentItemId)?.parentId
                } else {
                    newParentId
                }

                val updated = item.copy(
                    parentId = resolvedParentId,
                    parentItemId = newParentItemId,
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = currentUser
                )
                updated.computeExpiryFields()
                db.itemDao().updateItem(updated)
                enqueue("item", itemId, "update", resolvedParentId, newParentItemId)

                val placeName = resolvePlaceName(resolvedParentId, newParentItemId)
                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "move",
                    oldValue = "Из предыдущего места",
                    newValue = "$placeName"
                )

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error moving item: ${e.message}")
            }
        }
    }

    fun updateItemQuantity(itemId: String, newQty: Int) {
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    val oldQty = item.quantity
                    if (oldQty == newQty) return@launch

                    val updated = item.copy(
                        quantity = newQty,
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = currentUser
                    )
                    updated.computeExpiryFields()
                    db.itemDao().updateItem(updated)
                    enqueue("item", itemId, "update", item.parentId, item.parentItemId)

                    writeHistory(
                        itemId = itemId,
                        itemName = item.name,
                        action = "quantity_change",
                        oldValue = "$oldQty шт.",
                        newValue = "$newQty шт."
                    )

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
                val old = db.itemDao().getItemById(item.id)
                if (old == null) {
                    Logger.log(TAG, "updateItemFull: item not found ${item.id}")
                    return@launch
                }

                val updated = item.copy(
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = currentUser
                )
                updated.computeExpiryFields()
                db.itemDao().updateItem(updated)
                enqueue("item", item.id, "update", item.parentId, item.parentItemId)

                val diffs = buildDiff(old, updated)
                if (diffs.isNotEmpty()) {
                    writeHistory(
                        itemId = item.id,
                        itemName = updated.name,
                        action = "update",
                        oldValue = null,
                        newValue = diffs.joinToString("\n")
                    )
                }

                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error updating item: ${e.message}")
            }
        }
    }

    private fun buildDiff(old: ItemEntity, new: ItemEntity): List<String> {
        val diffs = mutableListOf<String>()

        if (old.name != new.name) {
            diffs.add("Название: «${old.name}» → «${new.name}»")
        }
        if (old.quantity != new.quantity) {
            diffs.add("Количество: ${old.quantity} → ${new.quantity}")
        }
        if ((old.price ?: 0.0) != (new.price ?: 0.0)) {
            diffs.add("Цена: ${formatPrice(old.price)} → ${formatPrice(new.price)}")
        }
        if (old.expiryDate != new.expiryDate) {
            diffs.add("Срок годности: ${formatDateOrDash(old.expiryDate)} → ${formatDateOrDash(new.expiryDate)}")
        }
        if (old.purchaseDate != new.purchaseDate) {
            diffs.add("Дата покупки: ${formatDateOrDash(old.purchaseDate)} → ${formatDateOrDash(new.purchaseDate)}")
        }
        if (old.itemType != new.itemType) {
            diffs.add("Тип: ${typeName(old.itemType)} → ${typeName(new.itemType)}")
        }
        if (old.itemSubtype != new.itemSubtype) {
            diffs.add("Подтип: ${subtypeName(old.itemType, old.itemSubtype)} → ${subtypeName(new.itemType, new.itemSubtype)}")
        }
        if (old.barcode != new.barcode) {
            diffs.add("Штрих-код: ${old.barcode ?: "—"} → ${new.barcode ?: "—"}")
        }
        if (old.description != new.description) {
            val oldDesc = if (old.description.isNullOrEmpty()) "—" else "«${truncate(old.description!!, 60)}»"
            val newDesc = if (new.description.isNullOrEmpty()) "—" else "«${truncate(new.description!!, 60)}»"
            diffs.add("Описание: $oldDesc → $newDesc")
        }

        return diffs
    }

    private fun formatPrice(p: Double?): String =
        if (p == null || p == 0.0) "—" else "$p ₽"

    private fun formatDateOrDash(ts: Long?): String =
        if (ts == null) "—" else formatDateShort(ts)

    private fun typeName(t: String?): String = when (t) {
        "food" -> "🍎 Еда"
        "medicine" -> "💊 Лекарство"
        "thing" -> "📦 Предмет"
        "other" -> "🗂 Другое"
        else -> "—"
    }

    private fun subtypeName(type: String?, subtype: String?): String {
        if (subtype.isNullOrEmpty()) return "—"
        return try {
            SubtypeCatalog.getDisplayName(type ?: "", subtype) ?: subtype
        } catch (e: Exception) {
            subtype
        }
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            try {
                val item = db.itemDao().getItemById(itemId)
                if (item != null) {
                    db.itemDao().deleteItem(item)
                    val appContext = getApplication<Application>().applicationContext
                    ImageUtils.deleteLocalImage(appContext, itemId)
                    enqueue("item", itemId, "delete", item.parentId, item.parentItemId)

                    writeHistory(
                        itemId = itemId,
                        itemName = item.name,
                        action = "delete",
                        oldValue = "${item.quantity} шт.",
                        newValue = null
                    )

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

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "delete",
                    oldValue = "${item.quantity} шт.",
                    newValue = null
                )

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

                writeHistory(
                    itemId = folderId,
                    itemName = folder.name,
                    action = "delete_folder",
                    oldValue = "Папка",
                    newValue = null
                )

                Logger.log(TAG, "deleteFolderSafely: deleted $folderId")
                withContext(Dispatchers.Main) { onSuccess() }
                loadContents()
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleteFolderSafely: ${e.message}")
                withContext(Dispatchers.Main) { onError("Ошибка удаления: ${e.message}") }
            }
        }
    }

    fun forceSync() {
        if (forceSyncJob?.isActive == true) {
            Logger.log(TAG, "forceSync: already running, skipping")
            return
        }
        forceSyncRequested = true
        syncResultMessage.postValue("⏳ Синхронизация…")
        forceSyncJob = applicationScope.launch { syncWithDisk() }
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
