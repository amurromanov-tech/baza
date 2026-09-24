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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
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
    // СКАЧИВАНИЕ version.json
    // ============================================================
    private fun downloadJson(urlString: String): JSONObject {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        if (connection.responseCode != 200) {
            throw Exception("HTTP ${connection.responseCode}: failed to fetch version.json")
        }

        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    // ============================================================
    // ПОЛУЧЕНИЕ ПРЯМОЙ ССЫЛКИ НА APK
    // Публичная ссылка Яндекс.Диска → прямая ссылка через API
    // ============================================================
    private suspend fun resolveDirectApkUrl(urlOrPublicLink: String): String {
        // Если это уже прямая ссылка на файл (не Яндекс.Диск) — возвращаем как есть
        if (!urlOrPublicLink.contains("disk.yandex.ru/d/")) {
            Logger.log(TAG, "Using URL as is: $urlOrPublicLink")
            return urlOrPublicLink
        }

        Logger.log(TAG, "Resolving Yandex.Disk public URL: $urlOrPublicLink")
        Logger.log(TAG, "APK file name: ${Config.APK_FILE_NAME}")

        return try {
            val api = YandexDiskApi.getInstance()
            val response = api.getPublicDownloadUrl(
                publicKey = urlOrPublicLink,
                path = Config.APK_FILE_NAME
            )

            Logger.log(TAG, "getPublicDownloadUrl response code: ${response.code()}")

            if (response.isSuccessful) {
                val href = response.body()?.href
                if (!href.isNullOrEmpty()) {
                    Logger.log(TAG, "Resolved direct URL: $href")
                    return href
                }
                Logger.log(TAG, "Href is null in response")
            } else {
                val errorBody = response.errorBody()?.string()
                Logger.log(TAG, "Failed to resolve: HTTP ${response.code()}, body=$errorBody")
            }

            throw Exception("Не удалось получить прямую ссылку на APK (HTTP ${response.code()})")
        } catch (e: Exception) {
            Logger.log(TAG, "Error resolving Yandex URL: ${e.message}")
            e.printStackTrace()
            throw e
        }
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

        // Получаем прямую ссылку (для Яндекс.Диска — через API)
        val directUrl = try {
            resolveDirectApkUrl(apkUrl)
        } catch (e: Exception) {
            Logger.log(TAG, "Failed to resolve direct URL: ${e.message}")
            return
        }

        Logger.log(TAG, "Direct APK URL: $directUrl")

        // Папка для сохранения APK — внешняя папка приложения (не требует WRITE_EXTERNAL_STORAGE)
        val fileName = "baza_$versionName.apk"
        val downloadDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "updates"
        )
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val file = File(downloadDir, fileName)
        if (file.exists()) file.delete()

        Logger.log(TAG, "Saving APK to: ${file.absolutePath}")

        val request = DownloadManager.Request(Uri.parse(directUrl))
            .setTitle("Обновление БАЗА")
            .setDescription("Загрузка версии $versionName...")
            .setDestinationUri(Uri.fromFile(file))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

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
                            Logger.log(TAG, "Download SUCCESS, starting install")
                            installApk(file)
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
    // УСТАНОВКА APK
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
}
