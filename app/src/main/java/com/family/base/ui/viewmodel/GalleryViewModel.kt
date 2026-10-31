package com.family.base.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.family.base.data.local.AppDatabase
import com.family.base.data.model.GalleryItem
import com.family.base.data.repository.GalleryRepository
import com.family.base.util.Logger
import kotlinx.coroutines.launch

/**
 * 🆕 v20.4.9: ViewModel экрана галереи.
 *
 * Один экземпляр на один экран GalleryActivity. Живёт, пока жива Activity.
 * При переходе в подпапку создаётся НОВАЯ GalleryActivity → новый ViewModel
 * с новым path. Это проще, чем один ViewModel со стеком путей: системный
 * стек Activity сам обрабатывает «Назад».
 *
 * Состояние:
 *  - items         — накопленный список (папки + фото + видео-плейсхолдеры);
 *  - isLoading     — идёт загрузка первой страницы или следующей;
 *  - hasMore       — есть ли ещё страницы (последняя вернула PAGE_SIZE элементов);
 *  - error         — текст ошибки, если загрузка упала;
 *  - folderPath    — абсолютный путь текущей папки на Диске
 *                    (например "/Романовы/Праздники"), нужен для заголовка
 *                    и для перехода «внутрь» при клике на подпапку.
 *
 * Пагинация:
 *  - при создании грузим offset=0 (первая страница);
 *  - loadNextPage() грузит offset = items.size (следующая);
 *  - hasMore = (последняя страница вернула ровно PAGE_SIZE);
 *  - при ошибке hasMore не меняем — пользователь может повторить.
 *
 * ⚠️ НИЧЕГО не сохраняем в Room. Это чистое «живое» чтение с Диска.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "GalleryViewModel"

    private val db = AppDatabase.getInstance(application)
    private val repository = GalleryRepository(db)

    // ============================================================
    // СОСТОЯНИЕ
    // ============================================================

    private val _items = MutableLiveData<List<GalleryItem>>(emptyList())
    val items: LiveData<List<GalleryItem>> = _items

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _hasMore = MutableLiveData(false)
    val hasMore: LiveData<Boolean> = _hasMore

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _folderPath = MutableLiveData("")
    val folderPath: LiveData<String> = _folderPath

    /**
     * Путь, который ViewModel сейчас показывает.
     * Хранится отдельно от LiveData, чтобы loadNextPage() брал
     * актуальное значение без гонок с observer'ами.
     */
    private var currentPath: String = ""

    /** Защита от параллельных loadNextPage() (пользователь быстро скроллит). */
    private var loadingInProgress: Boolean = false

    // ============================================================
    // ИНИЦИАЛИЗАЦИЯ
    // ============================================================

    /**
     * Открыть папку. Вызывается ОДИН раз из onCreate Activity.
     *
     * @param path абсолютный путь на Диске, например "/Романовы".
     *             Если пусто — берём корень галереи из DiskPaths.GALLERY.
     */
    fun openFolder(path: String) {
        if (currentPath == path && _items.value?.isNotEmpty() == true) {
            Logger.log(TAG, "openFolder: already on $path, skip")
            return
        }

        Logger.log(TAG, "openFolder: $path")
        currentPath = path
        _folderPath.value = path
        _items.value = emptyList()
        _error.value = null
        _hasMore.value = true

        loadNextPage()
    }

    // ============================================================
    // ПАГИНАЦИЯ
    // ============================================================

    /**
     * Загрузить следующую страницу.
     *
     * Вызывается:
     *  - из openFolder() (первая страница, offset=0);
     *  - из GalleryActivity при прокрутке до конца (RecyclerView scroll listener).
     *
     * Защита от повторного вызова: если loadingInProgress — выходим.
     * После загрузки: items накапливаются, hasMore = (вернулось ровно PAGE_SIZE).
     */
    fun loadNextPage() {
        if (loadingInProgress) {
            Logger.log(TAG, "loadNextPage: already loading, skip")
            return
        }
        if (_hasMore.value == false) {
            Logger.log(TAG, "loadNextPage: no more pages")
            return
        }
        if (currentPath.isEmpty()) {
            Logger.log(TAG, "loadNextPage: currentPath is empty, skip")
            return
        }

        loadingInProgress = true
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            val offset = _items.value?.size ?: 0
            Logger.log(TAG, "loadNextPage: path=$currentPath offset=$offset")

            val result = repository.listPage(currentPath, offset)

            loadingInProgress = false
            _isLoading.value = false

            result.fold(
                onSuccess = { page ->
                    // Защита от гонки: пока грузили, пользователь мог уйти
                    // в другую папку (маловероятно в Шаге 1, но пусть будет).
                    if (page.folderPath != currentPath && currentPath.isNotEmpty()) {
                        // body.path с Диска может отличаться от того, что мы послали
                        // (например, регистр или завершающий слэш) — обновим.
                        currentPath = page.folderPath
                        _folderPath.value = page.folderPath
                    }

                    val old = _items.value.orEmpty()
                    val merged = old + page.items
                    _items.value = merged

                    // Если Диск вернул меньше PAGE_SIZE — дальше страниц нет.
                    _hasMore.value = page.rawCount >= GalleryRepository.PAGE_SIZE

                    Logger.log(
                        TAG,
                        "loadNextPage OK: got=${page.items.size}, total=${merged.size}, " +
                            "rawCount=${page.rawCount}, hasMore=${_hasMore.value}"
                    )
                },
                onFailure = { e ->
                    val msg = e.message ?: "Не удалось загрузить галерею"
                    _error.value = msg
                    Logger.log(TAG, "loadNextPage FAILED: $msg", e)
                }
            )
        }
    }

    // ============================================================
    // ПОВТОР ПОСЛЕ ОШИБКИ
    // ============================================================

    /**
     * Повторить загрузку после ошибки.
     *
     * Логика:
     *  - если список пуст — грузим первую страницу (openFolder с тем же path);
     *  - если список не пуст — грузим следующую (loadNextPage).
     *
     * Используется кнопкой «Повторить» в пустом состоянии или при ошибке.
     */
    fun retry() {
        Logger.log(TAG, "retry: currentPath=$currentPath, items=${_items.value?.size ?: 0}")
        _error.value = null
        loadNextPage()
    }

    /**
     * Принудительное обновление (pull-to-refresh).
     * Сбрасывает накопленный список и грузит первую страницу заново.
     */
    fun refresh() {
        if (currentPath.isEmpty()) {
            Logger.log(TAG, "refresh: currentPath empty, skip")
            return
        }
        Logger.log(TAG, "refresh: path=$currentPath")
        _items.value = emptyList()
        _hasMore.value = true
        _error.value = null
        loadNextPage()
    }
}
