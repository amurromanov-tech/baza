package com.family.base.data.sync

import com.family.base.BaseApplication
import com.family.base.data.TokenStorage
import com.family.base.data.remote.YandexDiskApi
import com.family.base.util.DiskPaths
import com.family.base.util.Logger
import com.family.base.util.NetworkCallExecutor
import com.family.base.util.NetworkCallExecutor.NetworkException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.UnknownHostException

/**
 * Тонкий сетевой слой для SyncManager.
 *
 * 🆕 v21.1.0 (Шаг 1.6-4): deleteRecipeMediaFile(recipeId, fileName) —
 * удаление файла медиа рецепта с Диска при cleanup tombstones
 * (/BAZA/data/recipe_media/<recipeId>/<fileName>).
 */
class SyncManagerApi {

    private val TAG = "SyncManagerApi"

    private val LIST_LIMIT = 1000

    private val DOWNLOAD_MAX_ATTEMPTS = 2
    private val DOWNLOAD_RETRY_DELAY_MS = 500L

    private val DELETE_MAX_ATTEMPTS = 2
    private val DELETE_RETRY_DELAY_MS = 500L

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

    suspend fun ensureChangelogFolder(): Boolean {
        val auth = getAuth() ?: return false
        val api = YandexDiskApi.getInstance()
        val folderPath = DiskPaths.CHANGELOG
        val rootPath = DiskPaths.ROOT

        val result = NetworkCallExecutor.runHttpSafely<Boolean>(
            label = "ensureChangelogFolder",
            maxAttempts = 2,
            treat404AsSuccess = false
        ) {
            val check = api.getDiskResources(auth, folderPath, limit = LIST_LIMIT)
            val checkCode = check.code()

            when {
                checkCode == 200 -> {
                    return@runHttpSafely NetworkCallExecutor.HttpOutcome(true, 200)
                }
                checkCode == 404 -> {
                    val rootCheck = api.getDiskResources(auth, rootPath, limit = LIST_LIMIT)
                    val rootCode = rootCheck.code()
                    if (rootCode == 404) {
                        val createRoot = api.createFolder(auth, rootPath)
                        if (!createRoot.isSuccessful && createRoot.code() != 409) {
                            return@runHttpSafely NetworkCallExecutor.HttpOutcome(
                                null,
                                createRoot.code().takeIf { it >= 400 } ?: 500
                            )
                        }
                    } else if (rootCode != 200) {
                        return@runHttpSafely NetworkCallExecutor.HttpOutcome(null, rootCode)
                    }

                    val create = api.createFolder(auth, folderPath)
                    if (create.isSuccessful || create.code() == 409) {
                        return@runHttpSafely NetworkCallExecutor.HttpOutcome(true, 200)
                    }
                    return@runHttpSafely NetworkCallExecutor.HttpOutcome(
                        null,
                        create.code().takeIf { it >= 400 } ?: 500
                    )
                }
                else -> {
                    return@runHttpSafely NetworkCallExecutor.HttpOutcome(null, checkCode)
                }
            }
        }

        return result.fold(
            onSuccess = { value -> value == true },
            onFailure = { err ->
                if (err is NetworkException && err.kind == NetworkCallExecutor.Kind.DNS) {
                    Logger.log(TAG, "ensureChangelogFolder: DNS failure after retries: ${err.message}")
                } else {
                    Logger.log(TAG, "ensureChangelogFolder FAILED: ${err.message}")
                }
                false
            }
        )
    }

    // ============================================================
    // LIST FILES
    // ============================================================

    suspend fun listChangelogFiles(): List<String>? {
        val auth = getAuth() ?: return null
        val api = YandexDiskApi.getInstance()
        val folderPath = DiskPaths.CHANGELOG

        return try {
            val response = api.getDiskResources(auth, folderPath, limit = LIST_LIMIT)

            if (response.code() == 404) {
                Logger.log(TAG, "listChangelogFiles: folder not found, returning empty")
                return emptyList()
            }
            if (!response.isSuccessful) {
                Logger.log(TAG, "listChangelogFiles: code=${response.code()}")
                return null
            }

            val body = response.body() ?: return emptyList()
            val items = body.embedded?.items ?: run {
                Logger.log(TAG, "listChangelogFiles: _embedded.items is null")
                return emptyList()
            }

            val files = items
                .filter { it.type == "file" }
                .mapNotNull { it.name }
                .filter { it.endsWith(".json") }

            Logger.log(TAG, "listChangelogFiles: found ${files.size} files (limit=$LIST_LIMIT)")
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

    suspend fun uploadChangelogFile(fileName: String, jsonBytes: ByteArray): Boolean {
        val auth = getAuth() ?: return false
        val api = YandexDiskApi.getInstance()
        val path = DiskPaths.changelogFile(fileName)

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

    suspend fun downloadChangelogFile(fileName: String): ByteArray? {
        val auth = getAuth() ?: return null
        val api = YandexDiskApi.getInstance()
        val path = DiskPaths.changelogFile(fileName)

        var lastError: String? = null

        for (attempt in 1..DOWNLOAD_MAX_ATTEMPTS) {
            try {
                val urlResponse = api.getDiskDownloadUrl(auth, path)
                if (!urlResponse.isSuccessful) {
                    val code = urlResponse.code()

                    if (code in 400..499 && code != 429) {
                        Logger.log(TAG, "downloadChangelogFile: getDownloadUrl 4xx for $fileName, code=$code, no retry")
                        return null
                    }

                    lastError = "getDownloadUrl code=$code"
                    Logger.log(
                        TAG,
                        "downloadChangelogFile: getDownloadUrl attempt $attempt/$DOWNLOAD_MAX_ATTEMPTS " +
                            "for $fileName failed, code=$code"
                    )
                    if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                        delay(DOWNLOAD_RETRY_DELAY_MS)
                        continue
                    }
                    return null
                }

                val href = urlResponse.body()?.href
                if (href == null) {
                    lastError = "getDownloadUrl returned empty href"
                    Logger.log(
                        TAG,
                        "downloadChangelogFile: attempt $attempt/$DOWNLOAD_MAX_ATTEMPTS " +
                            "for $fileName — empty href"
                    )
                    if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                        delay(DOWNLOAD_RETRY_DELAY_MS)
                        continue
                    }
                    return null
                }

                val downloadResponse = api.downloadFile(href)
                if (!downloadResponse.isSuccessful) {
                    val code = downloadResponse.code()
                    if (code in 400..499 && code != 429) {
                        Logger.log(TAG, "downloadChangelogFile: download 4xx for $fileName, code=$code, no retry")
                        return null
                    }

                    lastError = "download code=$code"
                    Logger.log(
                        TAG,
                        "downloadChangelogFile: download attempt $attempt/$DOWNLOAD_MAX_ATTEMPTS " +
                            "for $fileName failed, code=$code"
                    )
                    if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                        delay(DOWNLOAD_RETRY_DELAY_MS)
                        continue
                    }
                    return null
                }

                val bytes = downloadResponse.body()?.bytes()
                if (bytes != null) {
                    Logger.log(TAG, "downloadChangelogFile: downloaded $fileName (${bytes.size} bytes)")
                }
                return bytes
            } catch (e: Exception) {
                lastError = e.message
                if (isDnsError(e)) {
                    Logger.log(
                        TAG,
                        "downloadChangelogFile: attempt $attempt/$DOWNLOAD_MAX_ATTEMPTS " +
                            "DNS failure for $fileName: ${e.message}"
                    )
                } else {
                    Logger.log(
                        TAG,
                        "downloadChangelogFile: attempt $attempt/$DOWNLOAD_MAX_ATTEMPTS " +
                            "error for $fileName: ${e.message}"
                    )
                }
                if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                    delay(DOWNLOAD_RETRY_DELAY_MS)
                    continue
                }
                return null
            }
        }

        Logger.log(TAG, "downloadChangelogFile: all $DOWNLOAD_MAX_ATTEMPTS attempts failed for $fileName (last=$lastError)")
        return null
    }

    // ============================================================
    // DELETE — CHANGELOG / ФОТО / МЕДИА РЕЦЕПТОВ
    // ============================================================

    suspend fun deleteChangelogFile(fileName: String): Boolean {
        val path = DiskPaths.changelogFile(fileName)
        return deleteFileByPathInternal(path, label = "deleteChangelogFile($fileName)")
    }

    suspend fun deleteItemImageFile(itemId: String): Boolean {
        val path = DiskPaths.itemImage(itemId)
        return deleteFileByPathInternal(path, label = "deleteItemImageFile($itemId)")
    }

    suspend fun deleteFolderImageFile(folderId: String): Boolean {
        val path = DiskPaths.folderImage(folderId)
        return deleteFileByPathInternal(path, label = "deleteFolderImageFile($folderId)")
    }

    /**
     * 🆕 v21.1.0 (Шаг 1.6-4): удаляет файл медиа рецепта с Диска.
     * Путь: /BAZA/data/recipe_media/<recipeId>/<fileName>.
     * 404 = успех (файла нет — цель достигнута).
     */
    suspend fun deleteRecipeMediaFile(recipeId: String, fileName: String): Boolean {
        val path = DiskPaths.recipeMediaFile(recipeId, fileName)
        return deleteFileByPathInternal(path, label = "deleteRecipeMediaFile($recipeId/$fileName)")
    }

    // ============================================================
    // УНИВЕРСАЛЬНОЕ УДАЛЕНИЕ ПО ПУТИ (с retry)
    // ============================================================

    private suspend fun deleteFileByPathInternal(path: String, label: String): Boolean {
        val auth = getAuth() ?: return false
        val api = YandexDiskApi.getInstance()

        var lastError: String? = null

        for (attempt in 1..DELETE_MAX_ATTEMPTS) {
            try {
                val response = api.deleteFile(auth, path, false)
                val code = response.code()

                if (response.isSuccessful) {
                    Logger.log(TAG, "$label: deleted $path")
                    return true
                }

                if (code == 404) {
                    Logger.log(TAG, "$label: $path already missing (404), treat as success")
                    return true
                }

                if (code in 400..499 && code != 429) {
                    Logger.log(TAG, "$label: 4xx for $path, code=$code, no retry")
                    return false
                }

                lastError = "code=$code"
                Logger.log(
                    TAG,
                    "$label: attempt $attempt/$DELETE_MAX_ATTEMPTS failed for $path, code=$code"
                )
                if (attempt < DELETE_MAX_ATTEMPTS) {
                    delay(DELETE_RETRY_DELAY_MS)
                    continue
                }
                return false
            } catch (e: Exception) {
                lastError = e.message
                if (isDnsError(e)) {
                    Logger.log(
                        TAG,
                        "$label: attempt $attempt/$DELETE_MAX_ATTEMPTS DNS failure for $path: ${e.message}"
                    )
                } else {
                    Logger.log(
                        TAG,
                        "$label: attempt $attempt/$DELETE_MAX_ATTEMPTS error for $path: ${e.message}"
                    )
                }
                if (attempt < DELETE_MAX_ATTEMPTS) {
                    delay(DELETE_RETRY_DELAY_MS)
                    continue
                }
                return false
            }
        }

        Logger.log(TAG, "$label: all $DELETE_MAX_ATTEMPTS attempts failed for $path (last=$lastError)")
        return false
    }
}
