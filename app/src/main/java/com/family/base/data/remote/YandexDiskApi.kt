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
    @GET("public/resources")
    suspend fun getPublicResources(
        @Query("public_key") publicKey: String,
        @Query("path") path: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): retrofit2.Response<PublicResourcesResponse>

    /**
     * Получение ссылки для скачивания файла из публичной папки.
     */
    @GET("public/resources/download")
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
    @DELETE("public/resources")
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
     */
    @GET("disk/resources")
    suspend fun getDiskResources(
        @Header("Authorization") authorization: String,
        @Query("path") path: String
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
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
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
