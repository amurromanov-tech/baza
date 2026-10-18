package com.family.base.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.family.base.Config
import com.family.base.data.remote.AppVersion
import com.family.base.data.remote.YandexDiskApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Менеджер обновлений приложения.
 *
 * 🆕 B3: прямых путей Яндекс.Диска здесь нет — только Config.APK_FILE_NAME
 * и Config.APK_YANDEX_PUBLIC_URL. Правок по DiskPaths не требуется.
 * Локальные пути APK (Downloads) остаются в классе — это не Яндекс.Диск.
 *
 * 🆕 Хвост «UpdateManager 500»: downloadJson (GitHub raw) и resolveDirectApkUrl
 * (Яндекс API) теперь делают до 3 попыток с паузой 500/1000 мс при
 * 5xx / 429 / DNS / timeout / IOException. 4xx (кроме 429) — без retry.
 *
 * Логика:
 *  - 4xx (кроме 429) — сразу null/throw, ретраить бессмысленно.
 *  - 429 — retry (rate limit).
 *  - 5xx / DNS / timeout / IOException — retry до исчерпания попыток.
 *  - Успех — сразу результат.
 *
 * Снаружи поведение не меняется: checkForUpdate всё так же возвращает
 * AppVersion? или null, downloadAndInstall всё так же либо стартует
 * скачивание, либо логирует ошибку и выходит.
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        /** Сколько попыток делаем при 5xx/429/DNS/timeout. */
        private const val MAX_ATTEMPTS = 3

        /** Базовая пауза между попытками (умножается на номер попытки). */
        private const val RETRY_BASE_DELAY_MS = 500L
    }

    // ============================================================
    // ПРОВЕРКА ОБНОВЛЕНИЯ
    // ============================================================
    suspend fun checkForUpdate(currentVersionCode: Int): AppVersion? = withContext(Dispatchers.IO) {
        Logger.log(TAG, "=== checkForUpdate START ===")
        Logger.log(TAG, "Current version code: $currentVersionCode")
        Logger.log(TAG, "VERSION_JSON_URL: ${Config.VERSION_JSON_URL}")

        return@withContext try {
            val json = downloadJson(Config.VERSION_JSON_URL)
            val versionCode = json.getInt("versionCode")
            val versionName = json.getString("versionName")
            val downloadUrl = json.optString("downloadUrl", Config.APK_YANDEX_PUBLIC_URL)
            val releaseNotes = json.optString("releaseNotes", null)

            Logger.log(TAG, "Remote version: $versionName ($versionCode)")
            Logger.log(TAG, "Download URL (public): $downloadUrl")
            Logger.log(TAG, "Need update: ${versionCode > currentVersionCode}")

            if (versionCode > currentVersionCode) {
                AppVersion(versionCode, versionName, downloadUrl, releaseNotes)
            } else {
                Logger.log(TAG, "Already up to date")
                null
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error in checkForUpdate: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // ============================================================
    // СКАЧИВАНИЕ version.json (с retry)
    // ============================================================

    /**
     * Скачивает version.json с GitHub raw.
     *
     * 🆕 До MAX_ATTEMPTS попыток с паузой RETRY_BASE_DELAY_MS * attempt
     * при 5xx / 429 / DNS / timeout / IOException.
     * 4xx (кроме 429) — сразу исключение, без retry.
     *
     * Бросает исключение, если все попытки провалились — checkForUpdate
     * ловит его и возвращает null (как раньше при разовой ошибке).
     */
    private suspend fun downloadJson(urlString: String): JSONObject {
        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val code = connection.responseCode

                // 4xx (кроме 429) — бессмысленно ретраить
                if (code in 400..499 && code != 429) {
                    Logger.log(TAG, "downloadJson: 4xx ($code), no retry")
                    throw Exception("HTTP $code: failed to fetch version.json")
                }

                if (code != 200) {
                    // 5xx или 429 — потенциально retry
                    lastError = Exception("HTTP $code: failed to fetch version.json")
                    Logger.log(
                        TAG,
                        "downloadJson: attempt $attempt/$MAX_ATTEMPTS failed, HTTP $code"
                    )
                    if (attempt < MAX_ATTEMPTS) {
                        delay(RETRY_BASE_DELAY_MS * attempt)
                        continue
                    }
                    throw lastError
                }

                val text = connection.inputStream.bufferedReader().use { it.readText() }
                return JSONObject(text)
            } catch (e: Exception) {
                // Если это наше собственное исключение про 4xx — не ретраим
                if (e is IOException || e is SocketTimeoutException || e is UnknownHostException) {
                    lastError = e
                    Logger.log(
                        TAG,
                        "downloadJson: attempt $attempt/$MAX_ATTEMPTS IO error: ${e.message}"
                    )
                    if (attempt < MAX_ATTEMPTS) {
                        delay(RETRY_BASE_DELAY_MS * attempt)
                        continue
                    }
                    throw e
                }
                // Прочие исключения (включая наш throw про 4xx) — пробрасываем
                throw e
            } finally {
                try { connection?.disconnect() } catch (_: Exception) { }
            }
        }

        throw lastError ?: Exception("downloadJson failed after $MAX_ATTEMPTS attempts")
    }

    // ============================================================
    // ПОЛУЧЕНИЕ ПРЯМОЙ ССЫЛКИ НА APK (с retry)
    // Публичная ссылка Яндекс.Диска → прямая ссылка через API
    // ============================================================

    /**
     * Получает прямую ссылку на APK.
     *
     * Если urlOrPublicLink — не Яндекс.Диск, возвращает как есть, без сети.
     *
     * 🆕 Если Яндекс.Диск — до MAX_ATTEMPTS попыток с паузой
     * RETRY_BASE_DELAY_MS * attempt при 5xx / 429 / DNS / timeout.
     * 4xx (кроме 429) — сразу throw, без retry.
     */
    private suspend fun resolveDirectApkUrl(urlOrPublicLink: String): String {
        // Если это уже прямая ссылка на файл (не Яндекс.Диск) — возвращаем как есть
        if (!urlOrPublicLink.contains("disk.yandex.ru/d/")) {
            Logger.log(TAG, "Using URL as is: $urlOrPublicLink")
            return urlOrPublicLink
        }

        Logger.log(TAG, "Resolving Yandex.Disk public URL: $urlOrPublicLink")
        Logger.log(TAG, "APK file name: ${Config.APK_FILE_NAME}")

        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val api = YandexDiskApi.getInstance()
                val response = api.getPublicDownloadUrl(
                    publicKey = urlOrPublicLink,
                    path = Config.APK_FILE_NAME
                )

                val code = response.code()
                Logger.log(TAG, "getPublicDownloadUrl attempt $attempt/$MAX_ATTEMPTS, code=$code")

                // 4xx (кроме 429) — бессмысленно ретраить
                if (code in 400..499 && code != 429) {
                    val errorBody = response.errorBody()?.string()
                    Logger.log(TAG, "Failed to resolve: HTTP $code (no retry), body=$errorBody")
                    throw Exception("Не удалось получить прямую ссылку на APK (HTTP $code)")
                }

                if (!response.isSuccessful) {
                    // 5xx или 429 — retry
                    lastError = Exception("Не удалось получить прямую ссылку на APK (HTTP $code)")
                    Logger.log(
                        TAG,
                        "resolveDirectApkUrl: attempt $attempt/$MAX_ATTEMPTS failed, HTTP $code"
                    )
                    if (attempt < MAX_ATTEMPTS) {
                        delay(RETRY_BASE_DELAY_MS * attempt)
                        continue
                    }
                    throw lastError
                }

                val href = response.body()?.href
                if (!href.isNullOrEmpty()) {
                    Logger.log(TAG, "Resolved direct URL: $href")
                    return href
                }

                // 200 без href — странно, считаем как 5xx и ретраим
                lastError = Exception("getPublicDownloadUrl вернул 200 без href")
                Logger.log(
                    TAG,
                    "resolveDirectApkUrl: attempt $attempt/$MAX_ATTEMPTS — 200 but empty href"
                )
                if (attempt < MAX_ATTEMPTS) {
                    delay(RETRY_BASE_DELAY_MS * attempt)
                    continue
                }
                throw lastError
            } catch (e: Exception) {
                // Если это наше собственное исключение про 4xx — не ретраим
                if (e is IOException || e is SocketTimeoutException || e is UnknownHostException) {
                    lastError = e
                    Logger.log(
                        TAG,
                        "resolveDirectApkUrl: attempt $attempt/$MAX_ATTEMPTS IO error: ${e.message}"
                    )
                    if (attempt < MAX_ATTEMPTS) {
                        delay(RETRY_BASE_DELAY_MS * attempt)
                        continue
                    }
                    throw e
                }
                throw e
            }
        }

        throw lastError ?: Exception("resolveDirectApkUrl failed after $MAX_ATTEMPTS attempts")
    }

    // ============================================================
    // ПРОВЕРКА РАЗРЕШЕНИЯ НА УСТАНОВКУ
    // ============================================================
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    // ============================================================
    // СКАЧИВАНИЕ И УСТАНОВКА APK
    // ============================================================
    suspend fun downloadAndInstall(apkUrl: String, versionName: String) {
        Logger.log(TAG, "=== downloadAndInstall START ===")
        Logger.log(TAG, "APK URL (raw): $apkUrl")
        Logger.log(TAG, "Version name: $versionName")

        // Проверяем разрешение на установку из неизвестных источников
        if (!canInstallPackages()) {
            Logger.log(TAG, "No install permission, requesting...")
            requestInstallPermission()
            return
        }

        // Получаем прямую ссылку (для Яндекс.Диска — через API, с retry)
        val directUrl = try {
            resolveDirectApkUrl(apkUrl)
        } catch (e: Exception) {
            Logger.log(TAG, "Failed to resolve direct URL: ${e.message}")
            return
        }

        Logger.log(TAG, "Direct APK URL: $directUrl")

        // v14.1.3: DownloadManager сам создаёт файл в приватной папке Downloads/.
        // setDestinationUri(Uri.fromFile) на Android 10+ не работает — DownloadManager
        // не имеет права писать file:// URI в приватную папку приложения.
        val fileName = "baza_$versionName.apk"

        Logger.log(TAG, "Saving APK as: $fileName (Downloads)")

        val request = DownloadManager.Request(Uri.parse(directUrl))
            .setTitle("Обновление БАЗА")
            .setDescription("Загрузка версии $versionName...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)

        Logger.log(TAG, "Download enqueued, id=$downloadId")

        // Ожидаем завершения загрузки в фоне
        Thread {
            var isDownloading = true
            while (isDownloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = manager.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            isDownloading = false
                            Logger.log(TAG, "Download SUCCESS")

                            // Получаем URI скачанного файла через DownloadManager —
                            // это правильный способ на Android 10+ вместо Uri.fromFile(file)
                            val downloadedUri = manager.getUriForDownloadedFile(downloadId)
                            if (downloadedUri != null) {
                                Logger.log(TAG, "Downloaded URI: $downloadedUri")
                                installApkFromUri(downloadedUri)
                            } else {
                                Logger.log(TAG, "Downloaded URI is null, falling back to file path")
                                val file = File(
                                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                                    fileName
                                )
                                if (file.exists()) {
                                    installApk(file)
                                } else {
                                    Logger.log(TAG, "Fallback file does not exist either")
                                }
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            isDownloading = false
                            val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                            Logger.log(TAG, "Download FAILED, reason=$reason")
                        }
                    }
                }
                cursor.close()
                Thread.sleep(1000)
            }
        }.start()
    }

    // ============================================================
    // УСТАНОВКА APK (по URI от DownloadManager)
    // ============================================================
    private fun installApkFromUri(uri: Uri) {
        Logger.log(TAG, "=== installApkFromUri START ===")
        Logger.log(TAG, "URI: $uri")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            Logger.log(TAG, "Install intent started")
        } else {
            Logger.log(TAG, "No activity to handle install intent")
        }
    }

    // ============================================================
    // УСТАНОВКА APK (по File — fallback)
    // ============================================================
    private fun installApk(file: File) {
        Logger.log(TAG, "=== installApk START ===")

        if (!file.exists()) {
            Logger.log(TAG, "File does not exist: ${file.absolutePath}")
            return
        }

        Logger.log(TAG, "APK file size: ${file.length()} bytes")

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        installApkFromUri(uri)
    }
}
