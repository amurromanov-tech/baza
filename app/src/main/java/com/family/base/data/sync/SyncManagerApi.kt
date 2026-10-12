package com.family.base.data.sync

import com.family.base.BaseApplication
import com.family.base.data.TokenStorage
import com.family.base.data.remote.YandexDiskApi
import com.family.base.util.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.UnknownHostException

/**
 * Тонкий сетевой слой для SyncManager.
 *
 * Отвечает только за работу с папкой /BAZA/changelog/:
 *  - список файлов;
 *  - загрузка одного файла;
 *  - скачивание одного файла;
 *  - удаление одного файла.
 *
 * Всё общение с Яндекс.Диском — через YandexDiskApi.
 */
class SyncManagerApi {

    private val TAG = "SyncManagerApi"
    private val CHANGELOG_FOLDER = "changelog"

    private fun getAuth(): String? {
        return try {
            val ctx = BaseApplication.getAppContext()
            val tokenStorage = TokenStorage(ctx)
            tokenStorage.getAccessToken()?.let { "OAuth $it" }
        } catch (e: Exception) {
            Logger.log(TAG, "getAuth error: ${e.message}")
            null
        }
    }

    private fun getRootPath(): String {
        return try {
            val ctx = BaseApplication.getAppContext()
            val tokenStorage = TokenStorage(ctx)
            val folderName = tokenStorage.getFolderName() ?: "BAZA"
            "/$folderName"
        } catch (e: Exception) {
            Logger.log(TAG, "getRootPath error, using /BAZA: ${e.message}")
            "/BAZA"
        }
    }

    private fun getChangelogPath(): String = "${getRootPath()}/$CHANGELOG_FOLDER"

    private fun isDnsError(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is UnknownHostException) return true
            if (cause.message?.contains("No address associated with hostname") == true) return true
            cause = cause.cause
        }
        return false
    }

    // ============================================================
    // ENSURE FOLDER
    // ============================================================

    /**
     * Гарантирует существование /BAZA/changelog/.
     */
    suspend fun ensureChangelogFolder(): Boolean {
        val auth = getAuth() ?: return false
        val api = YandexDiskApi.getInstance()
        val folderPath = getChangelogPath()

        return try {
            val check = api.getDiskResources(auth, folderPath)
            when (check.code()) {
                200 -> true
                404 -> {
                    // Папки нет — создаём. Также убедимся, что есть корень.
                    val rootPath = getRootPath()
                    val rootCheck = api.getDiskResources(auth, rootPath)
                    if (rootCheck.code() == 404) {
                        api.createFolder(auth, rootPath)
                    }
                    val create = api.createFolder(auth, folderPath)
                    create.isSuccessful || create.code() == 409
                }
                else -> {
                    Logger.log(TAG, "ensureChangelogFolder: unexpected code ${check.code()}")
                    false
                }
            }
        } catch (e: Exception) {
            if (isDnsError(e)) {
                Logger.log(TAG, "ensureChangelogFolder: DNS failure: ${e.message}")
            } else {
                Logger.log(TAG, "ensureChangelogFolder error: ${e.message}")
            }
            false
        }
    }

    // ============================================================
    // LIST FILES
    // ============================================================

    /**
     * Возвращает список имён файлов в /BAZA/changelog/ (без пути).
     * Пустой список — если папки нет, или она пуста.
     * null — если сетевая ошибка (важно: НЕ пустой список!).
     *
     * ⚠️ API getDiskResources не поддерживает limit/offset —
     * Яндекс отдаёт все элементы папки разом.
     */
    suspend fun listChangelogFiles(): List<String>? {
        val auth = getAuth() ?: return null
        val api = YandexDiskApi.getInstance()
        val folderPath = getChangelogPath()

        return try {
            val response = api.getDiskResources(auth, folderPath)

            if (response.code() == 404) {
                Logger.log(TAG, "listChangelogFiles: folder not found, returning empty")
                return emptyList()
            }
            if (!response.isSuccessful) {
                Logger.log(TAG, "listChangelogFiles: code=${response.code()}")
                return null
            }

            val body = response.body() ?: return emptyList()
            // Поле _embedded — стандартное для Яндекс.Диска.
            // Если модель не содержит — вернём пустой список.
            val items = body.embedded?.items ?: run {
                Logger.log(TAG, "listChangelogFiles: _embedded.items is null")
                return emptyList()
            }

            val files = items
                .filter { it.type == "file" }
                .mapNotNull { it.name }
                .filter { it.endsWith(".json") }

            Logger.log(TAG, "listChangelogFiles: found ${files.size} files")
            files
        } catch (e: Exception) {
            if (isDnsError(e)) {
                Logger.log(TAG, "listChangelogFiles: DNS failure: ${e.message}")
            } else {
                Logger.log(TAG, "listChangelogFiles error: ${e.message}")
            }
            null
        }
    }

    // ============================================================
    // UPLOAD
    // ============================================================

    /**
     * Заливает JSON-пакет на Диск.
     * fileName — только имя (например, "abc-123_1730000000000.json").
     */
    suspend fun uploadChangelogFile(fileName: String, jsonBytes: ByteArray): Boolean {
        val auth = getAuth() ?: return false
        val api = YandexDiskApi.getInstance()
        val path = "${getChangelogPath()}/$fileName"

        return try {
            val body = jsonBytes.toRequestBody("application/json".toMediaType())

            val urlResponse = api.getUploadUrl(auth, path, true)
            if (!urlResponse.isSuccessful) {
                Logger.log(TAG, "uploadChangelogFile: getUploadUrl failed for $fileName, code=${urlResponse.code()}")
                return false
            }
            val href = urlResponse.body()?.href ?: return false

            val uploadResponse = api.uploadFileToUrl(href, body)
            if (uploadResponse.isSuccessful) {
                Logger.log(TAG, "uploadChangelogFile: uploaded $fileName (${jsonBytes.size} bytes)")
                true
            } else {
                Logger.log(TAG, "uploadChangelogFile: upload failed for $fileName, code=${uploadResponse.code()}")
                false
            }
        } catch (e: Exception) {
            if (isDnsError(e)) {
                Logger.log(TAG, "uploadChangelogFile: DNS failure: ${e.message}")
            } else {
                Logger.log(TAG, "uploadChangelogFile error for $fileName: ${e.message}")
            }
            false
        }
    }

    // ============================================================
    // DOWNLOAD
    // ============================================================

    /**
     * Скачивает JSON-пакет с Диска.
     * fileName — только имя.
     * null — если ошибка или файла нет.
     */
    suspend fun downloadChangelogFile(fileName: String): ByteArray? {
        val auth = getAuth() ?: return null
        val api = YandexDiskApi.getInstance()
        val path = "${getChangelogPath()}/$fileName"

        return try {
            val urlResponse = api.getDiskDownloadUrl(auth, path)
            if (!urlResponse.isSuccessful) {
                Logger.log(TAG, "downloadChangelogFile: getDownloadUrl failed for $fileName, code=${urlResponse.code()}")
                return null
            }
            val href = urlResponse.body()?.href ?: return null

            val downloadResponse = api.downloadFile(href)
            if (!downloadResponse.isSuccessful) {
                Logger.log(TAG, "downloadChangelogFile: download failed for $fileName, code=${downloadResponse.code()}")
                return null
            }
            val bytes = downloadResponse.body()?.bytes()
            if (bytes != null) {
                Logger.log(TAG, "downloadChangelogFile: downloaded $fileName (${bytes.size} bytes)")
            }
            bytes
        } catch (e: Exception) {
            if (isDnsError(e)) {
                Logger.log(TAG, "downloadChangelogFile: DNS failure: ${e.message}")
            } else {
                Logger.log(TAG, "downloadChangelogFile error for $fileName: ${e.message}")
            }
            null
        }
    }

    // ============================================================
    // DELETE
    // ============================================================

    /**
     * Удаляет файл changelog с Диска.
     * Используется для очистки своих старых пакетов.
     */
    suspend fun deleteChangelogFile(fileName: String): Boolean {
        val auth = getAuth() ?: return false
        val api = YandexDiskApi.getInstance()
        val path = "${getChangelogPath()}/$fileName"

        return try {
            val response = api.deleteFile(auth, path, false)
            val ok = response.isSuccessful || response.code() == 404
            if (ok) {
                Logger.log(TAG, "deleteChangelogFile: deleted $fileName")
            } else {
                Logger.log(TAG, "deleteChangelogFile: failed for $fileName, code=${response.code()}")
            }
            ok
        } catch (e: Exception) {
            if (isDnsError(e)) {
                Logger.log(TAG, "deleteChangelogFile: DNS failure: ${e.message}")
            } else {
                Logger.log(TAG, "deleteChangelogFile error for $fileName: ${e.message}")
            }
            false
        }
    }
}
