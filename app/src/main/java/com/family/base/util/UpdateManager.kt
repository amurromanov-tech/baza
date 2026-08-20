package com.family.base.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
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
        // Замените на вашу реальную ссылку на versions.json
        private const val VERSION_URL = "https://disk.yandex.ru/d/AbCdEfGhIjKlMnOp?dl=1"
    }

    suspend fun checkForUpdate(currentVersionCode: Int): AppVersion? = withContext(Dispatchers.IO) {
        return@withContext try {
            val json = downloadJson(VERSION_URL)
            val versionCode = json.getInt("versionCode")
            val versionName = json.getString("versionName")
            val downloadUrl = json.getString("downloadUrl")
            val releaseNotes = json.optString("releaseNotes", null)

            if (versionCode > currentVersionCode) {
                AppVersion(versionCode, versionName, downloadUrl, releaseNotes)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

    fun downloadAndInstall(apkUrl: String, versionName: String) {
        val fileName = "baza_$versionName.apk"
        val downloadDir = context.getExternalFilesDir(null) ?: context.filesDir
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
                            installApk(file)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            isDownloading = false
                            Logger.log("UpdateManager", "Download failed")
                        }
                    }
                }
                cursor.close()
                Thread.sleep(1000)
            }
        }.start()
    }

    private fun installApk(file: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
    }
}
