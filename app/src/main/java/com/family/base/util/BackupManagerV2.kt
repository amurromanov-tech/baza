package com.family.base.util

import android.content.Context
import android.os.Environment
import com.family.base.data.BackupData
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.SettingsEntity
import com.family.base.data.local.entity.SyncQueueEntity
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

    // ===== ПУТИ =====
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
            val path = "/${tokenStorage.getFolderName()}/backup/${localFile.name}"

            val backupFolderPath = "/${tokenStorage.getFolderName()}/backup"
            val createFolderResponse = api.createFolder(auth, backupFolderPath)
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
            val backupPath = "/${tokenStorage.getFolderName()}/backup"

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

            val downloadUrlResponse = api.getDiskDownloadUrl(auth, "$backupPath/${latest.name}")
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
    // 🆕 ПРИМЕНЕНИЕ БЭКАПА + ПОСТАНОВКА В ОЧЕРЕДЬ СИНХРОНИЗАЦИИ
    // ============================================================
    /**
     * Общая логика для importFromLocal и importFromCloud.
     *
     * 1. Полностью очищает локальную БД (folders / items / history).
     * 2. Очищает sync_queue — чтобы старые «хвосты» не смешались с новыми.
     * 3. Вставляет данные из бэкапа.
     * 4. 🆕 Ставит ВСЕ папки и ВСЕ предметы в SyncQueueEntity с action="create".
     *
     * Это нужно, чтобы:
     *   - при следующем синке они гарантированно залились на Диск;
     *   - если сети нет — импорт «запомнился» и зальётся при следующем sync;
     *   - при инкрементальном синке (pending > 0) сработал полный upload.
     */
    private suspend fun applyBackupData(db: AppDatabase, backupData: BackupData) {
        // 1. Очищаем локальные данные
        clearAllData(db)

        // 2. 🆕 Очищаем очередь — она относится к старым данным
        db.syncQueueDao().clearAll()
        Logger.log(TAG, "applyBackupData: sync_queue cleared")

        val now = System.currentTimeMillis()

        // 3. Вставляем папки
        backupData.folders.forEach { db.folderDao().insertFolder(it) }

        // 4. Вставляем предметы (включая архивные)
        backupData.items.forEach { db.itemDao().insertItem(it) }

        // 5. Вставляем историю
        backupData.history.forEach { db.historyDao().insertEntry(it) }

        // 6. Настройки
        backupData.settings?.let { db.settingsDao().insertOrUpdateSettings(it) }

        // ============================================================
        // 🆕 7. СТАВИМ В ОЧЕРЕДЬ СИНХРОНИЗАЦИИ
        // ============================================================
        var folderQueueCount = 0
        backupData.folders.forEach { folder ->
            db.syncQueueDao().addToQueue(
                SyncQueueEntity(
                    entityType = "folder",
                    entityId = folder.id,
                    action = "create",
                    parentId = folder.parentId,
                    data = null,
                    timestamp = now
                )
            )
            folderQueueCount++
        }

        var itemQueueCount = 0
        backupData.items.forEach { item ->
            db.syncQueueDao().addToQueue(
                SyncQueueEntity(
                    entityType = "item",
                    entityId = item.id,
                    action = "create",
                    parentId = item.parentId,
                    data = null,
                    timestamp = now
                )
            )
            itemQueueCount++
        }

        Logger.log(
            TAG,
            "applyBackupData: queued $folderQueueCount folders + $itemQueueCount items for sync"
        )
    }

    // ===== ОЧИСТКА ДАННЫХ =====
    private suspend fun clearAllData(db: AppDatabase) {
        db.folderDao().getAllFolders().forEach { db.folderDao().deleteFolder(it) }
        // ⚠️ Раньше было getAllItems() — теперь getAllItemsRaw(), чтобы чистить и архивные
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
