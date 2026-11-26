package com.family.base.ui.viewmodel

import android.content.Context
import com.family.base.AppUser
import com.family.base.Config
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeMediaEntity
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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
    // 🆕 v23.4.1: КЛИЕНТ ДЛЯ ДИАГНОСТИКИ WEBDAV
    // Короткий таймаут (5 сек) — чтобы не вешать синк на недоступный ПК.
    // ============================================================
    private val diagnosticClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5L, TimeUnit.SECONDS)
            .readTimeout(5L, TimeUnit.SECONDS)
            .callTimeout(5L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

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

                // ============================================================
                // 🆕 v23.4.1: ДИАГНОСТИКА ДОСТУПНОСТИ ПК (WebDAV)
                // Если USE_LOCAL_WEBDAV_SYNC=true и ПК недоступен —
                // прерываем синк с понятным сообщением (Snackbar).
                // ============================================================
                if (Config.USE_LOCAL_WEBDAV_SYNC && !checkWebDavAvailability()) {
                    Logger.log(TAG, "syncWithDisk: WebDAV (ПК) unavailable, aborting sync")
                    onSyncStatusChanged(SyncStatus.OFFLINE)
                    notifySyncResult(
                        "❌ ПК недоступен.\n\n" +
                            "Проверьте:\n" +
                            "1. ПК включён и подключён к интернету\n" +
                            "2. Rclone запущен (start-baza-server.bat)\n" +
                            "3. Телефон в сети ZeroTier (зелёный статус)\n" +
                            "4. На мобильном интернете ZeroTier может блокироваться оператором — попробуйте Wi-Fi"
                    )
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
                val currentUser = tokenStorage.getCurrentUser()
                val isMaster = AppUser.canDelete(currentUser)

                Logger.log(
                    TAG,
                    "syncWithDisk: local items=$localItemsCount, folders=$localFoldersCount, " +
                        "outboxPending=$outboxPendingCount, localModified=$localLastModified, " +
                        "diskModified=${diskLastModified ?: "null (network error)"}, " +
                        "isFirstLaunch=$isFirstLaunch, user=$currentUser, isMaster=$isMaster"
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
                    syncRecipeMedia()
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

                onSyncProgressChanged(SyncProgress(SyncPhase.DOWNLOADING, 0, 1, "Проверка данных на Диске…"))
                val tDownload = System.currentTimeMillis()
                val downloadResult = repository.downloadDataFromDisk()
                Logger.log(TAG, "TIMING: downloadDataFromDisk took ${System.currentTimeMillis() - tDownload}ms")

                val shouldDownload = (diskLastModified != null) && (diskLastModified > localLastModified)
                val diskHasLessItems = downloadResult.items.size < localItemsCount
                val diskHasLessFolders = downloadResult.folders.size < localFoldersCount
                val diskHasMoreItems = downloadResult.items.size > localItemsCount
                val diskHasMoreFolders = downloadResult.folders.size > localFoldersCount
                val forceMerge = diskHasMoreItems || diskHasMoreFolders
                val isMasterNeedsUpload = isMaster && (diskHasLessItems || diskHasLessFolders) && outboxPendingCount == 0

                Logger.log(
                    TAG,
                    "syncWithDisk: shouldDownload=$shouldDownload, forceMerge=$forceMerge, " +
                        "isMaster=$isMaster, isMasterNeedsUpload=$isMasterNeedsUpload, " +
                        "diskItems=${downloadResult.items.size} vs localItems=$localItemsCount, " +
                        "diskFolders=${downloadResult.folders.size} vs localFolders=$localFoldersCount"
                )

                if (isMasterNeedsUpload) {
                    Logger.log(
                        TAG,
                        "syncWithDisk: ЭТАЛОН — на Диске меньше данных " +
                            "(diskItems=${downloadResult.items.size} < localItems=$localItemsCount), " +
                            "заливаем локальную базу на Диск"
                    )
                    onSyncProgressChanged(
                        SyncProgress(
                            SyncPhase.UPLOADING_PHOTOS,
                            0,
                            1,
                            "Заливка эталонной базы на Диск…"
                        )
                    )
                    val uploadResult = withContext(Dispatchers.IO) {
                        val items = db.itemDao().getAllItemsRaw()
                        val folders = db.folderDao().getAllFolders()
                        val history = db.historyDao().getAllEntries()
                        val tasks = db.taskDao().getAllForSync()
                        repository.uploadDataToDisk(items, folders, history, tasks)
                    }
                    Logger.log(TAG, "syncWithDisk: uploadDataToDisk result=$uploadResult")
                    if (uploadResult) {
                        val newLastModified = repository.getDiskLastModified() ?: 0L
                        if (newLastModified > 0L) {
                            withContext(Dispatchers.IO) { syncInfoDao.setLastModified(newLastModified) }
                        }
                        uploadedCount += localItemsCount + localFoldersCount
                    }
                } else if (shouldDownload || forceMerge) {
                    if (forceMerge && !shouldDownload) {
                        Logger.log(TAG, "syncWithDisk: FORCE merge — на Диске больше данных, чем локально")
                    }
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

                val tUploadImages = System.currentTimeMillis()
                uploadUnsyncedImages()
                Logger.log(TAG, "TIMING: uploadUnsyncedImages took ${System.currentTimeMillis() - tUploadImages}ms")

                val tUploadFolderImages = System.currentTimeMillis()
                uploadUnsyncedFolderImages()
                Logger.log(TAG, "TIMING: uploadUnsyncedFolderImages took ${System.currentTimeMillis() - tUploadFolderImages}ms")

                val tUploadRecipeMedia = System.currentTimeMillis()
                uploadUnsyncedRecipeMedia()
                Logger.log(TAG, "TIMING: uploadUnsyncedRecipeMedia took ${System.currentTimeMillis() - tUploadRecipeMedia}ms")

                val tSyncImages = System.currentTimeMillis()
                syncImages()
                Logger.log(TAG, "TIMING: syncImages took ${System.currentTimeMillis() - tSyncImages}ms")

                val tSyncFolderImages = System.currentTimeMillis()
                syncFolderImages()
                Logger.log(TAG, "TIMING: syncFolderImages took ${System.currentTimeMillis() - tSyncFolderImages}ms")

                val tSyncRecipeMedia = System.currentTimeMillis()
                syncRecipeMedia()
                Logger.log(TAG, "TIMING: syncRecipeMedia took ${System.currentTimeMillis() - tSyncRecipeMedia}ms")

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

        val unsyncedMediaCount = withContext(Dispatchers.IO) {
            db.recipeMediaDao().getAllRaw().count { !it.imageSynced && !it.isDeleted }
        }

        if (pendingCount > 0 || unsyncedMediaCount > 0) {
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
                "pending=$pendingCount, unsyncedMedia=$unsyncedMediaCount, " +
                "localItems=$localItemsCount, localFolders=$localFoldersCount"
        )

        if (forceSyncRequested) {
            val msg = buildString {
                append("✅ Синхронизация завершена\n")
                append("⬆️ Отправлено: $uploadedCount\n")
                append("⬇️ Получено: $downloadedCount")
                if (pendingCount > 0) append("\n⏳ Осталось в очереди: $pendingCount")
                if (unsyncedMediaCount > 0) append("\n⏳ Медиа в очереди: $unsyncedMediaCount")
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
    // 🆕 v23.4.1: ДИАГНОСТИКА ДОСТУПНОСТИ ПК (WebDAV)
    // ============================================================
    /**
     * Быстрая проверка доступности локального WebDAV-сервера (Rclone на ПК).
     * Делает HEAD-запрос к корню /BAZA/ с таймаутом 5 секунд.
     *
     * @return true — ПК доступен и отвечает; false — недоступен (выключен,
     *         Rclone не запущен, ZeroTier не работает, оператор блокирует).
     */
    private suspend fun checkWebDavAvailability(): Boolean = withContext(Dispatchers.IO) {
        val url = "${Config.LOCAL_WEBDAV_URL}${Config.SHARED_FOLDER_NAME}/"
        Logger.log(TAG, "checkWebDavAvailability: HEAD $url")
        val request = Request.Builder().url(url).head().build()
        try {
            val response = diagnosticClient.newCall(request).execute()
            val code = response.code
            response.close()
            // 200/207/401/403/405 = сервер работает (401 = неверный пароль,
            // но это проблема не доступности, а конфигурации — тоже считаем доступным)
            val available = code == 200 || code == 207 || code == 401 || code == 403 || code == 405
            Logger.log(TAG, "checkWebDavAvailability: code=$code, available=$available")
            available
        } catch (e: Exception) {
            Logger.log(TAG, "checkWebDavAvailability: FAILED ${e.message}")
            false
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
            if (item.imageSynced) return@filter false
            val f = ImageUtils.getLocalImageFile(appContext, item.id)
            f != null && f.exists() && f.length() > 0L
        }

        if (itemsWithLocalPhoto.isEmpty()) {
            Logger.log(TAG, "No unsynced local photos to upload (all imageSynced=true or no local file)")
            Logger.log(TAG, "TIMING: uploadUnsyncedImages (empty) took ${System.currentTimeMillis() - t0}ms")
            return
        }

        Logger.log(TAG, "Checking ${itemsWithLocalPhoto.size} unsynced photos (imageSynced=false)")

        var uploadedCount = 0
        var skippedCount = 0
        var failedCount = 0
        val total = itemsWithLocalPhoto.size
        val progressEvery = 25

        for (index in itemsWithLocalPhoto.indices) {
            val item = itemsWithLocalPhoto[index]

            if (index == 0 || (index + 1) % progressEvery == 0) {
                val elapsed = System.currentTimeMillis() - t0
                Logger.log(
                    TAG,
                    "Images progress: ${index + 1}/$total, uploaded=$uploadedCount, " +
                        "skipped=$skippedCount, failed=$failedCount, elapsed=${elapsed}ms"
                )
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
            if (localFile == null || !localFile.exists()) {
                skippedCount++
                continue
            }

            try {
                val bytes = localFile.readBytes()
                val success = repository.uploadItemImage(item.id, bytes)
                if (success) {
                    uploadedCount++
                } else {
                    if (!repository.isNetworkAvailable()) {
                        val elapsed = System.currentTimeMillis() - t0
                        Logger.log(
                            TAG,
                            "uploadUnsyncedImages: network error on upload at ${index + 1}/$total, aborting " +
                                "(uploaded=$uploadedCount, skipped=$skippedCount, failed=$failedCount, elapsed=${elapsed}ms)"
                        )
                        break
                    }
                    failedCount++
                }
            } catch (e: Exception) {
                if (!repository.isNetworkAvailable()) {
                    val elapsed = System.currentTimeMillis() - t0
                    Logger.log(
                        TAG,
                        "uploadUnsyncedImages: network error on upload at ${index + 1}/$total, aborting " +
                            "(uploaded=$uploadedCount, skipped=$skippedCount, failed=$failedCount, elapsed=${elapsed}ms): ${e.message}"
                    )
                    break
                }
                failedCount++
                Logger.log(TAG, "Failed to upload image ${item.id}: ${e.message}")
            }

            // 🆕 v23.4.1: пауза между загрузками — защита от OOM при большом количестве файлов
            delay(100)
        }

        val elapsed = System.currentTimeMillis() - t0
        Logger.log(
            TAG,
            "Images upload: uploaded=$uploadedCount, skipped=$skippedCount, " +
                "failed=$failedCount, total=$total, elapsed=${elapsed}ms"
        )
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
            if (folder.iconSynced) return@filter false
            val f = ImageUtils.getLocalImageFile(appContext, "folder_${folder.id}")
            f != null && f.exists() && f.length() > 0L
        }

        if (foldersWithLocalIcon.isEmpty()) {
            Logger.log(TAG, "No unsynced local folder icons to upload (all iconSynced=true or no local file)")
            Logger.log(TAG, "TIMING: uploadUnsyncedFolderImages (empty) took ${System.currentTimeMillis() - t0}ms")
            return
        }

        Logger.log(TAG, "Checking ${foldersWithLocalIcon.size} unsynced folder icons (iconSynced=false)")

        var uploadedCount = 0
        var skippedCount = 0
        var failedCount = 0
        val total = foldersWithLocalIcon.size
        val progressEvery = 25

        for (index in foldersWithLocalIcon.indices) {
            val folder = foldersWithLocalIcon[index]

            if (index == 0 || (index + 1) % progressEvery == 0) {
                val elapsed = System.currentTimeMillis() - t0
                Logger.log(
                    TAG,
                    "Folder icons progress: ${index + 1}/$total, uploaded=$uploadedCount, " +
                        "skipped=$skippedCount, failed=$failedCount, elapsed=${elapsed}ms"
                )
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
            if (localFile == null || !localFile.exists()) {
                skippedCount++
                continue
            }

            try {
                val bytes = localFile.readBytes()
                val success = repository.uploadFolderImage(folder.id, bytes)
                if (success) {
                    uploadedCount++
                } else {
                    if (!repository.isNetworkAvailable()) {
                        val elapsed = System.currentTimeMillis() - t0
                        Logger.log(
                            TAG,
                            "uploadUnsyncedFolderImages: network error on upload at ${index + 1}/$total, aborting " +
                                "(uploaded=$uploadedCount, skipped=$skippedCount, failed=$failedCount, elapsed=${elapsed}ms)"
                        )
                        break
                    }
                    failedCount++
                }
            } catch (e: Exception) {
                if (!repository.isNetworkAvailable()) {
                    val elapsed = System.currentTimeMillis() - t0
                    Logger.log(
                        TAG,
                        "uploadUnsyncedFolderImages: network error on upload at ${index + 1}/$total, aborting " +
                            "(uploaded=$uploadedCount, skipped=$skippedCount, failed=$failedCount, elapsed=${elapsed}ms): ${e.message}"
                    )
                    break
                }
                failedCount++
                Logger.log(TAG, "Failed to upload folder image ${folder.id}: ${e.message}")
            }

            // 🆕 v23.4.1: пауза между загрузками — защита от OOM
            delay(100)
        }

        val elapsed = System.currentTimeMillis() - t0
        Logger.log(
            TAG,
            "Folder icons upload: uploaded=$uploadedCount, skipped=$skippedCount, " +
                "failed=$failedCount, total=$total, elapsed=${elapsed}ms"
        )
        Logger.log(TAG, "TIMING: uploadUnsyncedFolderImages took ${System.currentTimeMillis() - t0}ms")
    }

    // ============================================================
    // МЕДИА РЕЦЕПТОВ — UPLOAD
    // ============================================================
    private suspend fun uploadUnsyncedRecipeMedia() {
        val t0 = System.currentTimeMillis()

        if (!repository.isNetworkAvailable()) {
            Logger.log(TAG, "uploadUnsyncedRecipeMedia: network blocked, skipping")
            return
        }

        val appContext = appContext()
        val allMedia = withContext(Dispatchers.IO) { db.recipeMediaDao().getAllRaw() }
        val mediaWithLocalFile = allMedia.filter { media ->
            if (media.imageSynced) return@filter false
            if (media.isDeleted) return@filter false
            val name = media.imageUrl
            if (name.isNullOrBlank()) return@filter false
            val f = ImageUtils.getRecipeMediaFile(appContext, media.recipeId, name)
            f.exists() && f.length() > 0L
        }

        if (mediaWithLocalFile.isEmpty()) {
            Logger.log(TAG, "uploadUnsyncedRecipeMedia: nothing to upload (all imageSynced=true or no local file)")
            Logger.log(TAG, "TIMING: uploadUnsyncedRecipeMedia (empty) took ${System.currentTimeMillis() - t0}ms")
            return
        }

        Logger.log(TAG, "uploadUnsyncedRecipeMedia: ${mediaWithLocalFile.size} files to upload")

        var uploadedCount = 0
        var skippedCount = 0
        var failedCount = 0
        val total = mediaWithLocalFile.size

        for (index in mediaWithLocalFile.indices) {
            val media = mediaWithLocalFile[index]

            if (index == 0 || (index + 1) % 10 == 0) {
                val elapsed = System.currentTimeMillis() - t0
                Logger.log(
                    TAG,
                    "Recipe media progress: ${index + 1}/$total, uploaded=$uploadedCount, " +
                        "skipped=$skippedCount, failed=$failedCount, elapsed=${elapsed}ms"
                )
            }

            onSyncProgressChanged(
                SyncProgress(
                    SyncPhase.UPLOADING_PHOTOS,
                    index + 1,
                    total,
                    "Загрузка медиа рецепта…"
                )
            )

            val fileName = media.imageUrl
            if (fileName.isNullOrBlank()) {
                skippedCount++
                continue
            }

            val localFile = ImageUtils.getRecipeMediaFile(appContext, media.recipeId, fileName)
            if (!localFile.exists() || localFile.length() == 0L) {
                skippedCount++
                continue
            }

            try {
                val bytes = localFile.readBytes()
                val success = repository.uploadRecipeMedia(
                    recipeId = media.recipeId,
                    fileName = fileName,
                    bytes = bytes,
                    mimeType = media.mimeType
                )

                if (success) {
                    uploadedCount++

                    val thumbName = media.thumbnailUrl
                    if (!thumbName.isNullOrBlank()) {
                        val thumbFile = ImageUtils.getRecipeThumbFile(appContext, media.recipeId, thumbName)
                        if (thumbFile.exists() && thumbFile.length() > 0L) {
                            val thumbBytes = thumbFile.readBytes()
                            val thumbOk = repository.uploadRecipeMedia(
                                recipeId = media.recipeId,
                                fileName = thumbName,
                                bytes = thumbBytes,
                                mimeType = "image/jpeg"
                            )
                            Logger.log(
                                TAG,
                                "uploadUnsyncedRecipeMedia: thumb for media=${media.id} ($thumbName) " +
                                    "uploaded=$thumbOk"
                            )
                        }
                    }
                } else {
                    if (!repository.isNetworkAvailable()) {
                        Logger.log(
                            TAG,
                            "uploadUnsyncedRecipeMedia: network error at ${index + 1}/$total, aborting " +
                                "(uploaded=$uploadedCount, skipped=$skippedCount, failed=$failedCount)"
                        )
                        break
                    }
                    failedCount++
                }
            } catch (e: Exception) {
                if (!repository.isNetworkAvailable()) {
                    Logger.log(
                        TAG,
                        "uploadUnsyncedRecipeMedia: network error at ${index + 1}/$total, aborting: ${e.message}"
                    )
                    break
                }
                failedCount++
                Logger.log(TAG, "Failed to upload recipe media ${media.id}: ${e.message}")
            }

            // 🆕 v23.4.1: пауза между загрузками — защита от OOM при больших видео.
            // OkHttp буферизует тело ответа в памяти; без паузы GC не успевает
            // освободить память после предыдущего файла, и Android убивает процесс.
            delay(100)
        }

        val elapsed = System.currentTimeMillis() - t0
        Logger.log(
            TAG,
            "Recipe media upload: uploaded=$uploadedCount, skipped=$skippedCount, " +
                "failed=$failedCount, total=$total, elapsed=${elapsed}ms"
        )
        Logger.log(TAG, "TIMING: uploadUnsyncedRecipeMedia took ${System.currentTimeMillis() - t0}ms")
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
                        if (folder.iconUrl.isNullOrEmpty() || !folder.iconSynced) {
                            val updated = folder.copy(
                                iconUrl = folder.iconUrl ?: "folder_${folder.id}.jpg",
                                iconSynced = true
                            )
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
    // МЕДИА РЕЦЕПТОВ — DOWNLOAD
    // ============================================================
    private suspend fun syncRecipeMedia() {
        if (!repository.isNetworkAvailable()) {
            Logger.log(TAG, "syncRecipeMedia: network blocked, skipping")
            return
        }

        val appContext = appContext()
        val allMedia = withContext(Dispatchers.IO) { db.recipeMediaDao().getAllRaw() }
        val toDownload = allMedia.filter { media ->
            if (media.isDeleted) return@filter false
            val name = media.imageUrl
            if (name.isNullOrBlank()) return@filter false
            val f = ImageUtils.getRecipeMediaFile(appContext, media.recipeId, name)
            !f.exists() || f.length() == 0L
        }

        if (toDownload.isEmpty()) {
            Logger.log(TAG, "syncRecipeMedia: nothing to download")
            return
        }

        Logger.log(TAG, "syncRecipeMedia: ${toDownload.size} files to download")

        var downloadedCount = 0
        var failedCount = 0
        var notFoundOnDisk = 0
        val total = toDownload.size

        toDownload.forEachIndexed { index, media ->
            onSyncProgressChanged(
                SyncProgress(
                    SyncPhase.DOWNLOADING_PHOTOS,
                    index + 1,
                    total,
                    "Скачивание медиа рецепта…"
                )
            )

            val fileName = media.imageUrl
            if (fileName.isNullOrBlank()) return@forEachIndexed

            try {
                val dir = ImageUtils.ensureRecipeMediaDir(appContext, media.recipeId)
                if (dir == null) {
                    failedCount++
                    return@forEachIndexed
                }

                val bytes = repository.downloadRecipeMediaBytes(media.recipeId, fileName)
                if (bytes == null) {
                    notFoundOnDisk++
                    if (media.imageSynced) {
                        val updated = media.copy(imageSynced = false)
                        withContext(Dispatchers.IO) { db.recipeMediaDao().updateMedia(updated) }
                        Logger.log(TAG, "syncRecipeMedia: reset imageSynced=false for ${media.id} (404)")
                    }
                    return@forEachIndexed
                }

                val localFile = File(dir, fileName)
                localFile.outputStream().use { it.write(bytes) }
                downloadedCount++
                Logger.log(TAG, "syncRecipeMedia: OK ${media.recipeId}/$fileName (${bytes.size} bytes)")

                if (!media.imageSynced) {
                    val updated = media.copy(imageSynced = true)
                    withContext(Dispatchers.IO) { db.recipeMediaDao().updateMedia(updated) }
                }

                val thumbName = media.thumbnailUrl
                if (!thumbName.isNullOrBlank()) {
                    val thumbFile = ImageUtils.getRecipeThumbFile(appContext, media.recipeId, thumbName)
                    if (!thumbFile.exists() || thumbFile.length() == 0L) {
                        val thumbBytes = repository.downloadRecipeMediaBytes(media.recipeId, thumbName)
                        if (thumbBytes != null) {
                            thumbFile.outputStream().use { it.write(thumbBytes) }
                            Logger.log(TAG, "syncRecipeMedia: thumb OK ${media.recipeId}/$thumbName")
                        }
                    }
                }
            } catch (e: Exception) {
                failedCount++
                Logger.log(TAG, "syncRecipeMedia: error for ${media.id}: ${e.message}", e)
            }
        }

        Logger.log(
            TAG,
            "syncRecipeMedia: downloaded=$downloadedCount, notFoundOnDisk=$notFoundOnDisk, " +
                "failed=$failedCount, total=$total"
        )
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
    private companion object {
        const val FOLDERS_INCOMPLETE_RATIO = 0.7
        const val FOLDERS_INCOMPLETE_MIN_DIFF = 20
        const val ITEMS_INCOMPLETE_RATIO = 0.7
        const val ITEMS_INCOMPLETE_MIN_DIFF = 50
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

            // ============================================================
            // 🛡️ ЗАЩИТА ОТ НЕПОЛНЫХ ДАННЫХ (инцидент 16.10.2026)
            // ============================================================
            val foldersAreMissing = diskFolders.isEmpty() && localFoldersCount > 0
            if (foldersAreMissing) {
                Logger.log(
                    TAG,
                    "mergeData: !!! FOLDERS MISSING ON DISK (disk=0, local=$localFoldersCount) — " +
                        "SKIP ALL merge (folders + items + history + tasks) to protect local data"
                )
                return@withContext
            }

            val diskFoldersMaxUpdated = diskFolders.maxOfOrNull { it.updatedAt } ?: 0L
            val localFoldersMaxUpdated = db.folderDao().getAllFolders().maxOfOrNull { it.updatedAt } ?: 0L
            val diskFoldersAreOlder = diskFolders.isNotEmpty() &&
                diskFoldersMaxUpdated < localFoldersMaxUpdated

            val foldersLookIncomplete = localFoldersCount > FOLDERS_INCOMPLETE_MIN_DIFF &&
                diskFolders.size < localFoldersCount * FOLDERS_INCOMPLETE_RATIO

            if (diskFoldersAreOlder) {
                Logger.log(
                    TAG,
                    "mergeData: disk folders are OLDER than local " +
                        "(disk max=$diskFoldersMaxUpdated, local max=$localFoldersMaxUpdated) — " +
                        "will merge but NOT delete local-only folders"
                )
            }

            if (foldersLookIncomplete) {
                Logger.log(
                    TAG,
                    "mergeData: !!! FOLDERS LOOK INCOMPLETE ON DISK " +
                        "(disk=${diskFolders.size}, local=$localFoldersCount, " +
                        "ratio=${diskFolders.size.toDouble() / localFoldersCount}) — " +
                        "SKIP ALL merge to protect local data"
                )
                return@withContext
            }

            val diskItemsMaxUpdated = diskItems.maxOfOrNull { it.updatedDate } ?: 0L
            val localItemsMaxUpdated = db.itemDao().getAllItemsRaw().maxOfOrNull { it.updatedDate } ?: 0L
            val diskItemsAreOlder = diskItems.isNotEmpty() &&
                diskItemsMaxUpdated < localItemsMaxUpdated

            val itemsLookIncomplete = localItemsCount > ITEMS_INCOMPLETE_MIN_DIFF &&
                diskItems.size < localItemsCount * ITEMS_INCOMPLETE_RATIO

            if (diskItemsAreOlder) {
                Logger.log(
                    TAG,
                    "mergeData: disk items are OLDER than local " +
                        "(disk max=$diskItemsMaxUpdated, local max=$localItemsMaxUpdated) — " +
                        "will merge but NOT delete local-only items"
                )
            }

            if (itemsLookIncomplete) {
                Logger.log(
                    TAG,
                    "mergeData: !!! ITEMS LOOK INCOMPLETE ON DISK " +
                        "(disk=${diskItems.size}, local=$localItemsCount, " +
                        "ratio=${diskItems.size.toDouble() / localItemsCount}) — " +
                        "SKIP ALL merge to protect local data"
                )
                return@withContext
            }

            // ============================================================
            // FOLDERS MERGE
            // ============================================================
            if (foldersError && localFoldersCount > 0) {
                Logger.log(TAG, "mergeData: folders download error (local=$localFoldersCount) — SKIP folders merge")
            } else {
                diskFolders.forEach { diskFolder ->
                    val local = db.folderDao().getFolderById(diskFolder.id)

                    if (local != null && local.isDeleted && !diskFolder.isDeleted &&
                        local.updatedAt >= diskFolder.updatedAt
                    ) {
                        Logger.log(
                            TAG,
                            "mergeData: folder SKIP tombstone (local=${local.id}, " +
                                "local.updatedAt=${local.updatedAt} >= disk=${diskFolder.updatedAt})"
                        )
                        return@forEach
                    }

                    if (local != null && local.isSystem && diskFolder.isDeleted) {
                        Logger.log(TAG, "mergeData: folder SKIP tombstone for system folder ${diskFolder.id}")
                        return@forEach
                    }

                    if (diskFolder.isDeleted) {
                        val mergedTombstone = diskFolder.copy(deleteConfirmed = true)
                        if (local == null) {
                            db.folderDao().insertFolder(mergedTombstone)
                            Logger.log(TAG, "mergeData: folder INSERT tombstone ${diskFolder.id}")
                        } else if (diskFolder.updatedAt > local.updatedAt) {
                            db.folderDao().updateFolder(mergedTombstone)
                            Logger.log(TAG, "mergeData: folder UPDATE tombstone ${diskFolder.id}")
                        }
                        return@forEach
                    }

                    if (local == null) {
                        db.folderDao().insertFolder(diskFolder)
                    } else if (diskFolder.updatedAt > local.updatedAt) {
                        val merged = diskFolder.copy(
                            iconUrl = diskFolder.iconUrl ?: local.iconUrl,
                            iconSynced = diskFolder.iconSynced,
                            parentItemId = diskFolder.parentItemId ?: local.parentItemId
                        )
                        db.folderDao().updateFolder(merged)
                    }
                }

                if (diskIsNewer && !diskFoldersAreOlder) {
                    val diskFolderIds = diskFolders.map { it.id }.toSet()
                    val localOnlyFolders = db.folderDao().getAllFolders()
                        .filter { it.id !in diskFolderIds }

                    for (folder in localOnlyFolders) {
                        if (folder.isSystem) {
                            Logger.log(TAG, "mergeData: KEEPING system folder ${folder.id} '${folder.name}' (never delete)")
                            continue
                        }
                        if (folder.isDeleted) {
                            Logger.log(TAG, "mergeData: KEEPING local tombstone folder ${folder.id} (own tombstone)")
                            continue
                        }
                        if (folder.updatedAt < diskLastModified) {
                            Logger.log(TAG, "mergeData: TOMBSTONE local-only folder ${folder.id} '${folder.name}'")
                            db.folderDao().markFolderDeleted(
                                folderId = folder.id,
                                date = System.currentTimeMillis(),
                                user = "sync"
                            )
                        }
                    }
                } else if (diskFoldersAreOlder) {
                    Logger.log(TAG, "mergeData: skipping local-only folders deletion (disk folders are older)")
                }
            }

            // ============================================================
            // ITEMS MERGE
            // ============================================================
            if (itemsError && localItemsCount > 0) {
                Logger.log(TAG, "mergeData: items download error (local=$localItemsCount) — SKIP items merge")
            } else {
                diskItems.forEach { diskItem ->
                    val local = db.itemDao().getItemById(diskItem.id)

                    if (local != null && local.isDeleted && !diskItem.isDeleted &&
                        local.updatedDate >= diskItem.updatedDate
                    ) {
                        Logger.log(
                            TAG,
                            "mergeData: item SKIP tombstone (local=${local.id}, " +
                                "local.updatedDate=${local.updatedDate} >= disk=${diskItem.updatedDate})"
                        )
                        return@forEach
                    }

                    if (diskItem.isDeleted) {
                        val mergedTombstone = diskItem.copy(deleteConfirmed = true)
                        if (local == null) {
                            db.itemDao().insertItem(mergedTombstone)
                            Logger.log(TAG, "mergeData: item INSERT tombstone ${diskItem.id}")
                        } else if (diskItem.updatedDate > local.updatedDate) {
                            db.itemDao().updateItem(mergedTombstone)
                            Logger.log(TAG, "mergeData: item UPDATE tombstone ${diskItem.id}")
                        }
                        return@forEach
                    }

                    if (local == null) {
                        db.itemDao().insertItem(diskItem)
                    } else if (diskItem.updatedDate > local.updatedDate) {
                        val merged = diskItem.copy(
                            imageUrl = diskItem.imageUrl ?: local.imageUrl,
                            imageSynced = diskItem.imageSynced,
                            parentItemId = diskItem.parentItemId ?: local.parentItemId
                        )
                        db.itemDao().updateItem(merged)
                    }
                }

                if (diskIsNewer && !diskItemsAreOlder) {
                    val diskItemIds = diskItems.map { it.id }.toSet()
                    val localOnlyItems = db.itemDao().getAllItemsRaw()
                        .filter { it.id !in diskItemIds }

                    for (item in localOnlyItems) {
                        if (item.isDeleted) {
                            continue
                        }
                        if (item.updatedDate < diskLastModified) {
                            Logger.log(TAG, "mergeData: TOMBSTONE local-only item ${item.id} '${item.name}'")
                            db.itemDao().markItemDeleted(
                                itemId = item.id,
                                date = System.currentTimeMillis(),
                                user = "sync"
                            )
                        }
                    }
                } else if (diskItemsAreOlder) {
                    Logger.log(TAG, "mergeData: skipping local-only items deletion (disk items are older)")
                }
            }

            // ============================================================
            // HISTORY MERGE
            // ============================================================
            if (historyError && diskHistory.isEmpty()) {
                Logger.log(TAG, "mergeData: history download error — SKIP history merge")
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

            // ============================================================
            // TASKS MERGE
            // ============================================================
            if (tasksError && diskTasks.isEmpty()) {
                Logger.log(TAG, "mergeData: tasks download error — SKIP tasks merge")
            } else if (diskTasks.isEmpty()) {
                Logger.log(TAG, "mergeData: disk tasks empty — nothing to merge")
            } else {
                val localTasks = db.taskDao().getAllForSync()
                val localMap = localTasks.associateBy { it.id }

                var insertedCount = 0
                var updatedCount = 0
                var tombstonedCount = 0
                var remindAtUpdatedCount = 0

                diskTasks.forEach { diskTask ->
                    val local = localMap[diskTask.id]

                    if (local != null && local.isDeleted && !diskTask.isDeleted &&
                        local.updatedAt >= diskTask.updatedAt
                    ) {
                        Logger.log(
                            TAG,
                            "mergeData: task SKIP tombstone (local=${local.id}, " +
                                "local.updatedAt=${local.updatedAt} >= disk=${diskTask.updatedAt})"
                        )
                        return@forEach
                    }

                    if (diskTask.isDeleted) {
                        val mergedTombstone = diskTask.copy(deleteConfirmed = true)
                        if (local == null) {
                            db.taskDao().insert(mergedTombstone)
                            tombstonedCount++
                        } else if (diskTask.updatedAt > local.updatedAt) {
                            db.taskDao().update(mergedTombstone)
                            tombstonedCount++
                        }
                        return@forEach
                    }

                    if (local == null) {
                        db.taskDao().insert(diskTask)
                        insertedCount++
                    } else if (diskTask.updatedAt > local.updatedAt) {
                        if (diskTask.remindAt != local.remindAt ||
                            diskTask.autoRemindEnabled != local.autoRemindEnabled
                        ) {
                            remindAtUpdatedCount++
                            Logger.log(
                                TAG,
                                "mergeData: task UPDATE reminds (id=${diskTask.id}, " +
                                    "remindAt ${local.remindAt} -> ${diskTask.remindAt}, " +
                                    "autoRemind ${local.autoRemindEnabled} -> ${diskTask.autoRemindEnabled})"
                            )
                        }
                        db.taskDao().update(diskTask)
                        updatedCount++
                    }
                }

                Logger.log(
                    TAG,
                    "mergeData: tasks merged (inserted=$insertedCount, updated=$updatedCount, " +
                        "tombstoned=$tombstonedCount, remindUpdated=$remindAtUpdatedCount, " +
                        "local=${localTasks.size}, disk=${diskTasks.size})"
                )
            }

            if (diskIsNewer) {
                syncInfoDao.setLastModified(diskLastModified)
            }
        }
    }

    // ============================================================
    // cleanup рецептов и медиа
    // ============================================================
    suspend fun cleanupRecipeTombstonesLocal(cutoff: Long) {
        val appContext = appContext()

        try {
            val mediaIds = db.recipeMediaDao().getConfirmedMediaOlderThan(cutoff)
            if (mediaIds.isNotEmpty()) {
                val allMedia = db.recipeMediaDao().getAllRaw()
                val byId = allMedia.associateBy { it.id }
                for (id in mediaIds) {
                    val m = byId[id] ?: continue
                    try {
                        ImageUtils.deleteRecipeMediaFile(appContext, m.recipeId, m.imageUrl)
                        ImageUtils.deleteRecipeMediaFile(appContext, m.recipeId, m.thumbnailUrl)
                    } catch (e: Exception) {
                        Logger.log(TAG, "cleanupRecipeTombstonesLocal: local media delete error for $id: ${e.message}")
                    }
                }
                db.recipeMediaDao().hardDeleteConfirmedMedia(mediaIds)
                Logger.log(TAG, "cleanupRecipeTombstonesLocal: removed ${mediaIds.size} media rows")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanupRecipeTombstonesLocal: media error: ${e.message}")
        }

        try {
            val recipeIds = db.recipeDao().getConfirmedRecipesOlderThan(cutoff)
            if (recipeIds.isNotEmpty()) {
                for (rid in recipeIds) {
                    try {
                        ImageUtils.deleteRecipeMediaDir(appContext, rid)
                    } catch (e: Exception) {
                        Logger.log(TAG, "cleanupRecipeTombstonesLocal: dir delete error for $rid: ${e.message}")
                    }
                }
                db.recipeMediaDao().hardDeleteByRecipeIds(recipeIds)
                db.recipeDao().hardDeleteConfirmedRecipes(recipeIds)
                Logger.log(TAG, "cleanupRecipeTombstonesLocal: removed ${recipeIds.size} recipes")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "cleanupRecipeTombstonesLocal: recipes error: ${e.message}")
        }
    }
}
