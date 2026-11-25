package com.family.base.data.remote

import android.util.Base64
import com.family.base.Config
import com.family.base.data.remote.model.DiskEmbedded
import com.family.base.data.remote.model.DiskResourceItem
import com.family.base.data.remote.model.DiskResourcesResponse
import com.family.base.data.remote.model.LinkResponse
import com.family.base.data.remote.model.PublicResourcesResponse
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Фасад для работы с хранилищем данных.
 *
 * 🆕 v23.3.19: Поддержка локального WebDAV (Rclone через ZeroTier) через флаг Config.USE_LOCAL_WEBDAV_SYNC.
 * - Если true: приватные методы для путей внутри /BAZA работают через WebDAV (Basic Auth).
 * - Если false: работает через Яндекс.Диск REST API (OAuth).
 * - Пути ВНЕ /BAZA (например, /Романовы для галереи) всегда идут на Яндекс.Диск.
 * - Публичные методы (getPublic*, downloadPreviewWebDav) всегда идут на Яндекс.Диск.
 *
 * Стратегия «Фасад»: все сигнатуры методов сохранены, вызывающий код не требует изменений.
 */
class YandexDiskApi private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: YandexDiskApi? = null

        fun getInstance(): YandexDiskApi {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: YandexDiskApi().also { INSTANCE = it }
            }
        }

        private const val CONNECT_TIMEOUT_SEC = 15L
        private const val READ_TIMEOUT_SEC = 60L
        private const val WRITE_TIMEOUT_SEC = 120L
        private const val CALL_TIMEOUT_SEC = 300L

        // 1. Клиент для Яндекса (REST API + публичные ссылки + превью галереи)
        private val yandexClient: OkHttpClient by lazy {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(logging)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val newRequest = request.newBuilder()
                        .header("Accept", "application/json")
                        .header("User-Agent", "BAZA/1.0")
                        .build()
                    chain.proceed(newRequest)
                }
                .build()
        }

        // 2. Клиент для локального WebDAV (Rclone на ПК через ZeroTier)
        // Без логирования BODY, чтобы не забивать память при загрузке больших видео
        private val webdavClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10L, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val credentials = "${Config.LOCAL_WEBDAV_USER}:${Config.LOCAL_WEBDAV_PASSWORD}"
                    val basicAuth = "Basic " + Base64.encodeToString(
                        credentials.toByteArray(),
                        Base64.NO_WRAP
                    )
                    val request = chain.request().newBuilder()
                        .header("Authorization", basicAuth)
                        .build()
                    chain.proceed(request)
                }
                .build()
        }

        // 3. Retrofit для публичных методов Яндекса
        private val yandexRetrofit: Retrofit by lazy {
            Retrofit.Builder()
                .baseUrl(Config.YANDEX_DISK_API_BASE)
                .client(yandexClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }

        private val publicYandexService: PublicYandexService by lazy {
            yandexRetrofit.create(PublicYandexService::class.java)
        }

        private val gson = Gson()

        private interface PublicYandexService {
            @GET("disk/public/resources")
            suspend fun getPublicResources(
                @Query("public_key") publicKey: String,
                @Query("path") path: String?,
                @Query("limit") limit: Int?,
                @Query("offset") offset: Int?
            ): Response<PublicResourcesResponse>

            @GET("disk/public/resources/download")
            suspend fun getPublicDownloadUrl(
                @Query("public_key") publicKey: String,
                @Query("path") path: String
            ): Response<LinkResponse>
        }

        private fun emptyBody(): ResponseBody {
            return "".toResponseBody("text/plain".toMediaType())
        }

        /**
         * Определяет, должен ли путь идти на локальный WebDAV.
         * Правило: путь начинается с /BAZA (или /BAZA/...) → WebDAV, иначе → Яндекс.
         * Это гарантирует, что галерея (/Романовы) всегда останется на Яндексе.
         */
        private fun shouldUseWebdav(path: String): Boolean {
            if (!Config.USE_LOCAL_WEBDAV_SYNC) return false
            val normalizedPath = path.trimStart('/')
            return normalizedPath.startsWith(Config.SHARED_FOLDER_NAME)
        }
    }

    // ============================================================
    // ПУБЛИЧНЫЕ МЕТОДЫ (Яндекс.Диск — НЕ ТРОГАЕМ)
    // ============================================================

    suspend fun getPublicResources(
        publicKey: String,
        path: String? = null,
        limit: Int? = null,
        offset: Int? = null
    ): Response<PublicResourcesResponse> {
        return publicYandexService.getPublicResources(publicKey, path, limit, offset)
    }

    suspend fun getPublicDownloadUrl(
        publicKey: String,
        path: String
    ): Response<LinkResponse> {
        return publicYandexService.getPublicDownloadUrl(publicKey, path)
    }

    suspend fun deletePublicFile(
        publicKey: String,
        path: String
    ): Response<Void> {
        val url = "${Config.YANDEX_DISK_API_BASE}disk/public/resources?public_key=$publicKey&path=${URLEncoder.encode(path, "UTF-8")}"
        val request = Request.Builder().url(url).delete().build()
        val response = yandexClient.newCall(request).execute()
        return if (response.isSuccessful) {
            Response.success(null)
        } else {
            Response.error(response.code, response.body ?: emptyBody())
        }
    }

    suspend fun downloadPreviewWebDav(
        url: String,
        authorization: String
    ): Response<ResponseBody> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .get()
            .build()
        val response = yandexClient.newCall(request).execute()
        return if (response.isSuccessful) {
            Response.success(response.body)
        } else {
            Response.error(response.code, response.body ?: emptyBody())
        }
    }

    // ============================================================
    // ПРИВАТНЫЕ МЕТОДЫ (Яндекс / WebDAV — по флагу и пути)
    // ============================================================

    suspend fun getUploadUrl(
        authorization: String,
        path: String,
        overwrite: Boolean = true
    ): Response<LinkResponse> {
        return if (shouldUseWebdav(path)) {
            Response.success(LinkResponse(href = normalizeWebdavPath(path), method = "PUT"))
        } else {
            yandexGetUploadUrl(authorization, path, overwrite)
        }
    }

    /**
     * Универсальный метод скачивания по URL.
     * URL может быть временной ссылкой Яндекса или локальным WebDAV-URL.
     */
    suspend fun downloadFile(url: String): Response<ResponseBody> {
        val request = Request.Builder().url(url).get().build()
        val client = if (url.startsWith(Config.LOCAL_WEBDAV_URL)) webdavClient else yandexClient
        val response = client.newCall(request).execute()
        return if (response.isSuccessful) {
            Response.success(response.body)
        } else {
            Response.error(response.code, response.body ?: emptyBody())
        }
    }

    suspend fun uploadFileToUrl(
        url: String,
        file: okhttp3.RequestBody
    ): Response<Void> {
        val request = Request.Builder().url(url).put(file).build()
        val client = if (url.startsWith(Config.LOCAL_WEBDAV_URL)) webdavClient else yandexClient
        val response = client.newCall(request).execute()
        return if (response.isSuccessful) {
            Response.success(null)
        } else {
            Response.error(response.code, response.body ?: emptyBody())
        }
    }

    suspend fun createFolder(
        authorization: String,
        path: String
    ): Response<Void> {
        return if (shouldUseWebdav(path)) {
            webdavMkdir(path)
        } else {
            yandexCreateFolder(authorization, path)
        }
    }

    suspend fun getDiskDownloadUrl(
        authorization: String,
        path: String
    ): Response<LinkResponse> {
        return if (shouldUseWebdav(path)) {
            // Для WebDAV честно проверяем существование файла через HEAD-запрос
            val exists = webdavCheckExists(path)
            if (exists) {
                Response.success(LinkResponse(href = normalizeWebdavPath(path), method = "GET"))
            } else {
                Response.error(404, emptyBody())
            }
        } else {
            yandexGetDownloadUrl(authorization, path)
        }
    }

    suspend fun getDiskResources(
        authorization: String,
        path: String,
        limit: Int = 200,
        offset: Int = 0,
        previewSize: String? = null
    ): Response<DiskResourcesResponse> {
        return if (shouldUseWebdav(path)) {
            webdavList(path)
        } else {
            yandexGetResources(authorization, path, limit, offset, previewSize)
        }
    }

    suspend fun deleteFile(
        authorization: String,
        path: String,
        permanently: Boolean = false
    ): Response<Unit> {
        return if (shouldUseWebdav(path)) {
            webdavDelete(path)
        } else {
            yandexDelete(authorization, path, permanently)
        }
    }

    // ============================================================
    // WEBDAV IMPLEMENTATION (PRIVATE)
    // ============================================================

    private fun normalizeWebdavPath(path: String): String {
        val cleanPath = path.trimStart('/')
        return "${Config.LOCAL_WEBDAV_URL}$cleanPath"
    }

    private suspend fun webdavCheckExists(path: String): Boolean {
        val url = normalizeWebdavPath(path)
        val request = Request.Builder().url(url).head().build()
        return try {
            val response = webdavClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun webdavMkdir(path: String): Response<Void> {
        val url = normalizeWebdavPath(path)
        val request = Request.Builder().url(url).method("MKCOL", null).build()
        val response = webdavClient.newCall(request).execute()
        return when {
            response.isSuccessful || response.code == 405 -> Response.success(null)
            else -> Response.error(response.code, response.body ?: emptyBody())
        }
    }

    private suspend fun webdavDelete(path: String): Response<Unit> {
        val url = normalizeWebdavPath(path)
        val request = Request.Builder().url(url).delete().build()
        val response = webdavClient.newCall(request).execute()
        return when {
            response.isSuccessful || response.code == 404 -> Response.success(Unit)
            else -> Response.error(response.code, response.body ?: emptyBody())
        }
    }

    private suspend fun webdavList(path: String): Response<DiskResourcesResponse> {
        val url = normalizeWebdavPath(path)
        val xmlBody = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
   <d:prop>
      <d:displayname/>
      <d:resourcetype/>
      <d:getcontentlength/>
   </d:prop>
</d:propfind>"""
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", xmlBody.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        val response = webdavClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return Response.error(response.code, response.body ?: emptyBody())
        }
        val bodyStr = response.body?.string() ?: return Response.error(500, emptyBody())
        val items = parseWebdavXml(bodyStr, path)
        val diskItems = items.map { item ->
            DiskResourceItem(
                name = item.name,
                path = if (path.endsWith("/")) "$path${item.name}" else "$path/${item.name}",
                type = if (item.isDirectory) "dir" else "file",
                file = if (!item.isDirectory) "true" else null,
                md5 = null,
                preview = null,
                size = item.size,
                created = null,
                mimeType = null,
                mediaType = null
            )
        }
        val embedded = DiskEmbedded(items = diskItems)
        val result = DiskResourcesResponse(
            embedded = embedded,
            name = path.substringAfterLast('/'),
            path = path
        )
        return Response.success(result)
    }

    private data class WebdavItem(
        val name: String,
        val isDirectory: Boolean,
        val size: Long?
    )

    /**
     * Парсинг WebDAV PROPFIND ответа через Regex.
     * Это на 100% защищает от ошибок компиляции "Unresolved reference",
     * которые возникают при использовании org.w3c.dom в Android/Kotlin.
     */
    private fun parseWebdavXml(xml: String, basePath: String): List<WebdavItem> {
        val items = mutableListOf<WebdavItem>()
        try {
            val responseRegex = Regex("<[^>]*:response[^>]*>(.*?)</[^>]*:response>", RegexOption.DOT_MATCHES_ALL)
            val hrefRegex = Regex("<[^>]*:href[^>]*>(.*?)</[^>]*:href>")
            val nameRegex = Regex("<[^>]*:displayname[^>]*>(.*?)</[^>]*:displayname>")
            val lengthRegex = Regex("<[^>]*:getcontentlength[^>]*>(.*?)</[^>]*:getcontentlength>")
            val collectionRegex = Regex("<[^>]*:collection[^>]*/>")

            for (match in responseRegex.findAll(xml)) {
                val block = match.groupValues[1]
                val hrefMatch = hrefRegex.find(block) ?: continue
                
                val href = hrefMatch.groupValues[1].trim().trimStart('/').trimEnd('/')
                val basePathNormalized = basePath.trimStart('/').trimEnd('/')
                if (href == basePathNormalized || href.isEmpty()) continue

                val isDirectory = collectionRegex.containsMatchIn(block)
                val nameMatch = nameRegex.find(block)
                val lengthMatch = lengthRegex.find(block)
                
                val displayName = nameMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                val size = lengthMatch?.groupValues?.getOrNull(1)?.trim()?.toLongOrNull()
                
                val finalName = displayName ?: href.substringAfterLast('/')
                if (finalName.isNotEmpty()) {
                    items.add(WebdavItem(finalName, isDirectory, size))
                }
            }
        } catch (e: Exception) {
            // XML parse error — игнорируем и возвращаем то, что успели
        }
        return items
    }

    // ============================================================
    // YANDEX REST API IMPLEMENTATION (PRIVATE)
    // ============================================================

    private suspend fun yandexGetUploadUrl(
        authorization: String,
        path: String,
        overwrite: Boolean
    ): Response<LinkResponse> {
        val url = "${Config.YANDEX_DISK_API_BASE}disk/resources/upload?path=${URLEncoder.encode(path, "UTF-8")}&overwrite=$overwrite"
        val request = Request.Builder().url(url).header("Authorization", authorization).get().build()
        val response = yandexClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return Response.error(response.code, response.body ?: emptyBody())
        }
        val json = response.body?.string() ?: return Response.error(500, emptyBody())
        val model = gson.fromJson(json, LinkResponse::class.java)
        return Response.success(model)
    }

    private suspend fun yandexGetDownloadUrl(
        authorization: String,
        path: String
    ): Response<LinkResponse> {
        val url = "${Config.YANDEX_DISK_API_BASE}disk/resources/download?path=${URLEncoder.encode(path, "UTF-8")}"
        val request = Request.Builder().url(url).header("Authorization", authorization).get().build()
        val response = yandexClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return Response.error(response.code, response.body ?: emptyBody())
        }
        val json = response.body?.string() ?: return Response.error(500, emptyBody())
        val model = gson.fromJson(json, LinkResponse::class.java)
        return Response.success(model)
    }

    private suspend fun yandexGetResources(
        authorization: String,
        path: String,
        limit: Int,
        offset: Int,
        previewSize: String?
    ): Response<DiskResourcesResponse> {
        val urlBuilder = StringBuilder("${Config.YANDEX_DISK_API_BASE}disk/resources?path=${URLEncoder.encode(path, "UTF-8")}&limit=$limit&offset=$offset")
        if (previewSize != null) {
            urlBuilder.append("&preview_size=$previewSize")
        }
        val request = Request.Builder().url(urlBuilder.toString()).header("Authorization", authorization).get().build()
        val response = yandexClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return Response.error(response.code, response.body ?: emptyBody())
        }
        val json = response.body?.string() ?: return Response.error(500, emptyBody())
        val model = gson.fromJson(json, DiskResourcesResponse::class.java)
        return Response.success(model)
    }

    private suspend fun yandexCreateFolder(
        authorization: String,
        path: String
    ): Response<Void> {
        val url = "${Config.YANDEX_DISK_API_BASE}disk/resources?path=${URLEncoder.encode(path, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .put("".toRequestBody("application/json".toMediaType()))
            .build()
        val response = yandexClient.newCall(request).execute()
        return if (response.isSuccessful) {
            Response.success(null)
        } else {
            Response.error(response.code, response.body ?: emptyBody())
        }
    }

    private suspend fun yandexDelete(
        authorization: String,
        path: String,
        permanently: Boolean
    ): Response<Unit> {
        val url = "${Config.YANDEX_DISK_API_BASE}disk/resources?path=${URLEncoder.encode(path, "UTF-8")}&permanently=$permanently"
        val request = Request.Builder().url(url).header("Authorization", authorization).delete().build()
        val response = yandexClient.newCall(request).execute()
        return if (response.isSuccessful) {
            Response.success(Unit)
        } else {
            Response.error(response.code, response.body ?: emptyBody())
        }
    }
}
