package com.family.base.data.remote

import com.family.base.Config
import com.family.base.data.remote.model.DiskResourcesResponse
import com.family.base.data.remote.model.LinkResponse
import com.family.base.data.remote.model.PublicResourcesResponse
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/**
 * Интерфейс для работы с API Яндекс.Диска.
 * Содержит методы для публичных (чтение) и приватных (чтение/запись) операций.
 *
 * 🆕 v20.6.1: добавлен метод downloadPreviewWebDav для скачивания превью
 * через WebDAV API. Прямые preview-ссылки REST API отдают 403 без куки,
 * а WebDAV с OAuth-токеном работает.
 */
interface YandexDiskApi {

    // ============================================================
    // ПУБЛИЧНЫЕ МЕТОДЫ (работа с публичной папкой по public_key)
    // ============================================================

    @GET("disk/public/resources")
    suspend fun getPublicResources(
        @Query("public_key") publicKey: String,
        @Query("path") path: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): retrofit2.Response<PublicResourcesResponse>

    @GET("disk/public/resources/download")
    suspend fun getPublicDownloadUrl(
        @Query("public_key") publicKey: String,
        @Query("path") path: String
    ): retrofit2.Response<LinkResponse>

    @GET
    suspend fun downloadFile(
        @Url url: String
    ): retrofit2.Response<okhttp3.ResponseBody>

    @DELETE("disk/public/resources")
    suspend fun deletePublicFile(
        @Query("public_key") publicKey: String,
        @Query("path") path: String
    ): retrofit2.Response<Void>

    // ============================================================
    // ПРИВАТНЫЕ МЕТОДЫ (с OAuth-токеном, для записи и управления)
    // ============================================================

    @GET("disk/resources/upload")
    suspend fun getUploadUrl(
        @Header("Authorization") authorization: String,
        @Query("path") path: String,
        @Query("overwrite") overwrite: Boolean = true
    ): retrofit2.Response<LinkResponse>

    @PUT
    suspend fun uploadFileToUrl(
        @Url url: String,
        @Body file: okhttp3.RequestBody
    ): retrofit2.Response<Void>

    @PUT("disk/resources")
    suspend fun createFolder(
        @Header("Authorization") authorization: String,
        @Query("path") path: String
    ): retrofit2.Response<Void>

    @GET("disk/resources/download")
    suspend fun getDiskDownloadUrl(
        @Header("Authorization") authorization: String,
        @Query("path") path: String
    ): retrofit2.Response<LinkResponse>

    /**
     * Получение информации о ресурсе (файле или папке) на диске пользователя.
     *
     * @param limit       максимум элементов в выдаче (default 200).
     * @param offset      сдвиг для пагинации (default 0).
     * @param previewSize размер превью (null — не запрашивать;
     *                    "S"/"M"/"L" — запросить ссылку в поле `preview`).
     */
    @GET("disk/resources")
    suspend fun getDiskResources(
        @Header("Authorization") authorization: String,
        @Query("path") path: String,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("preview_size") previewSize: String? = null
    ): retrofit2.Response<DiskResourcesResponse>

    @DELETE("disk/resources")
    suspend fun deleteFile(
        @Header("Authorization") authorization: String,
        @Query("path") path: String,
        @Query("permanently") permanently: Boolean = false
    ): retrofit2.Response<Unit>

    // ============================================================
    // 🆕 v20.6.1: WEBDAV — скачивание превью с OAuth-токеном
    // ============================================================

    /**
     * Скачивает превью файла через WebDAV API Яндекс.Диска.
     *
     * Почему WebDAV, а не REST preview-ссылка:
     *  - REST API в поле `preview` возвращает ссылку на downloader.disk.yandex.ru,
     *    которая при запросе из кода (без браузерных куки) отдаёт 403;
     *  - WebDAV API с заголовком Authorization: OAuth <токен> возвращает
     *    готовое изображение (тело ответа) с кодом 200.
     *
     * Пример URL:
     *   https://webdav.yandex.ru/Романовы/Праздники/фото.jpg?preview&size=M
     *
     * Требует тариф Яндекс 360 (у пользователя есть).
     *
     * @param url           полный URL WebDAV с параметрами ?preview&size=M
     * @param authorization заголовок "OAuth <токен>"
     * @return Response<ResponseBody> — тело ответа содержит байты изображения
     */
    @GET
    suspend fun downloadPreviewWebDav(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    // ============================================================
    // КОМПАНЬОН ДЛЯ СОЗДАНИЯ КЛИЕНТА
    // ============================================================

    companion object {
        private var INSTANCE: YandexDiskApi? = null

        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 15L
        private const val WRITE_TIMEOUT_SEC = 20L
        private const val CALL_TIMEOUT_SEC = 45L

        fun getInstance(): YandexDiskApi {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: create().also { INSTANCE = it }
            }
        }

        private fun create(): YandexDiskApi {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
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

            return Retrofit.Builder()
                .baseUrl(Config.YANDEX_DISK_API_BASE)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(YandexDiskApi::class.java)
        }
    }
}
