package com.family.base.data.sync

import com.family.base.BaseApplication
import com.family.base.data.TokenStorage
import com.family.base.data.remote.YandexDiskApi
import com.family.base.util.Logger
import com.family.base.util.NetworkCallExecutor
import com.family.base.util.NetworkCallExecutor.NetworkException
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
 *
 * 🆕 v17.0.7: при запросе списка файлов явно передаём `limit = 1000`.
 * Яндекс.Диск по умолчанию отдаёт только 20 элементов, из-за чего
 * новые changelog-файлы были невидимыми, и синк «залипал».
 */
class SyncManagerApi {

    private val TAG = "SyncManagerApi"
    private val CHANGELOG_FOLDER = "changelog"

    /**
     * Максимум файлов в одном ответе list.
     * У Яндекса допустимо до 10000. 1000 — с огромным запасом.
     */
    private val LIST_LIMIT = 1000

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
     *
     * Обёрнуто в NetworkCallExecutor с 2 попытками.
     *  - 5xx/DNS/timeout → один retry через 500 мс;
     *  - 4xx (кроме 404) → без retry, сразу false;
     *  - 404 → создаём папку (это не ошибка).
     *
     * 🆕 v17.0.7: явно передаём limit в getDiskResources.
     * Хотя для проверки существования папки нам нужно только одно поле,
     * явный limit защищает от скрытых кэшей и странных ответов.
     */
    suspend fun ensureChangelogFolder(): Boolean {
        val auth = getAuth() ?: return false
        val api = YandexDiskApi.getInstance()
        val folderPath = getChangelogPath()
        val rootPath = getRootPath()

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

    /**
     * Возвращает список имён файлов в /BAZA/changelog/ (без пути).
     * Пустой список — если папки нет, или она пуста.
     * null — если сетевая ошибка (важно: НЕ пустой список!).
     *
     * 🆕 v17.0.7: явно передаём limit = 1000.
     * Это ГЛАВНЫЙ фикс: без limit Яндекс отдавал только 20 файлов,
     * и всё, что старше, было невидимым → синк «залипал».
     */
    suspend fun listChangelogFiles(): List<String>? {
        val auth = getAuth() ?: return null
        val api = YandexDiskApi.getInstance()
        val folderPath = getChangelogPath()

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
