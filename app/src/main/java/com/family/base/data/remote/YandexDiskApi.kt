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
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Фасад для работы с хранилищем данных.
 *
 * 🆕 v23.3.19: Поддержка локального WebDAV (Rclone через ZeroTier) через флаг Config.USE_LOCAL_WEBDAV_SYNC.
 * - Если true: приватные методы (upload, download, list, delete, mkdir) работают через WebDAV (Basic Auth).
 * - Если false: работает через Яндекс.Диск REST API (OAuth).
 * - Публичные методы (getPublic*, downloadPreviewWebDav) всегда идут на Яндекс.Диск (не зависят от флага).
 *
 * Стратегия «Фасад»: все сигнатуры методов сохранены, вызывающий код (CatalogRepository, SyncManagerApi,
 * DiskUploader, BackupManagerV2, SyncViewModel) не требует изменений.
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

        // --- Таймауты (сохранены из предыдущей версии) ---
        private const val CONNECT_TIMEOUT_SEC = 15L
        private const val READ_TIMEOUT_SEC = 60L
        private const val WRITE_TIMEOUT_SEC = 120L
        private const val CALL_TIMEOUT_SEC = 300L

        // 1. Клиент для Яндекса (REST API + публичные ссылки + превью галереи)
        private val yandexClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
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
        private val webdavClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10L, TimeUnit.SECONDS) // Локальная сеть — быстро
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

        // 3. Retrofit для публичных методов Яндекса (getPublicResources, getPublicDownloadUrl)
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

        // Внутренний Retrofit-интерфейс только для публичных методов Яндекса
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

    /**
     * 🆕 v20.6.1: Скачивание превью через WebDAV API Яндекс.Диска.
     * Всегда идёт на Яндекс (для галереи «Романовы»), не зависит от Config.USE_LOCAL_WEBDAV_SYNC.
     */
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
    // ПРИВАТНЫЕ МЕТОДЫ (Яндекс / WebDAV — по флагу)
    // ============================================================

    suspend fun getUploadUrl(
        auth: String,
        path: String,
        overwrite: Boolean = true
    ): Response<LinkResponse> {
        return if (Config.USE_LOCAL_WEBDAV_SYNC) {
            // Для WebDAV возвращаем прямой URL для PUT
            Response.success(LinkResponse(href = normalizeWebdavPath(path), method = "PUT"))
        } else {
            yandexGetUploadUrl(auth, path, overwrite)
        }
    }

    /**
     * Загрузка файла по прямому URL.
     * URL может быть:
     *  - временной ссылкой Яндекса (из getUploadUrl) → yandexClient
     *  - локальным WebDAV-URL (из normalizeWebdavPath) → webdavClient
     */
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
        auth: String,
        path: String
    ): Response<Void> {
        return if (Config.USE_LOCAL_WEBDAV_SYNC) {
            webdavMkdir(path)
        } else {
            yandexCreateFolder(auth, path)
        }
    }

    suspend fun getDiskDownloadUrl(
        auth: String,
        path: String
    ): Response<LinkResponse> {
        return if (Config.USE_LOCAL_WEBDAV_SYNC) {
            // Для WebDAV возвращаем прямой URL для GET
            Response.success(LinkResponse(href = normalizeWebdavPath(path), method = "GET"))
        } else {
            yandexGetDownloadUrl(auth, path)
        }
    }

    /**
     * Скачивание файла по прямому URL.
     * URL может быть:
     *  - временной ссылкой Яндекса (из getDiskDownloadUrl) → yandexClient
     *  - локальным WebDAV-URL (из normalizeWebdavPath) → webdavClient
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

    suspend fun getDiskResources(
        auth: String,
        path: String,
        limit: Int = 200,
        offset: Int = 0,
        previewSize: String? = null
    ): Response<DiskResourcesResponse> {
        return if (Config.USE_LOCAL_WEBDAV_SYNC) {
            webdavList(path)
        } else {
            yandexGetResources(auth, path, limit, offset, previewSize)
        }
    }

    suspend fun deleteFile(
        auth: String,
        path: String,
        permanently: Boolean = false
    ): Response<Unit> {
        return if (Config.USE_LOCAL_WEBDAV_SYNC) {
            webdavDelete(path)
        } else {
            yandexDelete(auth, path, permanently)
        }
    }

    // ============================================================
    // WEBDAV IMPLEMENTATION (PRIVATE)
    // ============================================================

    private fun normalizeWebdavPath(path: String): String {
        // path вида "/BAZA/data/items.json" → "http://10.251.135.94:8080/BAZA/data/items.json"
        val cleanPath = path.trimStart('/')
        return "${Config.LOCAL_WEBDAV_URL}$cleanPath"
    }

    private suspend fun webdavMkdir(path: String): Response<Void> {
        val url = normalizeWebdavPath(path)
        val request = Request.Builder().url(url).method("MKCOL", null).build()
        val response = webdavClient.newCall(request).execute()
        return when {
            response.isSuccessful || response.code == 405 -> Response.success(null) // 405 = уже существует
            else -> Response.error(response.code, response.body ?: emptyBody())
        }
    }

    private suspend fun webdavDelete(path: String): Response<Unit> {
        val url = normalizeWebdavPath(path)
        val request = Request.Builder().url(url).delete().build()
        val response = webdavClient.newCall(request).execute()
        return when {
            response.isSuccessful || response.code == 404 -> Response.success(Unit) // 404 = уже удалён
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

    private fun parseWebdavXml(xml: String, basePath: String): List<WebdavItem> {
        val items = mutableListOf<WebdavItem>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray()))
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val basePathNormalized = basePath.trimStart('/').trimEnd('/')
            for (i in 0 until responses.length) {
                val node = responses.item(i) as org.w3c.dom.Element
                val hrefNode = node.getElementsByTagNameNS("DAV:", "href").item(0) ?: continue
                val href = hrefNode.textContent.trim().trimStart('/').trimEnd('/')
                // Исключаем саму запрашиваемую папку (первый response в PROPFIND)
                if (href == basePathNormalized) continue
                val propstatNode = node.getElementsByTagNameNS("DAV:", "propstat").item(0) as? org.w3c.dom.Element
                    ?: continue
                val propNode = propstatNode.getElementsByTagNameNS("DAV:", "prop").item(0) as? org.w3c.dom.Element
                    ?: continue
                val resourcetypeNode = propNode.getElementsByTagNameNS("DAV:", "resourcetype").item(0)
                val collectionNode = resourcetypeNode?.getElementsByTagNameNS("DAV:", "collection")?.item(0)
                val isDir = collectionNode != null
                val displayNameNode = propNode.getElementsByTagNameNS("DAV:", "displayname").item(0)
                val name = displayNameNode?.textContent ?: href.substringAfterLast('/')
                val contentLengthNode = propNode.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)
                val size = contentLengthNode?.textContent?.toLongOrNull()
                if (name.isNotEmpty()) {
                    items.add(WebdavItem(name, isDir, size))
                }
            }
        } catch (e: Exception) {
            // XML parse error — возвращаем пустой список
        }
        return items
    }

    // ============================================================
    // YANDEX REST API IMPLEMENTATION (PRIVATE)
    // ============================================================

    private suspend fun yandexGetUploadUrl(
        auth: String,
        path: String,
        overwrite: Boolean
    ): Response<LinkResponse> {
        val url = "${Config.YANDEX_DISK_API_BASE}disk/resources/upload?path=${URLEncoder.encode(path, "UTF-8")}&overwrite=$overwrite"
        val request = Request.Builder().url(url).header("Authorization", auth).get().build()
        val response = yandexClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return Response.error(response.code, response.body ?: emptyBody())
        }
        val json = response.body?.string() ?: return Response.error(500, emptyBody())
        val model = gson.fromJson(json, LinkResponse::class.java)
        return Response.success(model)
    }

    private suspend fun yandexGetDownloadUrl(
        auth: String,
        path: String
    ): Response<LinkResponse> {
        val url = "${Config.YANDEX_DISK_API_BASE}disk/resources/download?path=${URLEncoder.encode(path, "UTF-8")}"
        val request = Request.Builder().url(url).header("Authorization", auth).get().build()
        val response = yandexClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return Response.error(response.code, response.body ?: emptyBody())
        }
        val json = response.body?.string() ?: return Response.error(500, emptyBody())
        val model = gson.fromJson(json, LinkResponse::class.java)
        return Response.success(model)
    }

    private suspend fun yandexGetResources(
        auth: String,
        path: String,
        limit: Int,
        offset: Int,
        previewSize: String?
    ): Response<DiskResourcesResponse> {
        val urlBuilder = StringBuilder("${Config.YANDEX_DISK_API_BASE}disk/resources?path=${URLEncoder.encode(path, "UTF-8")}&limit=$limit&offset=$offset")
        if (previewSize != null) {
            urlBuilder.append("&preview_size=$previewSize")
        }
        val request = Request.Builder().url(urlBuilder.toString()).header("Authorization", auth).get().build()
        val response = yandexClient.newCall(request).execute()
        if (!response.isSuccessful) {
            return Response.error(response.code, response.body ?: emptyBody())
        }
        val json = response.body?.string() ?: return Response.error(500, emptyBody())
        val model = gson.fromJson(json, DiskResourcesResponse::class.java)
        return Response.success(model)
    }

    private suspend fun yandexCreateFolder(
        auth: String,
        path: String
    ): Response<Void> {
        val url = "${Config.YANDEX_DISK_API_BASE}disk/resources?path=${URLEncoder.encode(path, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
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
        auth: String,
        path: String,
        permanently: Boolean
    ): Response<Unit> {
        val url = "${Config.YANDEX_DISK_API_BASE}disk/resources?path=${URLEncoder.encode(path, "UTF-8")}&permanently=$permanently"
        val request = Request.Builder().url(url).header("Authorization", auth).delete().build()
        val response = yandexClient.newCall(request).execute()
        return if (response.isSuccessful) {
            Response.success(Unit)
        } else {
            Response.error(response.code, response.body ?: emptyBody())
        }
    }
}
