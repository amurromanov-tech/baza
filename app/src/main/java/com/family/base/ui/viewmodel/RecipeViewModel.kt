package com.family.base.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.family.base.data.TokenStorage
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

class RecipeViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "RecipeViewModel"

    private val db by lazy { AppDatabase.getInstance(application) }
    private val recipesRepository by lazy { RecipesRepository(db, application) }
    private val tokenStorage by lazy { TokenStorage(application) }

    private var source: String = RecipeEntity.SOURCE_USER
    private var searchQuery: String = ""
    private var sortNewest: Boolean = false
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

                // 1. Папки уровня
                val foldersList: List<RecipeFolderEntity> = if (folderMode && !hasQuery) {
                    recipesRepository.getFolders(folderId, sortNewest)
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

                // 3. Рецепты уровня
                val recipesList: List<RecipeEntity> = when {
                    isAi -> {
                        if (hasQuery) recipesRepository.searchRecipes(source, searchQuery)
                        else recipesRepository.getRecipesBySource(source, sortNewest)
                    }
                    folderId != null -> {
                        if (hasQuery) recipesRepository.searchInFolder(folderId, searchQuery)
                        else recipesRepository.getRecipesInFolder(folderId, sortNewest)
                    }
                    else -> {
                        if (hasQuery) {
                            // 🔧 Глубокий поиск по всем рецептам source='user' (включая папки)
                            db.recipeDao().searchAllUserRecipes(searchQuery)
                        } else {
                            recipesRepository.getRootRecipes(source, sortNewest)
                        }
                    }
                }

                val sortedRecipes: List<RecipeEntity> =
                    if (hasQuery && !sortNewest) recipesList.sortedBy { it.title.lowercase() }
                    else recipesList

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
                        "sortNewest=$sortNewest, folders=${foldersList.size}, recipes=${listItems.size}, " +
                        "withPhoto=${listItems.count { it.photoFile != null }}, counts=${counts.size}"
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
        Logger.log(TAG, "openFolder: $folderId (stack size=${folderStack.size})")
        loadContent()
    }

    fun goToRoot() {
        if (currentFolderId == null) return
        currentFolderId = null
        folderStack.clear()
        searchQuery = ""
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

    fun setSort(newest: Boolean) {
        if (sortNewest == newest) return
        sortNewest = newest
        Logger.log(TAG, "setSort: newest=$newest")
        loadContent()
    }

    fun isSortNewest(): Boolean = sortNewest

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
}
