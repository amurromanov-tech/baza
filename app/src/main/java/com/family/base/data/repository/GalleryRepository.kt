package com.family.base.data.repository

import com.family.base.BaseApplication
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.OutboxLogger
import com.family.base.data.model.GalleryItem
import com.family.base.data.remote.YandexDiskApi
import com.family.base.util.DiskPaths
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * 🆕 v20.4.9: репозиторий галереи.
 *
 * Отвечает за:
 *  1. Листинг папки «/Романовы» (и вложенных) через Яндекс.Диск API.
 *  2. Создание системной папки «🖼️ Галерея» в локальной БД (Room).
 *  3. 🆕 v20.6.1: скачивание превью через WebDAV (с OAuth-токеном).
 *
 * ⚠️ Оригиналы не качаются. Только превью через WebDAV.
 *
 * 🆕 v20.6.2: encodeWebDavPath теперь срезает префикс "disk:".
 * REST API возвращает пути вида "disk:/Романовы/...", а WebDAV
 * ожидает просто "/Романовы/...". Без срезания — HTTP 401.
 */
class GalleryRepository(
    private val db: AppDatabase
) {

    private val TAG = "GalleryRepository"

    companion object {
        const val PAGE_SIZE = 100
        private const val WEBDAV_BASE = "https://webdav.yandex.ru"
    }

    // ============================================================
    // ЛОКАЛЬНАЯ СИСТЕМНАЯ ПАПКА «🖼️ ГАЛЕРЕЯ»
    // ============================================================

    /**
     * Создаёт системную папку «🖼️ Галерея» в локальной БД,
     * если её ещё нет. Идемпотентно.
     */
    suspend fun ensureGalleryFolder(createdBy: String) = withContext(Dispatchers.IO) {
        val existing = db.folderDao().getFolderById(FolderEntity.SYSTEM_ID_GALLERY)
        if (existing != null) {
            Logger.log(TAG, "ensureGalleryFolder: already exists")
            return@withContext
        }

        val folder = FolderEntity(
            id = FolderEntity.SYSTEM_ID_GALLERY,
            name = "🖼️ Галерея",
            parentId = null,
            parentItemId = null,
            createdBy = createdBy,
            path = "/🖼️ Галерея",
            isSystem = true,
            systemKey = FolderEntity.SYSTEM_KEY_GALLERY
        )
        db.folderDao().insertFolder(folder)
        OutboxLogger.folderChanged(
            db = db,
            folder = folder,
            action = OutboxEntity.ACTION_CREATE,
            changedBy = createdBy
        )
        Logger.log(TAG, "ensureGalleryFolder: created ${folder.id}")
    }

    // ============================================================
    // ЛИСТИНГ ПАПКИ
    // ============================================================

    /**
     * Одна страница листинга папки на Яндекс.Диске.
     */
    suspend fun listPage(
        path: String,
        offset: Int
    ): Result<GalleryListPage> = withContext(Dispatchers.IO) {
        try {
            val auth = getAuthHeader()
                ?: return@withContext Result.failure(
                    IllegalStateException("Нет токена Яндекс.Диска")
                )

            val api = YandexDiskApi.getInstance()
            val response = api.getDiskResources(
                authorization = auth,
                path = path,
                limit = PAGE_SIZE,
                offset = offset,
                previewSize = "M"
            )

            if (!response.isSuccessful) {
                Logger.log(
                    TAG,
                    "listPage: HTTP ${response.code()} for path=$path offset=$offset"
                )
                return@withContext Result.failure(
                    IllegalStateException("Диск вернул HTTP ${response.code()}")
                )
            }

            val body = response.body()
                ?: return@withContext Result.failure(
                    IllegalStateException("Пустой ответ от Диска")
                )

            val rawItems = body.embedded?.items ?: emptyList()
            val folderPath = body.path?.takeIf { it.isNotEmpty() } ?: path

            val mapped: List<GalleryItem> = rawItems.mapNotNull { raw ->
                try {
                    GalleryItem.fromDiskItem(raw, folderPath)
                } catch (e: Exception) {
                    Logger.log(TAG, "listPage: skip bad item '${raw.name}': ${e.message}")
                    null
                }
            }

            val sorted = mapped.sortedWith(
                compareBy<GalleryItem> { if (it.isFolder) 0 else 1 }
                    .thenBy { it.name.lowercase() }
            )

            Logger.log(
                TAG,
                "listPage: path=$path offset=$offset → ${sorted.size} items " +
                    "(raw=${rawItems.size}, folders=${sorted.count { it.isFolder }})"
            )

            Result.success(
                GalleryListPage(
                    items = sorted,
                    folderPath = folderPath,
                    rawCount = rawItems.size
                )
            )
        } catch (e: Exception) {
            Logger.log(TAG, "listPage error for $path offset=$offset: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ============================================================
    // 🆕 v20.6.1: СКАЧИВАНИЕ ПРЕВЬЮ ЧЕРЕЗ WEBDAV
    // ============================================================

    /**
     * Скачивает превью файла через WebDAV API (с OAuth-токеном).
     *
     * @param path абсолютный путь на Диске. Может быть в формате
     *             "disk:/Романовы/..." (из REST API) или "/Романовы/..."
     *             (уже очищенный). Префикс "disk:" срезается.
     * @return байты изображения (JPEG) или null при ошибке.
     */
    suspend fun downloadPreview(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val auth = getAuthHeader()
                ?: run {
                    Logger.log(TAG, "downloadPreview: no token")
                    return@withContext null
                }

            val encodedPath = encodeWebDavPath(path)
            val url = "$WEBDAV_BASE$encodedPath?preview&size=M"

            // 🆕 v20.6.2: логируем длину заголовка и префикс (без токена).
            Logger.log(
                TAG,
                "downloadPreview: auth length=${auth.length}, " +
                    "prefix=${auth.take(6)}, url=$url"
            )

            val api = YandexDiskApi.getInstance()
            val response = api.downloadPreviewWebDav(url, auth)

            if (!response.isSuccessful) {
                Logger.log(
                    TAG,
                    "downloadPreview: HTTP ${response.code()} for $path (url=$url)"
                )
                return@withContext null
            }

            val body = response.body()
                ?: return@withContext null

            val bytes = body.bytes()
            Logger.log(
                TAG,
                "downloadPreview: OK ${bytes.size} bytes for '${path.substringAfterLast('/')}'"
            )
            bytes
        } catch (e: Exception) {
            Logger.log(TAG, "downloadPreview error for $path: ${e.message}", e)
            null
        }
    }

    /**
     * Кодирует путь для WebDAV: каждый сегмент через URLEncoder,
     * слэши сохраняются.
     *
     * 🆕 v20.6.2: срезаем префикс "disk:" — REST API возвращает
     * пути вида "disk:/Романовы/...", а WebDAV ожидает "/Романовы/...".
     * Без этого WebDAV отдаёт 401.
     */
    private fun encodeWebDavPath(path: String): String {
        // Срезаем "disk:" (если есть).
        val cleanPath = if (path.startsWith("disk:")) {
            path.removePrefix("disk:")
        } else {
            path
        }

        // Путь всегда начинается с "/".
        val normalized = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
        val segments = normalized.split("/").filter { it.isNotEmpty() }
        val encodedSegments = segments.map { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return "/" + encodedSegments.joinToString("/")
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================

    private fun getAuthHeader(): String? {
        return try {
            val context = BaseApplication.getAppContext()
            val tokenStorage = TokenStorage(context)
            val token = tokenStorage.getAccessToken()
            if (token.isNullOrEmpty()) null else "OAuth $token"
        } catch (e: Exception) {
            Logger.log(TAG, "getAuthHeader error: ${e.message}")
            null
        }
    }
}

/**
 * 🆕 v20.4.9: результат одной страницы листинга.
 */
data class GalleryListPage(
    val items: List<GalleryItem>,
    val folderPath: String,
    val rawCount: Int
)
