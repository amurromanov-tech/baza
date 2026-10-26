package com.family.base.util

import android.content.Context
import android.os.Environment
import com.family.base.data.BackupData
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxHelper
import com.family.base.data.local.OutboxLogger
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.SettingsEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BackupManagerV2(private val context: Context) {

    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private val TAG = "BackupManagerV2"

    // ===== ПУТИ (локальные, не Яндекс.Диск) =====
    private fun getBackupDir(): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val backupDir = File(downloadDir, "BAZA/backup")
        if (!backupDir.exists()) backupDir.mkdirs()
        return backupDir
    }

    private fun getBackupFileName(): String {
        val timestamp = dateFormat.format(Date())
        return "baza_backup_$timestamp.json"
    }

    // ===== ЭКСПОРТ =====
    suspend fun exportToLocal(db: AppDatabase, folderName: String): File? = withContext(Dispatchers.IO) {
        try {
            val folders = db.folderDao().getAllFolders()
            val allItems = db.itemDao().getAllItemsRaw()   // ← 🆕 берём ВСЁ, включая архивные
            val history = db.historyDao().getAllEntries()
            val settings = db.settingsDao().getSettings()

            val backupData = BackupData(
                folders = folders,
                items = allItems,
                history = history,
                settings = settings,
                folderName = folderName
            )

            val json = gson.toJson(backupData)
            val backupFile = File(getBackupDir(), getBackupFileName())
            backupFile.writeText(json)

            Logger.log(TAG, "Export to local: ${backupFile.absolutePath} (folders=${folders.size}, items=${allItems.size})")
            return@withContext backupFile
        } catch (e: Exception) {
            Logger.log(TAG, "Export to local failed", e)
            return@withContext null
        }
    }

    suspend fun exportToCloud(db: AppDatabase, folderName: String, tokenStorage: TokenStorage): Boolean = withContext(Dispatchers.IO) {
        try {
            val localFile = exportToLocal(db, folderName) ?: return@withContext false
            val bytes = localFile.readBytes()

            val token = tokenStorage.getAccessToken()
            if (token == null) {
                Logger.log(TAG, "No token, cannot upload to cloud")
                return@withContext false
            }

            val api = com.family.base.data.remote.YandexDiskApi.getInstance()
            val auth = "OAuth $token"
            val path = DiskPaths.backupFile(localFile.name)

            val createFolderResponse = api.createFolder(auth, DiskPaths.BACKUP)
            if (!createFolderResponse.isSuccessful && createFolderResponse.code() != 409) {
                Logger.log(TAG, "Failed to create backup folder: ${createFolderResponse.code()}")
                return@withContext false
            }

            val uploadResponse = DiskUploader.uploadFile(api, auth, path, bytes)
            if (uploadResponse) {
                Logger.log(TAG, "Export to cloud: $path")
                return@withContext true
            }

            Logger.log(TAG, "Export to cloud failed")
            return@withContext false
        } catch (e: Exception) {
            Logger.log(TAG, "Export to cloud failed", e)
            return@withContext false
        }
    }

    // ===== ИМПОРТ =====
    suspend fun importFromLocal(file: File, db: AppDatabase): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = file.readText()
            val type = object : TypeToken<BackupData>() {}.type
            val backupData: BackupData = gson.fromJson(json, type)

            applyBackupData(db, backupData)

            Logger.log(TAG, "Import from local: ${file.name} (folders=${backupData.folders.size}, items=${backupData.items.size})")
            return@withContext true
        } catch (e: Exception) {
            Logger.log(TAG, "Import from local failed", e)
            return@withContext false
        }
    }

    suspend fun importFromCloud(tokenStorage: TokenStorage, db: AppDatabase): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = tokenStorage.getAccessToken()
            if (token == null) {
                Logger.log(TAG, "No token, cannot import from cloud")
                return@withContext false
            }

            val api = com.family.base.data.remote.YandexDiskApi.getInstance()
            val auth = "OAuth $token"
            val backupPath = DiskPaths.BACKUP

            val listResponse = api.getDiskResources(auth, backupPath)
            if (!listResponse.isSuccessful) {
                Logger.log(TAG, "Failed to list backup files: ${listResponse.code()}")
                return@withContext false
            }

            // ===== embedded.items вместо items =====
            val diskItems = listResponse.body()?.embedded?.items ?: emptyList()
            val backupFiles = diskItems.filter { it.name.endsWith(".json") }

            if (backupFiles.isEmpty()) {
                Logger.log(TAG, "No backup files found")
                return@withContext false
            }

            // ===== сортировка по имени (дата в имени файла) =====
            val latest = backupFiles.maxByOrNull { it.name }
            if (latest == null) {
                Logger.log(TAG, "No backup files found")
                return@withContext false
            }

            val downloadUrlResponse = api.getDiskDownloadUrl(auth, DiskPaths.backupFile(latest.name))
            if (!downloadUrlResponse.isSuccessful) {
                Logger.log(TAG, "Failed to get download URL: ${downloadUrlResponse.code()}")
                return@withContext false
            }

            val downloadUrl = downloadUrlResponse.body()?.href
            if (downloadUrl == null) {
                Logger.log(TAG, "Download URL is null")
                return@withContext false
            }

            val downloadResponse = api.downloadFile(downloadUrl)
            if (!downloadResponse.isSuccessful) {
                Logger.log(TAG, "Failed to download backup: ${downloadResponse.code()}")
                return@withContext false
            }

            val json = downloadResponse.body()?.string()
            if (json == null) {
                Logger.log(TAG, "Downloaded backup is empty")
                return@withContext false
            }

            val type = object : TypeToken<BackupData>() {}.type
            val backupData: BackupData = gson.fromJson(json, type)

            applyBackupData(db, backupData)

            Logger.log(TAG, "Import from cloud: ${latest.name} (folders=${backupData.folders.size}, items=${backupData.items.size})")
            return@withContext true
        } catch (e: Exception) {
            Logger.log(TAG, "Import from cloud failed", e)
            return@withContext false
        }
    }

    // ============================================================
    // 🆕 v20.4.3: ПРИМЕНЕНИЕ БЭКАПА + «РАЗТОМБИРОВАНИЕ» СИНК-УДАЛЕНИЙ
    // ============================================================
    /**
     * Общая логика для importFromLocal и importFromCloud.
     *
     * 1. Полностью очищает локальную БД (folders / items / history).
     * 2. Вставляет данные из бэкапа.
     * 3. 🆕 v20.4.3: «разтомбирует» сущности, которые были удалены
     *    синхронизацией (deletedBy == "sync"). Снимает isDeleted,
     *    deletedAt, deletedBy, deleteConfirmed. Ручные удаления
     *    (deletedBy == "Алексей" / "Рима" и т.п.) НЕ трогает —
     *    это настоящие удаления пользователя.
     * 4. Пишет ВСЕ папки, предметы и историю в outbox (changelog),
     *    чтобы при следующем синке они улетели на Диск и «разтомбились»
     *    там тоже.
     *
     * Это лечит инцидент 26.10.2026: после синка Димы (14-я версия)
     * mergeData затомбировал ~60 папок и десятки предметов с
     * deletedBy="sync". Импорт бэкапа возвращает их в живое состояние.
     */
    private suspend fun applyBackupData(db: AppDatabase, backupData: BackupData) {
        // 1. Очищаем локальные данные
        clearAllData(db)

        // 2. Вставляем папки (с разтомбированием)
        var foldersUntombstoned = 0
        val foldersToInsert = backupData.folders.map { folder ->
            val fixed = untombstoneFolder(folder)
            if (fixed !== folder) foldersUntombstoned++
            fixed
        }
        foldersToInsert.forEach { db.folderDao().insertFolder(it) }

        // 3. Вставляем предметы (с разтомбированием)
        var itemsUntombstoned = 0
        val itemsToInsert = backupData.items.map { item ->
            val fixed = untombstoneItem(item)
            if (fixed !== item) itemsUntombstoned++
            fixed
        }
        itemsToInsert.forEach { db.itemDao().insertItem(it) }

        // 4. Вставляем историю (append-only, не трогаем)
        backupData.history.forEach { db.historyDao().insertEntry(it) }

        // 5. Настройки
        backupData.settings?.let { db.settingsDao().insertOrUpdateSettings(it) }

        Logger.log(
            TAG,
            "applyBackupData: untombstoned folders=$foldersUntombstoned, items=$itemsUntombstoned " +
                "(tombstones by sync only; manual deletes kept)"
        )

        // ============================================================
        // 🆕 v16.2 / v20.4.3: пишем в outbox (changelog).
        // Восстановленные сущности → ACTION_UPDATE (или CREATE, если новые).
        // Так при следующем синке они гарантированно улетят на Диск
        // и «разтомбидят» запись там тоже.
        // ============================================================
        val changedBy = "backup_import"

        var folderQueueCount = 0
        foldersToInsert.forEach { folder ->
            OutboxLogger.folderChanged(
                db = db,
                folder = folder,
                action = OutboxEntity.ACTION_CREATE,
                changedBy = changedBy
            )
            folderQueueCount++
        }

        var itemQueueCount = 0
        itemsToInsert.forEach { item ->
            OutboxLogger.itemChanged(
                db = db,
                item = item,
                action = OutboxEntity.ACTION_CREATE,
                changedBy = changedBy
            )
            itemQueueCount++
        }

        var historyQueueCount = 0
        backupData.history.forEach { entry ->
            // ⚠️ НЕ OutboxLogger.historyAdded — она бы вставила историю в БД повторно.
            // В БД история уже вставлена на шаге 4. Здесь только outbox.
            OutboxHelper.logHistory(db, entry, changedBy)
            historyQueueCount++
        }

        Logger.log(
            TAG,
            "applyBackupData: queued $folderQueueCount folders + $itemQueueCount items + " +
                "$historyQueueCount history for changelog"
        )
    }

    // ============================================================
    // 🆕 v20.4.3: РАЗТОМБИРОВАНИЕ
    // ============================================================

    /**
     * Снимает tombstone-флаги у папки, если она была затомбирована
     * синхронизацией (deletedBy == "sync"), а не пользователем вручную.
     *
     * Системные папки никогда не разтомбируем — они не должны быть удалены.
     * Если папка не была удалена — возвращаем как есть.
     */
    private fun untombstoneFolder(folder: FolderEntity): FolderEntity {
        if (!folder.isDeleted) return folder
        if (folder.isSystem) {
            // Системную папку не должно быть удалено; если tombstone —
            // всё равно восстанавливаем, но без логов.
            return folder.copy(
                isDeleted = false,
                deletedAt = null,
                deletedBy = null,
                deleteConfirmed = false,
                syncStatus = FolderEntity.SYNC_STATUS_PENDING
            )
        }
        if (folder.deletedBy != "sync") {
            // Ручное удаление пользователя — оставляем как есть.
            return folder
        }
        return folder.copy(
            isDeleted = false,
            deletedAt = null,
            deletedBy = null,
            deleteConfirmed = false,
            syncStatus = FolderEntity.SYNC_STATUS_PENDING
        )
    }

    /**
     * Снимает tombstone-флаги у предмета, если он был затомбирован
     * синхронизацией (deletedBy == "sync"), а не пользователем вручную.
     * Если предмет не был удалён — возвращаем как есть.
     */
    private fun untombstoneItem(item: ItemEntity): ItemEntity {
        if (!item.isDeleted) return item
        if (item.deletedBy != "sync") {
            // Ручное удаление пользователя — оставляем как есть.
            return item
        }
        return item.copy(
            isDeleted = false,
            deletedAt = null,
            deletedBy = null,
            deleteConfirmed = false,
            syncStatus = ItemEntity.SYNC_STATUS_PENDING
        )
    }

    // ===== ОЧИСТКА ДАННЫХ =====
    private suspend fun clearAllData(db: AppDatabase) {
        db.folderDao().getAllFolders().forEach { db.folderDao().deleteFolder(it) }
        // ⚠️ getAllItemsRaw() — чтобы чистить и архивные
        db.itemDao().getAllItemsRaw().forEach { db.itemDao().deleteItem(it) }
        db.historyDao().getAllEntries().forEach { db.historyDao().deleteEntry(it) }
    }

    // ===== ПОЛУЧИТЬ СПИСОК ЛОКАЛЬНЫХ БЭКАПОВ =====
    fun getLocalBackups(): List<File> {
        return getBackupDir().listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
    }

    // ===== УДАЛИТЬ БЭКАП =====
    fun deleteLocalBackup(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            false
        }
    }
}
