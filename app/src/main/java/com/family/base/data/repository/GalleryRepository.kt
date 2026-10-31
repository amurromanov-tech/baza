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

/**
 * 🆕 v20.4.9: репозиторий галереи.
 *
 * Отвечает за две вещи:
 *  1. Листинг папки «/Романовы» (и вложенных) через Яндекс.Диск API.
 *     Пагинация по 100 — чтобы не вешать Диск и телефон при тысячах фото.
 *  2. Создание системной папки «🖼️ Галерея» в локальной БД (Room).
 *     Это единственная запись, которая нужна галерее в БД: она
 *     отображается в корне БАЗЫ и перехватывает клик в MainActivity.
 *
 * ⚠️ Фото НЕ скачиваются. В сетке и в полноэкранном просмотре
 * используются временные ссылки preview с Диска (Coil грузит их
 * напрямую). Оригинал (fileUrl) не трогаем в Шаге 1.
 *
 * ⚠️ Токен — тот же, что у синка (TokenStorage.getAccessToken).
 * Если токен протух — вернём ошибку; UI предложит синхронизироваться
 * (existing ensureValidToken в SyncViewModel переиспользуем в Шаге 3).
 */
class GalleryRepository(
    private val db: AppDatabase
) {

    private val TAG = "GalleryRepository"

    /** Размер страницы при листинге. 100 — компромисс между скоростью и нагрузкой. */
    companion object {
        const val PAGE_SIZE = 100
    }

    // ============================================================
    // ЛОКАЛЬНАЯ СИСТЕМНАЯ ПАПКА «🖼️ ГАЛЕРЕЯ»
    // ============================================================

    /**
     * Создаёт системную папку «🖼️ Галерея» в локальной БД,
     * если её ещё нет. Идемпотентно: повторный вызов ничего не делает.
     *
     * Вызывается из MainViewModel.checkFirstLaunch() — рядом
     * с taskRepository.ensureSystemFolder(...).
     *
     * Пишется в outbox (ACTION_CREATE), чтобы папка разошлась
     * через changelog на другие устройства. Синк сам по себе
     * не критичен: даже если changelog ещё не ушёл, папка уже
     * видна локально и открывает GalleryActivity по systemKey.
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
     *
     * @param path   абсолютный путь на Диске, например "/Романовы"
     *               или "/Романовы/Праздники".
     * @param offset сдвиг для пагинации (0, 100, 200, ...).
     *
     * @return Result.success(items) — список элементов страницы.
     *         Result.failure(e)     — сетевая ошибка / нет токена.
     *
     * Пустой список означает «элементов больше нет» — вызывающий
     * код (ViewModel) по этому признаку ставит hasMore = false.
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

            // Определяем «родительский путь» для сборки полного пути.
            // body.path — путь самой папки (например "/Романовы/Праздники").
            // Если вдруг пусто — используем переданный path.
            val folderPath = body.path?.takeIf { it.isNotEmpty() } ?: path

            val mapped: List<GalleryItem> = rawItems.mapNotNull { raw ->
                try {
                    GalleryItem.fromDiskItem(raw, folderPath)
                } catch (e: Exception) {
                    Logger.log(TAG, "listPage: skip bad item '${raw.name}': ${e.message}")
                    null
                }
            }

            // Сортируем: папки сверху, потом файлы, всё по имени A→Я.
            // Пагинацию это не ломает: каждая страница сортируется отдельно,
            // накопленный список в ViewModel тоже сортируется (см. ViewModel).
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
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================

    /**
     * Заголовок авторизации из TokenStorage.
     * Тот же паттерн, что в CatalogRepository — переиспользуем.
     */
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
 *
 * @param items      элементы страницы (уже отсортированы: папки сверху).
 * @param folderPath абсолютный путь самой папки (для навигации «Назад»
 *                   и для заголовка экрана).
 * @param rawCount   сколько сырых элементов вернул Диск (до фильтрации).
 *                   Нужен, чтобы понять «это последняя страница или нет»:
 *                   если rawCount < PAGE_SIZE — значит, дальше пусто.
 */
data class GalleryListPage(
    val items: List<GalleryItem>,
    val folderPath: String,
    val rawCount: Int
)
