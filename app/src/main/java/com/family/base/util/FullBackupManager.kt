package com.family.base.util

import android.content.Context
import android.os.Environment
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.SettingsEntity
import com.family.base.data.local.entity.TaskEntity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 🆕 v21.0.1: Полный бэкап базы (ZIP-архив).
 *
 * Что внутри архива:
 *   manifest.json     — версия схемы, дата, пользователь, кол-во записей, размер
 *   database.json     — items / folders / tasks / history / settings / outbox (только pending)
 *   images/<id>.jpg   — фото предметов
 *   images/folder_<id>.jpg — иконки папок
 *   checksums.txt     — SHA-256 каждого файла (для валидации при импорте)
 *
 * ⚠️ Галерея НЕ включается — она живёт на Яндекс.Диске, не в приложении.
 * ⚠️ Логи НЕ включаются.
 * ⚠️ Кэш НЕ включается.
 * ⚠️ OAuth-токен НЕ включается (после краша его всё равно надо
 *    получать заново — при импорте будет перелогин).
 * ⚠️ processed_changelogs НЕ включается — это кэш «какие входящие
 *    пакеты уже обработаны». После restore last_modified сбрасывается,
 *    и синк заново подтянет дельту с Диска. Включение кэша может
 *    привести к пропуску чужих изменений на новом устройстве.
 * ⚠️ outbox включается ТОЛЬКО pending (isSynced = 0). Синхронизированные
 *    записи не нужны — они уже на Диске, повторный push создаст дубли.
 *
 * Импорт делает FullBackupRestoreManager (отдельный файл).
 */
class FullBackupManager(private val context: Context) {

    private val TAG = "FullBackupManager"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    companion object {
        /**
         * Версия формата бэкапа. При изменении схемы БД — поднимать,
         * а в FullBackupRestoreManager добавлять обработку.
         */
        const val BACKUP_FORMAT_VERSION = 1
        const val SCHEMA_VERSION = 21   // = AppDatabase version

        const val MANIFEST_NAME = "manifest.json"
        const val DATABASE_NAME = "database.json"
        const val CHECKSUMS_NAME = "checksums.txt"
        const val IMAGES_DIR = "images"

        fun getFullBackupDir(): File {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val backupDir = File(downloadDir, "BAZA/full_backup")
            if (!backupDir.exists()) backupDir.mkdirs()
            return backupDir
        }

        fun formatFileSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes Б"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.getDefault(), "%.1f КБ", kb)
            val mb = kb / 1024.0
            return String.format(Locale.getDefault(), "%.1f МБ", mb)
        }
    }

    // ============================================================
    // МОДЕЛИ ДЛЯ JSON
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
        val outbox: List<OutboxEntity>   // только pending (isSynced = 0)
    )

    data class ExportResult(
        val success: Boolean,
        val file: File?,
        val manifest: ManifestData?,
        val error: String? = null
    )

    // ============================================================
    // ЭКСПОРТ
    // ============================================================

    /**
     * Создаёт ZIP-архив с БД и фото.
     *
     * @param db              база данных.
     * @param userName        имя пользователя (для manifest, "кто сделал").
     * @param appVersionName  versionName приложения.
     * @param appVersionCode  versionCode приложения.
     * @param onProgress      callback прогресса (0..100) и текста.
     * @param checkCancel     возвращает true, если пользователь нажал «Отмена».
     */
    suspend fun exportFullBackup(
        db: AppDatabase,
        userName: String,
        appVersionName: String,
        appVersionCode: Int,
        onProgress: (percent: Int, text: String) -> Unit,
        checkCancel: () -> Boolean = { false }
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            onProgress(0, "Чтение базы…")

            // 1. Снимок БД
            val snapshot = DatabaseSnapshot(
                items = db.itemDao().getAllItemsRaw(),
                folders = db.folderDao().getAllFolders(),
                tasks = db.taskDao().getAllForSync(),
                history = db.historyDao().getAllEntries(),
                settings = db.settingsDao().getSettings(),
                outbox = db.outboxDao().getPending()   // ⚠️ только несинхронизированные
            )

            if (checkCancel()) return@withContext ExportResult(false, null, null, "Отменено пользователем")

            Logger.log(
                TAG,
                "exportFullBackup: snapshot items=${snapshot.items.size}, folders=${snapshot.folders.size}, " +
                    "tasks=${snapshot.tasks.size}, history=${snapshot.history.size}, " +
                    "outboxPending=${snapshot.outbox.size}"
            )

            // 2. Подготовить файлы
            val timestamp = dateFormat.format(Date())
            val backupDir = getFullBackupDir()
            val fileName = "baza_full_backup_$timestamp.zip"
            val zipFile = File(backupDir, fileName)

            val imagesDir = File(context.filesDir, IMAGES_DIR)
            val imageFiles: List<File> = if (imagesDir.exists()) {
                imagesDir.listFiles()?.filter { it.isFile && it.length() > 0L } ?: emptyList()
            } else emptyList()

            if (checkCancel()) return@withContext ExportResult(false, null, null, "Отменено пользователем")

            // 3. Сериализация JSON
            onProgress(5, "Подготовка JSON…")
            val databaseJson = gson.toJson(snapshot)
            val dbBytes = databaseJson.toByteArray(Charsets.UTF_8)

            // 4. Собираем checksums
            onProgress(10, "Подсчёт контрольных сумм…")
            val checksums = mutableMapOf<String, String>()
            checksums[DATABASE_NAME] = sha256(dbBytes)

            var processedImages = 0
            val totalImages = imageFiles.size
            for (img in imageFiles) {
                if (checkCancel()) return@withContext ExportResult(false, null, null, "Отменено пользователем")
                checksums["$IMAGES_DIR/${img.name}"] = sha256File(img)
                processedImages++
                if (processedImages % 50 == 0) {
                    val p = 10 + (processedImages.toDouble() / totalImages * 20).toInt()  // 10..30
                    onProgress(p.coerceIn(10, 30), "Контрольные суммы фото: $processedImages/$totalImages")
                }
            }

            // 5. Manifest
            onProgress(32, "Подготовка manifest…")
            val tempZip = File(backupDir, "$fileName.part")
            var manifest: ManifestData? = null

            // 6. Упаковка ZIP
            onProgress(35, "Упаковка архива…")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zip ->
                // 6.1 database.json
                zip.putNextEntry(ZipEntry(DATABASE_NAME))
                zip.write(dbBytes)
                zip.closeEntry()

                // 6.2 images/*.jpg
                var written = 0
                for (img in imageFiles) {
                    if (checkCancel()) {
                        zip.close()
                        tempZip.delete()
                        return@withContext ExportResult(false, null, null, "Отменено пользователем")
                    }
                    zip.putNextEntry(ZipEntry("$IMAGES_DIR/${img.name}"))
                    FileInputStream(img).use { fis ->
                        BufferedInputStream(fis).copyTo(zip)
                    }
                    zip.closeEntry()
                    written++
                    if (written % 25 == 0 || written == totalImages) {
                        val p = 35 + (written.toDouble() / totalImages * 55).toInt()  // 35..90
                        onProgress(p.coerceIn(35, 90), "Упаковка фото: $written/$totalImages")
                    }
                }

                // 6.3 checksums.txt
                zip.putNextEntry(ZipEntry(CHECKSUMS_NAME))
                val checksumsText = buildString {
                    for ((path, hash) in checksums) {
                        append(hash).append("  ").append(path).append('\n')
                    }
                }
                zip.write(checksumsText.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 6.4 manifest.json (последним, т.к. размер архива до этого неизвестен)
                onProgress(92, "Запись manifest…")
                val approximateSize = tempZip.length()
                manifest = ManifestData(
                    formatVersion = BACKUP_FORMAT_VERSION,
                    schemaVersion = SCHEMA_VERSION,
                    createdAt = System.currentTimeMillis(),
                    createdBy = userName,
                    appVersionName = appVersionName,
                    appVersionCode = appVersionCode,
                    itemCount = snapshot.items.size,
                    folderCount = snapshot.folders.size,
                    taskCount = snapshot.tasks.size,
                    historyCount = snapshot.history.size,
                    imageCount = imageFiles.count { !it.name.startsWith("folder_") },
                    folderIconCount = imageFiles.count { it.name.startsWith("folder_") },
                    archiveSizeBytes = approximateSize
                )
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(gson.toJson(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            // 7. Переименовать .part → .zip (атомарность)
            if (tempZip.exists()) {
                if (zipFile.exists()) zipFile.delete()
                if (!tempZip.renameTo(zipFile)) {
                    tempZip.delete()
                    return@withContext ExportResult(false, null, null, "Не удалось переименовать архив")
                }
            } else {
                return@withContext ExportResult(false, null, null, "Архив не создан")
            }

            onProgress(100, "Готово: ${formatFileSize(zipFile.length())}")
            Logger.log(TAG, "exportFullBackup: OK → ${zipFile.absolutePath} (${formatFileSize(zipFile.length())})")

            return@withContext ExportResult(true, zipFile, manifest, null)
        } catch (e: Exception) {
            Logger.log(TAG, "exportFullBackup error: ${e.message}", e)
            return@withContext ExportResult(false, null, null, e.message ?: "Неизвестная ошибка")
        }
    }

    // ============================================================
    // СПИСОК БЭКАПОВ
    // ============================================================

    fun getFullBackups(): List<File> {
        return getFullBackupDir()
            .listFiles()
            ?.filter { it.name.startsWith("baza_full_backup_") && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun deleteBackup(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            Logger.log(TAG, "deleteBackup error: ${e.message}")
            false
        }
    }

    // ============================================================
    // ХЭШИ
    // ============================================================

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
