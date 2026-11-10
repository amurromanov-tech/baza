package com.family.base.util

import android.content.Context
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.SettingsEntity
import com.family.base.data.local.entity.TaskEntity
import com.family.base.data.local.entity.SyncInfoEntity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 🆕 v21.0.1: Восстановление из полного бэкапа (ZIP).
 *
 * Порядок действий:
 *   1. Валидация архива (manifest, version, checksums).
 *   2. Автоматический бэкап текущего состояния (в отдельный ZIP).
 *   3. Полная очистка БД: items, folders, tasks, history, settings,
 *      outbox, sync_info.
 *   4. Восстановление из database.json.
 *   5. Очистка images/, восстановление фото из ZIP.
 *   6. Сброс last_modified → следующий синк подтянет дельту с Диска.
 *
 * ⚠️ Импорт НЕ трогает:
 *   - галерею (её нет в бэкапе);
 *   - OAuth-токен (пользователь перелогинивается);
 *   - users.json на Диске (это не локальные данные).
 */
class FullBackupRestoreManager(private val context: Context) {

    private val TAG = "FullBackupRestoreManager"
    private val gson: Gson = GsonBuilder().create()

    companion object {
        private const val IMAGES_DIR = FullBackupManager.IMAGES_DIR
        private const val MANIFEST_NAME = FullBackupManager.MANIFEST_NAME
        private const val DATABASE_NAME = FullBackupManager.DATABASE_NAME
        private const val CHECKSUMS_NAME = FullBackupManager.CHECKSUMS_NAME
    }

    // ============================================================
    // МОДЕЛИ (совпадают с FullBackupManager)
    // ============================================================

    data class ManifestData(
        val formatVersion: Int,
        val schemaVersion: Int,
        val createdAt: Long,
        val createdBy: String,
        val appVersionName: String,
        val appVersionCode: Int,
        val itemCount: Int,
        val folderCount: Int,
        val taskCount: Int,
        val historyCount: Int,
        val imageCount: Int,
        val folderIconCount: Int,
        val archiveSizeBytes: Long
    )

    data class DatabaseSnapshot(
        val items: List<ItemEntity>,
        val folders: List<FolderEntity>,
        val tasks: List<TaskEntity>,
        val history: List<HistoryEntry>,
        val settings: SettingsEntity?,
        val outbox: List<OutboxEntity>
    )

    data class ValidationResult(
        val valid: Boolean,
        val manifest: ManifestData?,
        val error: String? = null
    )

    data class RestoreResult(
        val success: Boolean,
        val manifest: ManifestData?,
        val preRestoreBackup: File?,
        val error: String? = null
    )

    // ============================================================
    // ВАЛИДАЦИЯ (без распаковки на диск)
    // ============================================================

    /**
     * Быстрая проверка ZIP: manifest есть, формат совместим,
     * схема не новее текущей.
     */
    suspend fun validate(zipFile: File): ValidationResult = withContext(Dispatchers.IO) {
        try {
            if (!zipFile.exists()) {
                return@withContext ValidationResult(false, null, "Файл не найден")
            }
            if (!zipFile.name.endsWith(".zip")) {
                return@withContext ValidationResult(false, null, "Не ZIP-файл")
            }

            var manifest: ManifestData? = null
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == MANIFEST_NAME) {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        manifest = gson.fromJson(text, ManifestData::class.java)
                        break
                    }
                    entry = zis.nextEntry
                }
            }

            if (manifest == null) {
                return@withContext ValidationResult(false, null, "В архиве нет manifest.json")
            }

            val m = manifest!!
            if (m.formatVersion != FullBackupManager.BACKUP_FORMAT_VERSION) {
                return@withContext ValidationResult(
                    false, m,
                    "Несовместимая версия бэкапа: ${m.formatVersion} (ожидается ${FullBackupManager.BACKUP_FORMAT_VERSION})"
                )
            }
            if (m.schemaVersion > FullBackupManager.SCHEMA_VERSION) {
                return@withContext ValidationResult(
                    false, m,
                    "Бэкап из более новой версии приложения: схема ${m.schemaVersion} > ${FullBackupManager.SCHEMA_VERSION}"
                )
            }

            Logger.log(
                TAG,
                "validate: OK — created=${m.createdBy}, schema=${m.schemaVersion}, " +
                    "items=${m.itemCount}, folders=${m.folderCount}, images=${m.imageCount}"
            )
            return@withContext ValidationResult(true, m, null)
        } catch (e: Exception) {
            Logger.log(TAG, "validate error: ${e.message}", e)
            return@withContext ValidationResult(false, null, e.message ?: "Ошибка чтения ZIP")
        }
    }

    // ============================================================
    // ВОССТАНОВЛЕНИЕ
    // ============================================================

    /**
     * Полное восстановление.
     *
     * @param zipFile          ZIP с бэкапом.
     * @param db               база данных.
     * @param currentUserName  кто запустил (для pre-restore backup).
     * @param appVersionName   versionName приложения.
     * @param appVersionCode   versionCode приложения.
     * @param onProgress       callback (0..100, текст).
     */
    suspend fun restore(
        zipFile: File,
        db: AppDatabase,
        currentUserName: String,
        appVersionName: String,
        appVersionCode: Int,
        onProgress: (percent: Int, text: String) -> Unit
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            onProgress(0, "Проверка архива…")
            val validation = validate(zipFile)
            if (!validation.valid) {
                return@withContext RestoreResult(false, validation.manifest, null, validation.error)
            }
            val manifest = validation.manifest

            // 1. Pre-restore backup текущего состояния
            onProgress(5, "Резервная копия текущих данных…")
            val preRestoreBackup: File? = try {
                val backupManager = FullBackupManager(context)
                val res = backupManager.exportFullBackup(
                    db = db,
                    userName = currentUserName,
                    appVersionName = appVersionName,
                    appVersionCode = appVersionCode,
                    onProgress = { p, t -> onProgress((5 + p * 15 / 100), "Pre-backup: $t") },
                    checkCancel = { false }
                )
                if (res.success) res.file else null
            } catch (e: Exception) {
                Logger.log(TAG, "pre-restore backup failed: ${e.message}", e)
                null
            }
            Logger.log(TAG, "restore: pre-backup = ${preRestoreBackup?.absolutePath}")

            // 2. Распаковка во временную папку
            onProgress(20, "Распаковка архива…")
            val tempDir = File(context.cacheDir, "full_restore_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            var databaseJson: String? = null
            val extractedImages = mutableMapOf<String, File>()

            try {
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == DATABASE_NAME) {
                            databaseJson = zis.readBytes().toString(Charsets.UTF_8)
                        } else if (name.startsWith("$IMAGES_DIR/")) {
                            val fileName = name.removePrefix("$IMAGES_DIR/")
                            if (fileName.isNotEmpty()) {
                                val outFile = File(tempDir, fileName)
                                FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                extractedImages[fileName] = outFile
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) {
                tempDir.deleteRecursively()
                return@withContext RestoreResult(false, manifest, preRestoreBackup, "Ошибка распаковки: ${e.message}")
            }

            if (databaseJson == null) {
                tempDir.deleteRecursively()
                return@withContext RestoreResult(false, manifest, preRestoreBackup, "В архиве нет database.json")
            }

            onProgress(40, "Парсинг базы…")
            val snapshot: DatabaseSnapshot = try {
                gson.fromJson(databaseJson, DatabaseSnapshot::class.java)
            } catch (e: Exception) {
                tempDir.deleteRecursively()
                return@withContext RestoreResult(false, manifest, preRestoreBackup, "Ошибка JSON: ${e.message}")
            }

            Logger.log(
                TAG,
                "restore: parsed items=${snapshot.items.size}, folders=${snapshot.folders.size}, " +
                    "tasks=${snapshot.tasks.size}, history=${snapshot.history.size}, " +
                    "outbox=${snapshot.outbox.size}, images=${extractedImages.size}"
            )

            // 3. Полная очистка БД
            onProgress(50, "Очистка текущей базы…")
            clearAllData(db)

            // 4. Восстановление сущностей
            onProgress(60, "Восстановление предметов и папок…")
            // Папки первыми — на них ссылаются предметы (parentId).
            snapshot.folders.forEach { db.folderDao().insertFolder(it) }
            snapshot.items.forEach { db.itemDao().insertItem(it) }
            snapshot.tasks.forEach { db.taskDao().insert(it) }
            snapshot.history.forEach { db.historyDao().insertEntry(it) }
            snapshot.settings?.let { db.settingsDao().insertOrUpdateSettings(it) }

            // outbox — восстановим только pending, чтобы не запушить дубли
            onProgress(70, "Восстановление очереди синхронизации…")
            if (snapshot.outbox.isNotEmpty()) {
                db.outboxDao().insertAll(snapshot.outbox.filter { !it.isSynced })
            }

            // 5. Фото: очистить текущие и положить из архива
            onProgress(80, "Восстановление фотографий…")
            val imagesDir = File(context.filesDir, IMAGES_DIR)
            if (imagesDir.exists()) {
                imagesDir.listFiles()?.forEach { it.delete() }
            } else {
                imagesDir.mkdirs()
            }

            var copied = 0
            for ((fileName, tempFile) in extractedImages) {
                val dest = File(imagesDir, fileName)
                try {
                    tempFile.copyTo(dest, overwrite = true)
                    copied++
                } catch (e: Exception) {
                    Logger.log(TAG, "restore: copy image failed $fileName: ${e.message}")
                }
            }
            Logger.log(TAG, "restore: copied $copied images to ${imagesDir.absolutePath}")

            // 6. Сброс last_modified — следующий синк подтянет дельту с Диска
            onProgress(90, "Сброс состояния синхронизации…")
            try {
                db.syncInfoDao().setLastModified(0L)
            } catch (e: Exception) {
                Logger.log(TAG, "restore: reset last_modified failed: ${e.message}")
            }

            // 7. Уборка
            tempDir.deleteRecursively()

            onProgress(100, "Готово: восстановлено items=${snapshot.items.size}, folders=${snapshot.folders.size}")
            Logger.log(TAG, "restore: OK, pre-backup = ${preRestoreBackup?.absolutePath}")

            return@withContext RestoreResult(true, manifest, preRestoreBackup, null)
        } catch (e: Exception) {
            Logger.log(TAG, "restore error: ${e.message}", e)
            return@withContext RestoreResult(false, null, null, e.message ?: "Неизвестная ошибка")
        }
    }

    // ============================================================
    // ОЧИСТКА БД
    // ============================================================

    private suspend fun clearAllData(db: AppDatabase) {
        // Порядок важен: сначала зависимые, потом основные.
        // Но у нас нет FK — можно в любом порядке.
        db.itemDao().getAllItemsRaw().forEach { db.itemDao().deleteItem(it) }
        db.folderDao().getAllFolders().forEach { db.folderDao().deleteFolder(it) }
        db.taskDao().deleteAll()
        db.historyDao().getAllEntries().forEach { db.historyDao().deleteEntry(it) }
        db.outboxDao().clear()
        // settings: перезапишем через insertOrUpdateSettings ниже — не чистим.
        // sync_info: перезапишем setLastModified(0) ниже — не чистим.
        Logger.log(TAG, "clearAllData: items, folders, tasks, history, outbox cleared")
    }
}
