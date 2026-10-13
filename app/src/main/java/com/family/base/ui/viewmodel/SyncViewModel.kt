package com.family.base.ui.viewmodel

import android.content.Context
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.TaskEntity
import com.family.base.data.repository.CatalogRepository
import com.family.base.data.sync.ChangelogParser
import com.family.base.data.sync.SyncManager
import com.family.base.data.sync.SyncManagerApi
import com.family.base.ui.SyncStatus
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Делегат MainViewModel: вся синхронизация с Яндекс.Диском.
 *
 * Отвечает за:
 *  - syncWithDisk() — оркестрация полного цикла синка;
 *  - push через changelog (SyncManager);
 *  - pull через JSON-ы (items/folders/history/tasks);
 *  - mergeData() — слияние локального и облачного;
 *  - фото: upload/download (items и folders);
 *  - forceSync();
 *  - ensureValidToken() + isInternetAvailable().
 *
 * НЕ знает про LiveData. Пишет статус/прогресс/сообщения через колбэки,
 * которые реализует MainViewModel (postValue в свои LiveData).
 *
 * applicationScope создаётся здесь — синк живёт независимо от
 * жизненного цикла активити.
 */
class SyncViewModel(
    private val db: AppDatabase,
    private val repository: CatalogRepository,
    private val tokenStorage: TokenStorage,
    private val syncInfoDao: com.family.base.data.local.dao.SyncInfoDao,
    private val appContextProvider: () -> Context,
    private val onSyncStatusChanged: (SyncStatus) -> Unit,
    private val onSyncProgressChanged: (SyncProgress?) -> Unit,
    private val onSyncResultMessage: (String) -> Unit
) {

    private val TAG = "SyncViewModel"

    private fun appContext(): Context = appContextProvider()

    // ============================================================
    // CHANGELOG
    // ============================================================

    private val syncManagerApi = SyncManagerApi()
    private val changelogParser by lazy {
        ChangelogParser(db, com.family.base.data.local.DeviceIdProvider.get(appContext()))
    }
    private val syncManager by lazy {
        SyncManager(db, changelogParser, syncManagerApi)
    }

    // ============================================================
    // СОСТОЯНИЕ
    // ============================================================

    private val syncMutex = Mutex()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var forceSyncJob: Job? = null
    private var forceSyncRequested = false

    // ============================================================
    // FORCE SYNC
    // ============================================================

    fun forceSync(): Boolean {
        if (forceSyncJob?.isActive == true) {
            Logger.log(TAG, "forceSync: already running, skipping")
            return false
        }
        forceSyncRequested = true
        onSyncResultMessage("⏳ Синхронизация…")
        forceSyncJob = applicationScope.launch { syncWithDisk() }
        return true
    }

    // ============================================================
    // ОРКЕСТРАЦИЯ СИНКА
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
                onSyncProgressChanged(SyncProgress(SyncPhase.SENDING, 0, 1, "Проверка авторизации…"))

                val token = tokenStorage.getAccessToken()
                if (token.isNullOrEmpty()) {
                    Logger.log(TAG, "syncWithDisk: no token, skipping sync")
                    onSyncStatusChanged(SyncStatus.OFFLINE)
                    onSyncProgressChanged(null)
                    return@launch
                }

                if (!isInternetAvailable()) {
                    Logger.log(TAG, "syncWithDisk: no internet, skipping sync")
                    onSyncStatusChanged(SyncStatus.OFFLINE)
                    notifySyncResult("Нет сети. Изменения сохранены локально")
                    onSyncProgressChanged(null)
                    return@launch
                }

                if (!ensureValidToken()) {
                    onSyncStatusChanged(SyncStatus.OFFLINE)
                    notifySyncResult("Не удалось авторизоваться")
                    onSyncProgressChanged(null)
                    return@launch
                }

                onSyncStatusChanged(SyncStatus.SYNCING)

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

                    onSyncProgressChanged(
                        SyncProgress(SyncPhase.DOWNLOADING, 0, 1, "Первый запуск: загрузка данных с Диска…")
                    )

                    val downloadResult = repository.downloadDataFromDisk()
                    onSyncProgressChanged(SyncProgress(SyncPhase.DOWNLOADING, 1, 1, "Слияние данных…"))
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
                        onSyncProgressChanged(
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

                    onSyncProgressChanged(
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

                    onSyncProgressChanged(SyncProgress(SyncPhase.DOWNLOADING, 0, 1, "Получение данных…"))

                    val tDownload = System.currentTimeMillis()
                    val downloadResult = repository.downloadDataFromDisk()
                    Logger.log(TAG, "TIMING: downloadDataFromDisk took ${System.currentTimeMillis() - tDownload}ms")

                    onSyncProgressChanged(SyncProgress(SyncPhase.DOWNLOADING, 1, 1, "Слияние данных…"))

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
                    onSyncProgressChanged(
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
                onSyncProgressChanged(null)
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
            onSyncStatusChanged(SyncStatus.PENDING)
        } else {
            onSyncStatusChanged(SyncStatus.SYNCED)
        }

        onSyncProgressChanged(
            SyncProgress(
                SyncPhase.DONE,
                uploadedCount + downloadedCount,
                uploadedCount + downloadedCount,
                "Готово: отправлено $uploadedCount, получено $downloadedCount"
            )
        )

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
        onSyncResultMessage(message)
        applicationScope.launch {
            delay(2000)
            onSyncResultMessage("")
        }
    }

    // ============================================================
    // ФОТО ПРЕДМЕТОВ — UPLOAD
    // ============================================================

    private suspend fun uploadUnsyncedImages() {
        val t0 = System.currentTimeMillis()

        if (!repository.isNetworkAvailable()) {
            Logger.log(TAG, "uploadUnsyncedImages: network blocked, skipping")
            return
        }

        val appContext = appContext()
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

            onSyncProgressChanged(
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

        val appContext = appContext()
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

            onSyncProgressChanged(
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

    // ============================================================
    // ФОТО ПРЕДМЕТОВ — DOWNLOAD
    // ============================================================

    private suspend fun syncImages() {
        if (!repository.isNetworkAvailable()) {
            Logger.log(TAG, "syncImages: network blocked, skipping")
            return
        }

        val appContext = appContext()
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
            onSyncProgressChanged(
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
    }

    private suspend fun syncFolderImages() {
        if (!repository.isNetworkAvailable()) {
            Logger.log(TAG, "syncFolderImages: network blocked, skipping")
            return
        }

        val appContext = appContext()
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
            onSyncProgressChanged(
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

    // ============================================================
    // ТОКЕН / СЕТЬ
    // ============================================================

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

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = appContext()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
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

    // ============================================================
    // MERGE
    // ============================================================

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
                    val ctx = appContext()
                    val diskItemIds = diskItems.map { it.id }.toSet()
                    val localOnlyItems = db.itemDao().getAllItemsRaw().filter { it.id !in diskItemIds }
                    for (item in localOnlyItems) {
                        if (item.updatedDate < diskLastModified) {
                            Logger.log(TAG, "mergeData: DELETING local-only item ${item.id} '${item.name}'")
                            db.itemDao().deleteItem(item)
                            ImageUtils.deleteLocalImage(ctx, item.id)
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
}
