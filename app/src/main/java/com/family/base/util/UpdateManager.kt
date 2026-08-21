package com.family.base.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.family.base.data.remote.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        private const val VERSION_URL = "https://gist.githubusercontent.com/ваш_логин/.../raw/versions.json"
    }

    suspend fun checkForUpdate(currentVersionCode: Int): AppVersion? = withContext(Dispatchers.IO) {
        Logger.log("UpdateManager", "=== checkForUpdate START ===")
        Logger.log("UpdateManager", "Current version code: $currentVersionCode")
        
        return@withContext try {
            val json = downloadJson(VERSION_URL)
            val versionCode = json.getInt("versionCode")
            val versionName = json.getString("versionName")
            val downloadUrl = json.getString("downloadUrl")
            val releaseNotes = json.optString("releaseNotes", null)

            Logger.log("UpdateManager", "Remote version: $versionName ($versionCode)")
            Logger.log("UpdateManager", "Need update: ${versionCode > currentVersionCode}")

            if (versionCode > currentVersionCode) {
                AppVersion(versionCode, versionName, downloadUrl, releaseNotes)
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.log("UpdateManager", "Error in checkForUpdate", e)
            null
        }
    }

    private fun downloadJson(urlString: String): JSONObject {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connect()

        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

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
            }
            context.startActivity(intent)
        }
    }

    fun downloadAndInstall(apkUrl: String, versionName: String) {
        Logger.log("UpdateManager", "=== downloadAndInstall START ===")
        Logger.log("UpdateManager", "APK URL: $apkUrl")
        
        // Проверяем разрешение
        if (!canInstallPackages()) {
            Logger.log("UpdateManager", "No install permission, requesting...")
            requestInstallPermission()
            return
        }

        val fileName = "baza_$versionName.apk"
        val downloadDir = java.io.File(
    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
    "BAZA"
)
if (!downloadDir.exists()) {
    downloadDir.mkdirs()
}
        val file = File(downloadDir, fileName)

        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Обновление БАЗА")
            .setDescription("Загрузка версии $versionName...")
            .setDestinationUri(Uri.fromFile(file))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)

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
                            Logger.log("UpdateManager", "Download SUCCESS, starting install")
                            installApk(file)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            isDownloading = false
                            Logger.log("UpdateManager", "Download FAILED")
                        }
                    }
                }
                cursor.close()
                Thread.sleep(1000)
            }
        }.start()
    }

    private fun installApk(file: File) {
        Logger.log("UpdateManager", "=== installApk START ===")
        
        if (!file.exists()) {
            Logger.log("UpdateManager", "File does not exist: ${file.absolutePath}")
            return
        }
        
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            Logger.log("UpdateManager", "Install intent started")
        } else {
            Logger.log("UpdateManager", "No activity to handle install intent")
        }
    }
}
