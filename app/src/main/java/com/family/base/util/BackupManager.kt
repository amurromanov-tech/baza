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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BackupManager(private val context: Context) {

    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

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
            val items = db.itemDao().getAllItems()
            val history = db.historyDao().getAllEntries()
            val settings = db.settingsDao().getSettings()

            val backupData = BackupData(
                folders = folders,
                items = items,
                history = history,
                settings = settings,
                folderName = folderName
            )

            val json = gson.toJson(backupData)
            val backupFile = File(getBackupDir(), getBackupFileName())
            backupFile.writeText(json)

            Logger.log("BackupManager", "Export to local: ${backupFile.absolutePath}")
            return@withContext backupFile
        } catch (e: Exception) {
            Logger.log("BackupManager", "Export to local failed", e)
            return@withContext null
        }
    }

    suspend fun exportToCloud(db: AppDatabase, folderName: String, tokenStorage: TokenStorage): Boolean = withContext(Dispatchers.IO) {
        try {
            val localFile = exportToLocal(db, folderName) ?: return@withContext false
            val bytes = localFile.readBytes()

            val token = tokenStorage.getAccessToken()
            if (token == null) {
                Logger.log("BackupManager", "No token, cannot upload to cloud")
                return@withContext false
            }

            val api = com.family.base.data.remote.YandexDiskApi.getInstance()
            val auth = "OAuth $token"
            val path = "/${tokenStorage.getFolderName()}/backup/${localFile.name}"

            // Создаём папку backup
            val backupFolderPath = "/${tokenStorage.getFolderName()}/backup"
            val createFolderResponse = api.createFolder(auth, backupFolderPath)
            if (!createFolderResponse.isSuccessful && createFolderResponse.code() != 409) {
                Logger.log("BackupManager", "Failed to create backup folder: ${createFolderResponse.code()}")
                return@withContext false
            }

            // Загружаем файл
            val uploadResponse = com.family.base.util.DiskUploader.uploadFile(api, auth, path, bytes)
            if (uploadResponse) {
                Logger.log("BackupManager", "Export to cloud: $path")
                return@withContext true
            }

            Logger.log("BackupManager", "Export to cloud failed")
            return@withContext false
        } catch (e: Exception) {
            Logger.log("BackupManager", "Export to cloud failed", e)
            return@withContext false
        }
    }

    // ===== ИМПОРТ =====
    suspend fun importFromLocal(file: File, db: AppDatabase): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = file.readText()
            val backupData = gson.fromJson(json, BackupData::class.java)

            // Очищаем текущие данные
            clearAllData(db)

            // Вставляем новые
            backupData.folders.forEach { db.folderDao().insertFolder(it) }
            backupData.items.forEach { db.itemDao().insertItem(it) }
            backupData.history.forEach { db.historyDao().insertEntry(it) }
            backupData.settings?.let { db.settingsDao().insertOrUpdateSettings(it) }

            Logger.log("BackupManager", "Import from local: ${file.name}")
            return@withContext true
        } catch (e: Exception) {
            Logger.log("BackupManager", "Import from local failed", e)
            return@withContext false
        }
    }

    suspend fun importFromCloud(tokenStorage: TokenStorage, db: AppDatabase): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = tokenStorage.getAccessToken()
            if (token == null) {
                Logger.log("BackupManager", "No token, cannot import from cloud")
                return@withContext false
            }

            val api = com.family.base.data.remote.YandexDiskApi.getInstance()
            val auth = "OAuth $token"
            val backupPath = "/${tokenStorage.getFolderName()}/backup"

            // Получаем список файлов в папке backup
            val listResponse = api.getDiskResources(auth, backupPath)
            if (!listResponse.isSuccessful) {
                Logger.log("BackupManager", "Failed to list backup files: ${listResponse.code()}")
                return@withContext false
            }

            val items = listResponse.body()?.items ?: emptyList()
            val backupFiles = items.filter { it.name.endsWith(".json") }

            if (backupFiles.isEmpty()) {
                Logger.log("BackupManager", "No backup files found")
                return@withContext false
            }

            // Берём последний по дате
            val latest = backupFiles.maxByOrNull { it.modified }
            if (latest == null) {
                Logger.log("BackupManager", "No backup files found")
                return@withContext false
            }

            // Скачиваем файл
            val downloadUrlResponse = api.getDiskDownloadUrl(auth, "$backupPath/${latest.name}")
            if (!downloadUrlResponse.isSuccessful) {
                Logger.log("BackupManager", "Failed to get download URL: ${downloadUrlResponse.code()}")
                return@withContext false
            }

            val downloadUrl = downloadUrlResponse.body()?.href
            if (downloadUrl == null) {
                Logger.log("BackupManager", "Download URL is null")
                return@withContext false
            }

            val downloadResponse = api.downloadFile(downloadUrl)
            if (!downloadResponse.isSuccessful) {
                Logger.log("BackupManager", "Failed to download backup: ${downloadResponse.code()}")
                return@withContext false
            }

            val json = downloadResponse.body()?.string()
            if (json == null) {
                Logger.log("BackupManager", "Downloaded backup is empty")
                return@withContext false
            }

            // Парсим и импортируем
            val backupData = gson.fromJson(json, BackupData::class.java)
            clearAllData(db)

            backupData.folders.forEach { db.folderDao().insertFolder(it) }
            backupData.items.forEach { db.itemDao().insertItem(it) }
            backupData.history.forEach { db.historyDao().insertEntry(it) }
            backupData.settings?.let { db.settingsDao().insertOrUpdateSettings(it) }

            Logger.log("BackupManager", "Import from cloud: ${latest.name}")
            return@withContext true
        } catch (e: Exception) {
            Logger.log("BackupManager", "Import from cloud failed", e)
            return@withContext false
        }
    }

    // ===== ОЧИСТКА ДАННЫХ =====
    private suspend fun clearAllData(db: AppDatabase) {
        db.folderDao().getAllFolders().forEach { db.folderDao().deleteFolder(it) }
        db.itemDao().getAllItems().forEach { db.itemDao().deleteItem(it) }
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
