package com.family.base.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.dao.CountryCountRow
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
 * 🆕 v23.4.9: Типы сортировки рецептов.
 */
enum class RecipeSortType(val key: String, val displayName: String) {
    NEWEST("newest", "По новизне"),
    ALPHABETICAL("alphabetical", "По алфавиту"),
    POPULARITY("popularity", "По популярности"),
    LIKES("likes", "По лайкам"),
    DISLIKES("dislikes", "По дизлайкам");

    companion object {
        fun fromKey(key: String): RecipeSortType =
            values().firstOrNull { it.key == key } ?: NEWEST
    }
}

class RecipeViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "RecipeViewModel"

    private val db by lazy { AppDatabase.getInstance(application) }
    private val recipesRepository by lazy { RecipesRepository(db, application) }
    private val tokenStorage by lazy { TokenStorage(application) }

    private var source: String = RecipeEntity.SOURCE_USER
    private var searchQuery: String = ""

    // 🆕 v23.4.9: расширенная сортировка (5 типов вместо 2)
    private var sortType: RecipeSortType = RecipeSortType.NEWEST

    // 🆕 v23.4.9 (27.11.2026): флаг — пользователь уже явно выбрал сортировку.
    // Пока false, кнопка btnSort показывает "🔽 Сортировка",
    // после явного выбора в PopupMenu — displayName выбранного типа.
    // (имя isSortExplicitlySelected — чтобы не конфликтовать с публичным LiveData userSelectedSort)
    private var isSortExplicitlySelected: Boolean = false

    // 🆕 v23.4.9: фильтр по стране кухни (null = не выбран, показываем все)
    private var currentCountryFilter: String? = null

    private var currentFolderId: String? = null
    private var loadJob: Job? = null

    private val _folders = MutableLiveData<List<RecipeFolderEntity>>(emptyList())
    val folders: LiveData<List<RecipeFolderEntity>> = _folders

    private val _folderRecipeCounts = MutableLiveData<Map<String, Int>>(emptyMap())
    val folderRecipeCounts: LiveData<Map<String, Int>> = _folderRecipeCounts

    private val _recipes = MutableLiveData<List<RecipeAdapter.RecipeListItem>>(emptyList())
    val recipes: LiveData<List<RecipeAdapter.RecipeListItem>> = _recipes

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isEmpty = MutableLiveData(true)
    val isEmpty: LiveData<Boolean> = _isEmpty

    private val _currentFolderId = MutableLiveData<String?>(null)
    val currentFolderIdLive: LiveData<String?> = _currentFolderId

    private val _currentFolderName = MutableLiveData<String?>(null)
    val currentFolderName: LiveData<String?> = _currentFolderName

    // 🆕 v23.4.9: список стран с количеством рецептов (для BottomSheet)
    private val _countries = MutableLiveData<List<CountryCountRow>>(emptyList())
    val countries: LiveData<List<CountryCountRow>> = _countries

    // 🆕 v23.4.9: текущий тип сортировки (для UI — подсветка активного пункта)
    private val _currentSortType = MutableLiveData(RecipeSortType.NEWEST)
    val currentSortType: LiveData<RecipeSortType> = _currentSortType

    // 🆕 v23.4.9 (27.11.2026): публичный LiveData для UI (кнопка сортировки)
    private val _userSelectedSort = MutableLiveData(false)
    val userSelectedSort: LiveData<Boolean> = _userSelectedSort

    // 🆕 v23.4.9: выбранная страна (для UI — плашка «🇮🇹 Итальянская ✕»)
    private val _selectedCountry = MutableLiveData<String?>(null)
    val selectedCountry: LiveData<String?> = _selectedCountry

    // ============================================================
    // 🆕 AI-ГЕНЕРАЦИЯ РЕЦЕПТОВ (Шаг 2)
    // ============================================================
    private val _isAiGenerating = MutableLiveData(false)
    val isAiGenerating: LiveData<Boolean> = _isAiGenerating

    private val _aiError = MutableLiveData<String?>(null)
    val aiError: LiveData<String?> = _aiError

    private val _generatedAiRecipe = MutableLiveData<String?>(null)
    val generatedAiRecipe: LiveData<String?> = _generatedAiRecipe

    private val folderStack = ArrayDeque<String>()

    val currentUserName: String
        get() = tokenStorage.getCurrentUser()
            ?: tokenStorage.getUserDisplayName()
            ?: "Пользователь"

    fun init(source: String) {
        if (this.source == source) return
        this.source = source
        this.currentFolderId = null
        this.searchQuery = ""
        this.sortType = RecipeSortType.NEWEST
        this.isSortExplicitlySelected = false
        this._userSelectedSort.value = false
        this.currentCountryFilter = null
        this.folderStack.clear()
        Logger.log(TAG, "init: source=$source")
    }

    fun getSource(): String = source

    fun isFolderModeAvailable(): Boolean = source == RecipeEntity.SOURCE_USER

    fun loadContent() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val isAi = (source == RecipeEntity.SOURCE_AI)
                val folderMode = isFolderModeAvailable()
                val folderId = currentFolderId
                val hasQuery = searchQuery.isNotBlank()
                val country = currentCountryFilter
                val sort = sortType

                // 1. Папки уровня (только если нет поиска и нет фильтра по стране)
                // 🆕 v23.4.9: папки ВСЕГДА по алфавиту (false), чтобы "01, 02, 03" не скакали
                // при переключении сортировки рецептов.
                val foldersList: List<RecipeFolderEntity> =
                    if (folderMode && !hasQuery && country == null) {
                        recipesRepository.getFolders(folderId, false)
                    } else {
                        emptyList()
                    }

                // 2. Счётчики рецептов в папках (глубокий подсчёт)
                val counts: Map<String, Int> = if (foldersList.isNotEmpty()) {
                    try {
                        val ids = foldersList.map { it.id }
                        val rows = db.recipeDao().getRecipeFolderStatsDeep(ids)
                        rows.associate { it.folderId to it.itemCount }
                    } catch (e: Exception) {
                        Logger.log(TAG, "loadContent: folder counts error: ${e.message}", e)
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }

                // 3. Рецепты уровня — 🆕 v23.4.9: учитываем sortType и currentCountryFilter
                val recipesList: List<RecipeEntity> = when {
                    isAi -> {
                        when {
                            hasQuery -> recipesRepository.searchRecipes(source, searchQuery)
                            country != null -> recipesRepository.getRecipesByCountry(source, country, sort.key)
                            else -> recipesRepository.getRecipesBySourceSorted(source, sort.key)
                        }
                    }
                    folderId != null -> {
                        when {
                            hasQuery && country != null ->
                                recipesRepository.searchInFolderByCountry(folderId, country, searchQuery)
                            hasQuery ->
                                recipesRepository.searchInFolder(folderId, searchQuery)
                            country != null ->
                                recipesRepository.getRecipesInFolderByCountry(folderId, country, sort.key)
                            else ->
                                recipesRepository.getRecipesInFolderSorted(folderId, sort.key)
                        }
                    }
                    else -> {
                        // Корень «Моих рецептов»
                        when {
                            hasQuery && country != null ->
                                recipesRepository.searchRootRecipesByCountry(source, country, searchQuery)
                            hasQuery ->
                                db.recipeDao().searchAllUserRecipes(searchQuery)
                            country != null ->
                                recipesRepository.getRootRecipesByCountry(source, country, sort.key)
                            else ->
                                recipesRepository.getRootRecipesSorted(source, sort.key)
                        }
                    }
                }

                // 🆕 v23.4.9: вторичная сортировка по алфавиту только если
                // основной sortType != ALPHABETICAL и есть поиск.
                val sortedRecipes: List<RecipeEntity> =
                    if (hasQuery && sort != RecipeSortType.ALPHABETICAL) {
                        recipesList.sortedBy { it.title.lowercase() }
                    } else {
                        recipesList
                    }

                // 4. Подстановка локального файла первого фото
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

                // 5. Имя текущей папки
                val currentName: String? = if (folderId != null) {
                    try {
                        db.recipeFolderDao().getLiveFolderById(folderId)?.name ?: folderId
                    } catch (e: Exception) {
                        Logger.log(TAG, "loadContent: error getting folder name for $folderId: ${e.message}", e)
                        folderId
                    }
                } else {
                    null
                }

                Logger.log(
                    TAG,
                    "loadContent: source=$source, folderId=$folderId, query='$searchQuery', " +
                        "sortType=${sort.key}, country=$country, folders=${foldersList.size}, " +
                        "recipes=${listItems.size}, withPhoto=${listItems.count { it.photoFile != null }}, " +
                        "counts=${counts.size}"
                )

                _folders.value = foldersList
                _folderRecipeCounts.value = counts
                _recipes.value = listItems
                _isEmpty.value = foldersList.isEmpty() && listItems.isEmpty()
                _currentFolderId.value = folderId
                _currentFolderName.value = currentName
            } catch (e: Exception) {
                Logger.log(TAG, "loadContent ERROR: ${e.message}", e)
                _folders.value = emptyList()
                _folderRecipeCounts.value = emptyMap()
                _recipes.value = emptyList()
                _isEmpty.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadRecipes() {
        loadContent()
    }

    fun openFolder(folderId: String) {
        if (!isFolderModeAvailable()) {
            Logger.log(TAG, "openFolder: folder mode unavailable for source=$source")
            return
        }
        if (currentFolderId == folderId) return
        currentFolderId?.let { folderStack.addLast(it) }
        currentFolderId = folderId
        searchQuery = ""
        // 🆕 v23.4.9: при входе в папку сбрасываем фильтр по стране
        currentCountryFilter = null
        _selectedCountry.value = null
        Logger.log(TAG, "openFolder: $folderId (stack size=${folderStack.size})")
        loadContent()
    }

    fun goToRoot() {
        if (currentFolderId == null) return
        currentFolderId = null
        folderStack.clear()
        searchQuery = ""
        currentCountryFilter = null
        _selectedCountry.value = null
        Logger.log(TAG, "goToRoot")
        loadContent()
    }

    fun goBack(): Boolean {
        return if (currentFolderId != null) {
            if (folderStack.isNotEmpty()) {
                currentFolderId = folderStack.removeLast()
                Logger.log(TAG, "goBack: restored to parent $currentFolderId (stack size=${folderStack.size})")
            } else {
                currentFolderId = null
                Logger.log(TAG, "goBack: to root")
            }
            searchQuery = ""
            currentCountryFilter = null
            _selectedCountry.value = null
            loadContent()
            true
        } else {
            false
        }
    }

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
    // 🆕 v23.4.9: СОРТИРОВКА (5 типов)
    // ============================================================

    fun setSortType(type: RecipeSortType) {
        if (sortType == type) return
        sortType = type
        isSortExplicitlySelected = true
        _userSelectedSort.value = true
        _currentSortType.value = type
        Logger.log(TAG, "setSortType: ${type.key} (${type.displayName})")
        loadContent()
    }

    fun getSortType(): RecipeSortType = sortType

    // 🆕 v23.4.9 (27.11.2026): геттер флага явного выбора сортировки
    fun isUserSelectedSort(): Boolean = isSortExplicitlySelected

    @Deprecated(
        "Use setSortType(RecipeSortType) instead",
        ReplaceWith("setSortType(if (newest) RecipeSortType.NEWEST else RecipeSortType.ALPHABETICAL)")
    )
    fun setSort(newest: Boolean) {
        setSortType(if (newest) RecipeSortType.NEWEST else RecipeSortType.ALPHABETICAL)
    }

    @Deprecated(
        "Use getSortType() instead",
        ReplaceWith("getSortType() == RecipeSortType.NEWEST")
    )
    fun isSortNewest(): Boolean = sortType == RecipeSortType.NEWEST

    // ============================================================
    // 🆕 v23.4.9: ФИЛЬТР ПО СТРАНЕ КУХНИ
    // ============================================================

    fun loadCountries() {
        viewModelScope.launch {
            try {
                val folderId = currentFolderId
                val rows: List<CountryCountRow> = when {
                    source == RecipeEntity.SOURCE_AI ->
                        recipesRepository.getDistinctCountriesWithCount(source)
                    folderId != null ->
                        recipesRepository.getDistinctCountriesWithCountInFolder(folderId)
                    else ->
                        recipesRepository.getDistinctCountriesWithCountInRoot(source)
                }
                Logger.log(TAG, "loadCountries: count=${rows.size}")
                _countries.value = rows
            } catch (e: Exception) {
                Logger.log(TAG, "loadCountries ERROR: ${e.message}", e)
                _countries.value = emptyList()
            }
        }
    }

    fun selectCountry(country: String) {
        if (currentCountryFilter == country) return
        currentCountryFilter = country
        _selectedCountry.value = country
        Logger.log(TAG, "selectCountry: '$country'")
        loadContent()
    }

    fun clearCountryFilter() {
        if (currentCountryFilter == null) return
        currentCountryFilter = null
        _selectedCountry.value = null
        Logger.log(TAG, "clearCountryFilter")
        loadContent()
    }

    fun getSelectedCountry(): String? = currentCountryFilter

    // ============================================================
    // 🆕 v23.4.9: ЛАЙК / ДИЗЛАЙК / ПРИГОТОВЛЕНО (инкремент)
    // ============================================================

    fun likeRecipe(recipeId: String) {
        viewModelScope.launch {
            try {
                val user = currentUserName
                val updated = recipesRepository.incrementLikes(recipeId, user)
                if (updated != null) {
                    Logger.log(TAG, "likeRecipe: OK id=$recipeId, likes=${updated.likes}")
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "likeRecipe ERROR: ${e.message}", e)
            }
        }
    }

    fun dislikeRecipe(recipeId: String) {
        viewModelScope.launch {
            try {
                val user = currentUserName
                val updated = recipesRepository.incrementDislikes(recipeId, user)
                if (updated != null) {
                    Logger.log(TAG, "dislikeRecipe: OK id=$recipeId, dislikes=${updated.dislikes}")
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "dislikeRecipe ERROR: ${e.message}", e)
            }
        }
    }

    fun markCooked(recipeId: String) {
        viewModelScope.launch {
            try {
                val user = currentUserName
                val updated = recipesRepository.incrementCookedCount(recipeId, user)
                if (updated != null) {
                    Logger.log(TAG, "markCooked: OK id=$recipeId, cookedCount=${updated.cookedCount}")
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "markCooked ERROR: ${e.message}", e)
            }
        }
    }

    // ============================================================
    // 🆕 v23.4.9 (27.11.2026): ДЕКРЕМЕНТ СЧЁТЧИКОВ (отмена долгого нажатия)
    //
    // Вызывается при долгом нажатии на кнопку 👍/👎/🍳.
    // Защита от ухода в минус реализована в SQL (MAX(0, value - 1))
    // на уровне DAO, поэтому здесь просто вызываем репозиторий.
    // ============================================================

    /**
     * 🆕 -1 к лайкам (отмена долгого нажатия).
     */
    fun decrementLikes(recipeId: String) {
        viewModelScope.launch {
            try {
                val user = currentUserName
                val updated = recipesRepository.decrementLikes(recipeId, user)
                if (updated != null) {
                    Logger.log(TAG, "decrementLikes: OK id=$recipeId, likes=${updated.likes}")
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "decrementLikes ERROR: ${e.message}", e)
            }
        }
    }

    /**
     * 🆕 -1 к дизлайкам (отмена долгого нажатия).
     */
    fun decrementDislikes(recipeId: String) {
        viewModelScope.launch {
            try {
                val user = currentUserName
                val updated = recipesRepository.decrementDislikes(recipeId, user)
                if (updated != null) {
                    Logger.log(TAG, "decrementDislikes: OK id=$recipeId, dislikes=${updated.dislikes}")
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "decrementDislikes ERROR: ${e.message}", e)
            }
        }
    }

    /**
     * 🆕 -1 к счётчику приготовлений (отмена долгого нажатия).
     */
    fun decrementCookedCount(recipeId: String) {
        viewModelScope.launch {
            try {
                val user = currentUserName
                val updated = recipesRepository.decrementCookedCount(recipeId, user)
                if (updated != null) {
                    Logger.log(TAG, "decrementCookedCount: OK id=$recipeId, cookedCount=${updated.cookedCount}")
                    loadContent()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "decrementCookedCount ERROR: ${e.message}", e)
            }
        }
    }

    fun updateCuisineCountry(recipeId: String, country: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val user = currentUserName
                val ok = recipesRepository.updateCuisineCountry(recipeId, country, user)
                if (!ok) {
                    onResult("Не удалось обновить страну кухни")
                } else {
                    Logger.log(TAG, "updateCuisineCountry: OK id=$recipeId, country='$country'")
                    onResult(null)
                    loadContent()
                    // Если фильтр по стране активен — обновим список стран в BottomSheet
                    if (currentCountryFilter != null) loadCountries()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "updateCuisineCountry ERROR: ${e.message}", e)
                onResult("Ошибка: ${e.message}")
            }
        }
    }

    // ============================================================
    // ПАПКИ (CRUD)
    // ============================================================
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

    // ============================================================
    // 🆕 AI-ГЕНЕРАЦИЯ РЕЦЕПТОВ (Шаг 2)
    // ============================================================
    fun generateAiRecipe(prompt: String, onResult: (String?) -> Unit) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) {
            onResult("Введите запрос для генерации рецепта")
            return
        }
        _aiError.value = null
        _generatedAiRecipe.value = null
        _isAiGenerating.value = true
        viewModelScope.launch {
            try {
                Logger.log(TAG, "generateAiRecipe: start, prompt='$cleanPrompt'")
                val recipeText = recipesRepository.generateAiRecipe(cleanPrompt)
                if (recipeText.isNullOrBlank()) {
                    val errorMsg = "Не удалось сгенерировать рецепт. Проверьте интернет и попробуйте снова."
                    Logger.log(TAG, "generateAiRecipe: empty response")
                    _aiError.value = errorMsg
                    onResult(errorMsg)
                } else {
                    Logger.log(TAG, "generateAiRecipe: OK, length=${recipeText.length}")
                    _generatedAiRecipe.value = recipeText
                    onResult(null)
                }
            } catch (e: Exception) {
                val errorMsg = "Ошибка генерации: ${e.message ?: "неизвестная ошибка"}"
                Logger.log(TAG, "generateAiRecipe ERROR: ${e.message}", e)
                _aiError.value = errorMsg
                onResult(errorMsg)
            } finally {
                _isAiGenerating.value = false
            }
        }
    }

    fun clearAiState() {
        _generatedAiRecipe.value = null
        _aiError.value = null
    }
}
