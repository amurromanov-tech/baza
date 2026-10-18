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
 *
 * 🆕 v17.3.0 (хвост №8): downloadChangelogFile теперь делает до 2 попыток
 * с задержкой 500 мс при 5xx / DNS / timeout. Разовые 500 от Яндекса
 * больше не теряют файл до следующего синка. 4xx — без retry
 * (бессмысленно, файла нет или нет прав).
 *
 * 🆕 v17.4.0 (хвост №3): deleteChangelogFile теперь тоже делает до 2 попыток
 * с задержкой 500 мс при 5xx / 429 / DNS / timeout. 4xx (кроме 429) — без retry.
 * Раньше разовый 500 при удалении оставлял файл на Диске до следующего cleanup.
 *
 * 🆕 B3: пути changelog берутся из DiskPaths — никаких магических строк.
 */
class SyncManagerApi {

    private val TAG = "SyncManagerApi"

    /**
     * Максимум файлов в одном ответе list.
     * У Яндекса допустимо до 10000. 1000 — с огромным запасом.
     */
    private val LIST_LIMIT = 1000

    /**
     * 🆕 v17.3.0: сколько раз пробуем скачать один changelog-файл
     * при 5xx/DNS/timeout. 2 попытки = 1 retry через 500 мс.
     */
    private val DOWNLOAD_MAX_ATTEMPTS = 2

    /**
     * 🆕 v17.3.0: пауза между попытками downloadChangelogFile.
     */
    private val DOWNLOAD_RETRY_DELAY_MS = 500L

    /**
     * 🆕 v17.4.0: сколько раз пробуем удалить один changelog-файл
     * при 5xx/429/DNS/timeout. 2 попытки = 1 retry через 500 мс.
     */
    private val DELETE_MAX_ATTEMPTS = 2

    /**
     * 🆕 v17.4.0: пауза между попытками deleteChangelogFile.
     */
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
     *
     * 🆕 B3: пути — из DiskPaths.
     */
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

    /**
     * Возвращает список имён файлов в /BAZA/changelog/ (без пути).
     * Пустой список — если папки нет, или она пуста.
     * null — если сетевая ошибка (важно: НЕ пустой список!).
     *
     * 🆕 v17.0.7: явно передаём limit = 1000.
     * Это ГЛАВНЫЙ фикс: без limit Яндекс отдавал только 20 файлов,
     * и всё, что старше, было невидимым → синк «залипал».
     *
     * 🆕 B3: путь — из DiskPaths.
     */
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

    /**
     * Заливает JSON-пакет на Диск.
     * fileName — только имя (например, "abc-123_1730000000000.json").
     *
     * 🆕 B3: путь — из DiskPaths.changelogFile().
     */
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

    /**
     * Скачивает JSON-пакет с Диска.
     * fileName — только имя.
     * null — если ошибка или файла нет.
     *
     * 🆕 v17.3.0 (хвост №8): до DOWNLOAD_MAX_ATTEMPTS попыток
     * с паузой DOWNLOAD_RETRY_DELAY_MS при 5xx / DNS / timeout.
     *
     * Логика:
     *  - 4xx (кроме 429) — сразу null, без retry (файла нет или нет прав).
     *  - 429 — retry (rate limit, может пройти).
     *  - 5xx / DNS / timeout / любое исключение — retry до исчерпания попыток.
     *  - 200 — сразу bytes.
     *
     * Это закрывает разовые 500 от Яндекса, из-за которых раньше
     * файл терялся до следующего синка (он уже мог быть в processed
     * на другом устройстве, и синк «залипал»).
     *
     * 🆕 B3: путь — из DiskPaths.changelogFile().
     */
    suspend fun downloadChangelogFile(fileName: String): ByteArray? {
        val auth = getAuth() ?: return null
        val api = YandexDiskApi.getInstance()
        val path = DiskPaths.changelogFile(fileName)

        var lastError: String? = null

        for (attempt in 1..DOWNLOAD_MAX_ATTEMPTS) {
            try {
                // 1. Получаем ссылку на скачивание
                val urlResponse = api.getDiskDownloadUrl(auth, path)
                if (!urlResponse.isSuccessful) {
                    val code = urlResponse.code()

                    // 4xx — бессмысленно ретраить (кроме 429)
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

                // 2. Скачиваем файл по ссылке
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
    // DELETE
    // ============================================================

    /**
     * Удаляет файл changelog с Диска.
     * Используется для очистки своих старых пакетов и чужих
     * (уже обработанных) в SyncManager.cleanup.
     *
     * 🆕 v17.4.0 (хвост №3): до DELETE_MAX_ATTEMPTS попыток
     * с паузой DELETE_RETRY_DELAY_MS при 5xx / 429 / DNS / timeout.
     *
     * Логика:
     *  - 4xx (кроме 429) — сразу false, без retry (файла нет или нет прав).
     *    404 считается успехом: файла уже нет, цель достигнута.
     *  - 429 — retry (rate limit).
     *  - 5xx / DNS / timeout / исключение — retry до исчерпания попыток.
     *  - 200/204 — успех.
     *
     * 🆕 B3: путь — из DiskPaths.changelogFile().
     */
    suspend fun deleteChangelogFile(fileName: String): Boolean {
        val auth = getAuth() ?: return false
        val api = YandexDiskApi.getInstance()
        val path = DiskPaths.changelogFile(fileName)

        var lastError: String? = null

        for (attempt in 1..DELETE_MAX_ATTEMPTS) {
            try {
                val response = api.deleteFile(auth, path, false)
                val code = response.code()

                if (response.isSuccessful) {
                    Logger.log(TAG, "deleteChangelogFile: deleted $fileName")
                    return true
                }

                // 404 — файла уже нет, считаем успехом
                if (code == 404) {
                    Logger.log(TAG, "deleteChangelogFile: $fileName already missing (404), treat as success")
                    return true
                }

                // 4xx — бессмысленно ретраить (кроме 429)
                if (code in 400..499 && code != 429) {
                    Logger.log(TAG, "deleteChangelogFile: 4xx for $fileName, code=$code, no retry")
                    return false
                }

                lastError = "code=$code"
                Logger.log(
                    TAG,
                    "deleteChangelogFile: attempt $attempt/$DELETE_MAX_ATTEMPTS " +
                        "for $fileName failed, code=$code"
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
                        "deleteChangelogFile: attempt $attempt/$DELETE_MAX_ATTEMPTS " +
                            "DNS failure for $fileName: ${e.message}"
                    )
                } else {
                    Logger.log(
                        TAG,
                        "deleteChangelogFile: attempt $attempt/$DELETE_MAX_ATTEMPTS " +
                            "error for $fileName: ${e.message}"
                    )
                }
                if (attempt < DELETE_MAX_ATTEMPTS) {
                    delay(DELETE_RETRY_DELAY_MS)
                    continue
                }
                return false
            }
        }

        Logger.log(TAG, "deleteChangelogFile: all $DELETE_MAX_ATTEMPTS attempts failed for $fileName (last=$lastError)")
        return false
    }
}
