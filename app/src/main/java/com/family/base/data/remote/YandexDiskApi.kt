package com.family.base.data.remote

import com.family.base.Config
import com.family.base.data.remote.model.DiskResourcesResponse
import com.family.base.data.remote.model.LinkResponse
import com.family.base.data.remote.model.PublicResourcesResponse
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/**
 * Интерфейс для работы с API Яндекс.Диска.
 * Содержит методы для публичных (чтение) и приватных (чтение/запись) операций.
 */
interface YandexDiskApi {

    // ============================================================
    // ПУБЛИЧНЫЕ МЕТОДЫ (работа с публичной папкой по public_key)
    // ============================================================

    /**
     * Получение информации о публичной папке (список файлов и папок).
     */
    @GET("disk/public/resources")
    suspend fun getPublicResources(
        @Query("public_key") publicKey: String,
        @Query("path") path: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): retrofit2.Response<PublicResourcesResponse>

    /**
     * Получение ссылки для скачивания файла из публичной папки.
     */
    @GET("disk/public/resources/download")
    suspend fun getPublicDownloadUrl(
        @Query("public_key") publicKey: String,
        @Query("path") path: String
    ): retrofit2.Response<LinkResponse>

    /**
     * Скачивание файла по прямой ссылке.
     */
    @GET
    suspend fun downloadFile(
        @Url url: String
    ): retrofit2.Response<okhttp3.ResponseBody>

    /**
     * Удаление файла из публичной папки.
     */
    @DELETE("disk/public/resources")
    suspend fun deletePublicFile(
        @Query("public_key") publicKey: String,
        @Query("path") path: String
    ): retrofit2.Response<Void>

    // ============================================================
    // ПРИВАТНЫЕ МЕТОДЫ (с OAuth-токеном, для записи и управления)
    // ============================================================

    /**
     * Получение ссылки для загрузки файла в личный диск пользователя.
     */
    @GET("disk/resources/upload")
    suspend fun getUploadUrl(
        @Header("Authorization") authorization: String,
        @Query("path") path: String,
        @Query("overwrite") overwrite: Boolean = true
    ): retrofit2.Response<LinkResponse>

    /**
     * Загрузка файла по полученной ссылке (метод PUT).
     */
    @PUT
    suspend fun uploadFileToUrl(
        @Url url: String,
        @Body file: okhttp3.RequestBody
    ): retrofit2.Response<Void>

    /**
     * Создание папки на диске пользователя.
     */
    @PUT("disk/resources")
    suspend fun createFolder(
        @Header("Authorization") authorization: String,
        @Query("path") path: String
    ): retrofit2.Response<Void>

    /**
     * Получение ссылки для скачивания файла с приватного диска.
     */
    @GET("disk/resources/download")
    suspend fun getDiskDownloadUrl(
        @Header("Authorization") authorization: String,
        @Query("path") path: String
    ): retrofit2.Response<LinkResponse>

    /**
     * Получение информации о ресурсе (файле или папке) на диске пользователя.
     *
     * 🆕 v17.0.7: добавлены параметры `limit` и `offset`.
     *
     * ВАЖНО: Яндекс.Диск возвращает по умолчанию только 20 элементов.
     * Если в папке больше 20 файлов — новые НЕ попадут в выдачу, и всё,
     * что за 20-м элементом, будет невидимым. Именно из-за этого ломался
     * changelog: файлы физически лежали на Диске, но list их не видел.
     *
     * @param limit максимум элементов в выдаче (default 200 — с запасом
     *              для нашего changelog; Яндекс допускает до 10000).
     * @param offset сдвиг для пагинации (default 0 — берём с начала).
     */
      @GET("disk/resources")
    @GET("disk/resources")
    suspend fun getDiskResources(
        @Header("Authorization") authorization: String,
        @Query("path") path: String,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("preview_size") previewSize: String? = null,
        @Query("preview_crop") previewCrop: Boolean? = null
    ): retrofit2.Response<DiskResourcesResponse>

    /**
     * Удаление файла или папки с приватного диска пользователя.
     * @param authorization заголовок "OAuth <токен>"
     * @param path путь к файлу/папке на диске (например, "/BAZA/data/items.json")
     * @param permanently если true, файл удаляется без возможности восстановления (по умолчанию false)
     */
    @DELETE("disk/resources")
    suspend fun deleteFile(
        @Header("Authorization") authorization: String,
        @Query("path") path: String,
        @Query("permanently") permanently: Boolean = false
    ): retrofit2.Response<Unit>

    // ============================================================
    // КОМПАНЬОН ДЛЯ СОЗДАНИЯ КЛИЕНТА
    // ============================================================

    companion object {
        private var INSTANCE: YandexDiskApi? = null

        /**
         * Таймауты подобраны так, чтобы «плохая сеть» отваливалась
         * за 10-20 секунд, а не висела на дефолтных 10 / 60 / 60.
         */
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
