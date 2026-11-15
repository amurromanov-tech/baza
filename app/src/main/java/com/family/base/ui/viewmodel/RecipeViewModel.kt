package com.family.base.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeFolderEntity
import com.family.base.data.repository.RecipesRepository
import com.family.base.ui.adapter.RecipeAdapter
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 🆕 v21.1.0 (Шаг 1.4b-1 + 1.5-4): ViewModel экрана «Рецепты».
 *
 * Отвечает за:
 *  - загрузку списка рецептов по source ("ai" | "user");
 *  - поиск (query) — через RecipesRepository.searchRecipes / searchInFolder /
 *    searchRootRecipes (в зависимости от уровня навигации);
 *  - сортировку (sortNewest: 🕐 Новые / 🔤 А-Я);
 *  - 🆕 подстановку локального файла первого фото каждому рецепту
 *    (RecipeAdapter.RecipeListItem.photoFile) — чтобы адаптер не ходил в БД;
 *  - уведомление UI об изменениях (LiveData).
 *
 * 🆕 Шаг 1.7-2 (миграция 22→23): ПАПКИ РЕЦЕПТОВ + НАВИГАЦИЯ.
 *  - Стейт экрана теперь двухуровневый: currentFolderId (null = корень
 *    «Моих рецептов», иначе — id открытой папки).
 *  - Папки и рецепты текущего уровня отдаются ДВУМЯ LiveData:
 *      • folders: LiveData<List<RecipeFolderEntity>>
 *      • recipes: LiveData<List<RecipeAdapter.RecipeListItem>>
 *    UI (Шаг 1.7-3) склеит их в один RecipeCatalogAdapter.
 *  - Навигация: openFolder(folderId), goBack() (в корень), goToRoot().
 *  - CRUD папок: createFolder(name), renameFolder(id, name), deleteFolder(id).
 *  - Перемещение рецепта: moveRecipeToFolder(recipeId, folderId).
 *
 *  Для «Рецептов ИИ» (source = "ai") папок нет — currentFolderId всегда null,
 *  UI не показывает кнопку «📁 Создать папку», а getFolders() вернёт пустой
 *  список (для ai просто не вызываем). Это проверяется в isFolderModeAvailable().
 *
 * Особенности:
 *  - loadContent() (ранее loadRecipes) отменяет предыдущую загрузку (Job) —
 *    нет гонки при быстром переключении сортировки/поиска/навигации.
 *  - searchQuery, sortNewest, currentFolderId хранятся в полях, чтобы
 *    повторный вызов loadContent() (например, после возврата из
 *    RecipeEditActivity) подхватил актуальное состояние.
 */
class RecipeViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "RecipeViewModel"

    private val db by lazy { AppDatabase.getInstance(application) }
    private val recipesRepository by lazy { RecipesRepository(db, application) }

    // ============================================================
    // СОСТОЯНИЕ ЭКРАНА (source задаётся один раз при старте)
    // ============================================================

    private var source: String = RecipeEntity.SOURCE_USER

    /** Текущий поисковый запрос (пустая строка = без поиска). */
    private var searchQuery: String = ""

    /** true = «🕐 Новые» (createdAt DESC), false = «🔤 А-Я» (title ASC). */
    private var sortNewest: Boolean = true

    /**
     * 🆕 Шаг 1.7-2: id открытой папки рецептов.
     * null — корень «Моих рецептов» (папки + рецепты без папки).
     * Не null — внутри указанной папки (только её содержимое).
     *
     * Для «Рецептов ИИ» (source = "ai") всегда null.
     */
    private var currentFolderId: String? = null

    private var loadJob: Job? = null

    // ============================================================
    // LIVEDATA ДЛЯ UI
    // ============================================================

    /**
     * 🆕 Шаг 1.7-2: папки текущего уровня.
     * Для «Рецептов ИИ» — всегда пустой список (папок нет).
     */
    private val _folders = MutableLiveData<List<RecipeFolderEntity>>(emptyList())
    val folders: LiveData<List<RecipeFolderEntity>> = _folders

    /**
     * Рецепты текущего уровня (с уже подставленным photoFile первого фото).
     * В корне «Моих рецептов» — только рецепты без папки (folderId IS NULL).
     * Внутри папки — рецепты этой папки.
     * В «Рецептах ИИ» — все живые ИИ-рецепты.
     */
    private val _recipes = MutableLiveData<List<RecipeAdapter.RecipeListItem>>(emptyList())
    val recipes: LiveData<List<RecipeAdapter.RecipeListItem>> = _recipes

    /** true — идёт загрузка (для прогресса / скрытия emptyState). */
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /**
     * true — на текущем уровне пусто (нет ни папок, ни рецептов)
     * с учётом активного поиска. Для показа emptyState.
     */
    private val _isEmpty = MutableLiveData(true)
    val isEmpty: LiveData<Boolean> = _isEmpty

    /**
     * 🆕 Шаг 1.7-2: id текущей открытой папки для UI (заголовок, хлебные крошки,
     * кнопка «назад»). null — корень.
     */
    private val _currentFolderId = MutableLiveData<String?>(null)
    val currentFolderIdLive: LiveData<String?> = _currentFolderId

    /**
     * 🆕 Шаг 1.7-2: имя текущей открытой папки (для заголовка ActionBar).
     * null — корень (тогда заголовок = имя экрана из EXTRA_SOURCE).
     */
    private val _currentFolderName = MutableLiveData<String?>(null)
    val currentFolderName: LiveData<String?> = _currentFolderName

    // ============================================================
    // ИНИЦИАЛИЗАЦИЯ
    // ============================================================

    fun init(source: String) {
        if (this.source == source) return
        this.source = source
        // При смене source всегда возвращаемся в корень.
        this.currentFolderId = null
        this.searchQuery = ""
        Logger.log(TAG, "init: source=$source")
    }

    fun getSource(): String = source

    /**
     * 🆕 Шаг 1.7-2: доступен ли режим папок для текущего source.
     * Папки есть только у «Моих рецептов» (source = "user").
     */
    fun isFolderModeAvailable(): Boolean = source == RecipeEntity.SOURCE_USER

    // ============================================================
    // ЗАГРУЗКА
    // ============================================================

    /**
     * Перезагрузить содержимое текущего уровня с учётом текущих
     * currentFolderId, searchQuery и sortNewest.
     *
     * Грузит:
     *  - папки уровня (только для source = "user" и только если searchQuery пуст —
     *    при поиске папки не показываем, ищем только рецепты);
     *  - рецепты уровня (в корне — без папки, внутри папки — её рецепты).
     *
     * Отменяет предыдущую загрузку, если она ещё идёт.
     */
    fun loadContent() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val isAi = (source == RecipeEntity.SOURCE_AI)
                val folderMode = isFolderModeAvailable()
                val folderId = currentFolderId
                val hasQuery = searchQuery.isNotBlank()

                // ---------- 1. Папки уровня ----------
                // Показываем только в «Моих рецептах», только в корне/папке,
                // и только при пустом поиске (при поиске — фокус на рецептах).
                val foldersList: List<RecipeFolderEntity> = if (folderMode && !hasQuery) {
                    recipesRepository.getFolders(folderId, sortNewest)
                } else {
                    emptyList()
                }

                // ---------- 2. Рецепты уровня ----------
                val recipesList: List<RecipeEntity> = when {
                    isAi -> {
                        // «Рецепты ИИ» — папок нет, ищем/грузим по source.
                        if (hasQuery) recipesRepository.searchRecipes(source, searchQuery)
                        else recipesRepository.getRecipesBySource(source, sortNewest)
                    }
                    folderId != null -> {
                        // Внутри папки «Моих рецептов».
                        if (hasQuery) recipesRepository.searchInFolder(folderId, searchQuery)
                        else recipesRepository.getRecipesInFolder(folderId, sortNewest)
                    }
                    else -> {
                        // Корень «Моих рецептов» — рецепты без папки.
                        if (hasQuery) recipesRepository.searchRootRecipes(source, searchQuery)
                        else recipesRepository.getRootRecipes(source, sortNewest)
                    }
                }

                // Сортировка «А-Я» для результатов поиска (search* отдают по createdAt DESC).
                val sortedRecipes: List<RecipeEntity> =
                    if (hasQuery && !sortNewest) recipesList.sortedBy { it.title.lowercase() }
                    else recipesList

                // ---------- 3. Подстановка локального файла первого фото ----------
                val appContext = getApplication<Application>()
                val listItems: List<RecipeAdapter.RecipeListItem> = sortedRecipes.map { recipe ->
                    val photoFile: File? = try {
                        val firstPhoto = recipesRepository.getPhotos(recipe.id).firstOrNull()
                        val name = firstPhoto?.imageUrl
                        if (!name.isNullOrBlank()) {
                            val f = ImageUtils.getRecipeMediaFile(appContext, recipe.id, name)
                            if (f.exists()) f else null
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Logger.log(
                            TAG,
                            "loadContent: photoFile error for ${recipe.id}: ${e.message}",
                            e
                        )
                        null
                    }

                    RecipeAdapter.RecipeListItem(recipe = recipe, photoFile = photoFile)
                }

                // ---------- 4. Имя текущей папки (для заголовка) ----------
                val currentName: String? = if (folderId != null) {
                    foldersList.firstOrNull { it.id == folderId }?.name
                        ?: recipesRepository.getFolders(null, true)
                            .firstOrNull { it.id == folderId }?.name
                        ?: folderId
                } else {
                    null
                }

                Logger.log(
                    TAG,
                    "loadContent: source=$source, folderId=$folderId, query='$searchQuery', " +
                        "sortNewest=$sortNewest, folders=${foldersList.size}, recipes=${listItems.size}, " +
                        "withPhoto=${listItems.count { it.photoFile != null }}"
                )

                _folders.value = foldersList
                _recipes.value = listItems
                _isEmpty.value = foldersList.isEmpty() && listItems.isEmpty()
                _currentFolderId.value = folderId
                _currentFolderName.value = currentName
            } catch (e: Exception) {
                Logger.log(TAG, "loadContent ERROR: ${e.message}", e)
                _folders.value = emptyList()
                _recipes.value = emptyList()
                _isEmpty.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Устаревшее имя (для совместимости с вызовами из RecipeListActivity,
     * которые ещё не переименованы). Просто делегирует в loadContent().
     */
    fun loadRecipes() {
        loadContent()
    }

    // ============================================================
    // 🆕 НАВИГАЦИЯ ПО ПАПКАМ (Шаг 1.7-2)
    // ============================================================

    /**
     * Открыть папку. Сбрасывает поиск (внутри папки ищем заново).
     * folderId не должен быть null — для возврата в корень есть goToRoot().
     */
    fun openFolder(folderId: String) {
        if (!isFolderModeAvailable()) {
            Logger.log(TAG, "openFolder: folder mode unavailable for source=$source")
            return
        }
        if (currentFolderId == folderId) return
        currentFolderId = folderId
        searchQuery = ""
        Logger.log(TAG, "openFolder: $folderId")
        loadContent()
    }

    /**
     * Вернуться в корень «Моих рецептов» (или «Рецептов ИИ»).
     * Сбрасывает поиск.
     */
    fun goToRoot() {
        if (currentFolderId == null) return
        currentFolderId = null
        searchQuery = ""
        Logger.log(TAG, "goToRoot")
        loadContent()
    }

    /**
     * Кнопка «назад»: если мы внутри папки — в корень, иначе — закрыть экран.
     * Возвращает true, если обработали (были в папке), false — если уже в корне
     * (UI должен вызвать finish()).
     */
    fun goBack(): Boolean {
        return if (currentFolderId != null) {
            goToRoot()
            true
        } else {
            false
        }
    }

    // ============================================================
    // ПОИСК
    // ============================================================

    fun setSearchQuery(query: String) {
        val normalized = query.trim()
        if (searchQuery == normalized) return
        searchQuery = normalized
        Logger.log(TAG, "setSearchQuery: '$searchQuery' (folderId=$currentFolderId)")
        loadContent()
    }

    fun getSearchQuery(): String = searchQuery

    fun clearSearch() {
        if (searchQuery.isEmpty()) return
        searchQuery = ""
        Logger.log(TAG, "clearSearch")
        loadContent()
    }

    // ============================================================
    // СОРТИРОВКА
    // ============================================================

    fun setSort(newest: Boolean) {
        if (sortNewest == newest) return
        sortNewest = newest
        Logger.log(TAG, "setSort: newest=$newest")
        loadContent()
    }

    fun isSortNewest(): Boolean = sortNewest

    // ============================================================
    // 🆕 CRUD ПАПОК (Шаг 1.7-2)
    // ============================================================

    /**
     * Создать папку на текущем уровне (в корне или внутри текущей папки).
     *
     * @param name название папки.
     * @param currentUser имя текущего пользователя.
     * @param onResult колбэк: null — успех, иначе — сообщение об ошибке для UI.
     */
    fun createFolder(
        name: String,
        currentUser: String,
        onResult: (String?) -> Unit
    ) {
        if (!isFolderModeAvailable()) {
            onResult("Папки доступны только в «Моих рецептах»")
            return
        }
        val parentId = currentFolderId
        viewModelScope.launch {
            try {
                val created = recipesRepository.createFolder(name, parentId, currentUser)
                if (created == null) {
                    onResult("Не удалось создать папку (пустое имя или ошибка)")
                } else {
                    Logger.log(TAG, "createFolder: OK id=${created.id}, name='${created.name}'")
                    onResult(null)
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "createFolder ERROR: ${e.message}", e)
                onResult("Ошибка создания папки: ${e.message}")
            }
        }
    }

    /**
     * Переименовать папку.
     */
    fun renameFolder(
        folderId: String,
        newName: String,
        currentUser: String,
        onResult: (String?) -> Unit
    ) {
        if (!isFolderModeAvailable()) {
            onResult("Папки доступны только в «Моих рецептах»")
            return
        }
        viewModelScope.launch {
            try {
                val ok = recipesRepository.renameFolder(folderId, newName, currentUser)
                if (!ok) {
                    onResult("Не удалось переименовать папку")
                } else {
                    Logger.log(TAG, "renameFolder: OK id=$folderId")
                    onResult(null)
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "renameFolder ERROR: ${e.message}", e)
                onResult("Ошибка переименования: ${e.message}")
            }
        }
    }

    /**
     * Удалить папку (только если пуста — проверка в репозитории).
     *
     * @param onResult колбэк: null — успех, иначе — сообщение об ошибке
     *                 (например, «Папка не пуста: сначала удалите/перенесите рецепты»).
     */
    fun deleteFolder(
        folderId: String,
        currentUser: String,
        onResult: (String?) -> Unit
    ) {
        if (!isFolderModeAvailable()) {
            onResult("Папки доступны только в «Моих рецептах»")
            return
        }
        viewModelScope.launch {
            try {
                val ok = recipesRepository.deleteFolder(folderId, currentUser)
                if (!ok) {
                    onResult("Папка не пуста: сначала удалите или перенесите рецепты и подпапки")
                } else {
                    Logger.log(TAG, "deleteFolder: OK id=$folderId")
                    onResult(null)
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "deleteFolder ERROR: ${e.message}", e)
                onResult("Ошибка удаления папки: ${e.message}")
            }
        }
    }

    /**
     * 🆕 Переместить рецепт в папку (или в корень, если folderId = null).
     * Используется в RecipeEditActivity (Шаг 1.7-4) через выпадающий список.
     */
    fun moveRecipeToFolder(
        recipeId: String,
        folderId: String?,
        currentUser: String,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val ok = recipesRepository.moveRecipeToFolder(recipeId, folderId, currentUser)
                if (!ok) {
                    onResult("Не удалось переместить рецепт")
                } else {
                    Logger.log(TAG, "moveRecipeToFolder: OK id=$recipeId → $folderId")
                    onResult(null)
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "moveRecipeToFolder ERROR: ${e.message}", e)
                onResult("Ошибка перемещения: ${e.message}")
            }
        }
    }

    // ============================================================
    // УДАЛЕНИЕ РЕЦЕПТА (пригодится в дальнейшем, если понадобится из списка)
    // ============================================================

    fun deleteRecipe(recipeId: String, currentUser: String) {
        viewModelScope.launch {
            try {
                val ok = recipesRepository.deleteRecipe(recipeId, currentUser)
                Logger.log(TAG, "deleteRecipe: id=$recipeId, ok=$ok")
                if (ok) loadContent()
            } catch (e: Exception) {
                Logger.log(TAG, "deleteRecipe ERROR: ${e.message}", e)
            }
        }
    }
}
