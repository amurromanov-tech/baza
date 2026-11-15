package com.family.base.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
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
 *  - поиск (query) — через RecipesRepository.searchRecipes;
 *  - сортировку (sortNewest: 🕐 Новые / 🔤 А-Я);
 *  - 🆕 подстановку локального файла первого фото каждому рецепту
 *    (RecipeAdapter.RecipeListItem.photoFile) — чтобы адаптер не ходил в БД;
 *  - уведомление UI об изменениях (LiveData).
 *
 * Стиль — AndroidViewModel + LiveData (как у остальных ViewModel в проекте).
 * Работа с БД — suspend-методы репозитория, внутри viewModelScope.
 *
 * 🆕 Шаг 1.5-4: фото рецептов теперь реально подставляются.
 *  Для каждого рецепта берём ПЕРВОЕ фото (getPhotos(recipeId).firstOrNull())
 *  и вычисляем локальный File через ImageUtils.getRecipeMediaFile(...).
 *  Если фото нет или файл отсутствует — photoFile = null, адаптер покажет
 *  ic_item_default. Всё локально, без интернета.
 *
 * Особенности:
 *  - loadRecipes() отменяет предыдущую загрузку (Job) — нет гонки
 *    при быстром переключении сортировки/поиска.
 *  - searchQuery и sortNewest хранятся в полях, чтобы повторный вызов
 *    loadRecipes() (например, после возврата из RecipeEditActivity)
 *    подхватил актуальное состояние.
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

    private var loadJob: Job? = null

    // ============================================================
    // LIVEDATA ДЛЯ UI
    // ============================================================

    private val _recipes = MutableLiveData<List<RecipeAdapter.RecipeListItem>>(emptyList())
    val recipes: LiveData<List<RecipeAdapter.RecipeListItem>> = _recipes

    /** true — идёт загрузка (для прогресса / скрытия emptyState). */
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /** true — список пуст (для показа emptyState). */
    private val _isEmpty = MutableLiveData(true)
    val isEmpty: LiveData<Boolean> = _isEmpty

    // ============================================================
    // ИНИЦИАЛИЗАЦИЯ
    // ============================================================

    fun init(source: String) {
        if (this.source == source) return
        this.source = source
        Logger.log(TAG, "init: source=$source")
    }

    fun getSource(): String = source

    // ============================================================
    // ЗАГРУЗКА
    // ============================================================

    /**
     * Перезагрузить список с учётом текущих query и sortNewest.
     * Отменяет предыдущую загрузку, если она ещё идёт.
     */
    fun loadRecipes() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val recipes: List<RecipeEntity> = if (searchQuery.isBlank()) {
                    recipesRepository.getRecipesBySource(source, sortNewest)
                } else {
                    // Поиск возвращает по createdAt DESC (см. RecipeDao.searchBySource).
                    // Если выбрана сортировка «А-Я» — отсортируем в памяти.
                    val found = recipesRepository.searchRecipes(source, searchQuery)
                    if (sortNewest) found
                    else found.sortedBy { it.title.lowercase() }
                }

                // 🆕 1.5-4: для каждого рецепта — локальный файл первого фото.
                val appContext = getApplication<Application>()
                val listItems: List<RecipeAdapter.RecipeListItem> = recipes.map { recipe ->
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
                            "loadRecipes: photoFile error for ${recipe.id}: ${e.message}",
                            e
                        )
                        null
                    }

                    RecipeAdapter.RecipeListItem(recipe = recipe, photoFile = photoFile)
                }

                Logger.log(
                    TAG,
                    "loadRecipes: source=$source, query='$searchQuery', sortNewest=$sortNewest, " +
                        "count=${listItems.size}, withPhoto=${listItems.count { it.photoFile != null }}"
                )

                _recipes.value = listItems
                _isEmpty.value = listItems.isEmpty()
            } catch (e: Exception) {
                Logger.log(TAG, "loadRecipes ERROR: ${e.message}", e)
                _recipes.value = emptyList()
                _isEmpty.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // ПОИСК
    // ============================================================

    fun setSearchQuery(query: String) {
        val normalized = query.trim()
        if (searchQuery == normalized) return
        searchQuery = normalized
        Logger.log(TAG, "setSearchQuery: '$searchQuery'")
        loadRecipes()
    }

    fun getSearchQuery(): String = searchQuery

    fun clearSearch() {
        if (searchQuery.isEmpty()) return
        searchQuery = ""
        Logger.log(TAG, "clearSearch")
        loadRecipes()
    }

    // ============================================================
    // СОРТИРОВКА
    // ============================================================

    fun setSort(newest: Boolean) {
        if (sortNewest == newest) return
        sortNewest = newest
        Logger.log(TAG, "setSort: newest=$newest")
        loadRecipes()
    }

    fun isSortNewest(): Boolean = sortNewest

    // ============================================================
    // УДАЛЕНИЕ (пригодится в дальнейшем, если понадобится из списка)
    // ============================================================

    fun deleteRecipe(recipeId: String, currentUser: String) {
        viewModelScope.launch {
            try {
                val ok = recipesRepository.deleteRecipe(recipeId, currentUser)
                Logger.log(TAG, "deleteRecipe: id=$recipeId, ok=$ok")
                if (ok) loadRecipes()
            } catch (e: Exception) {
                Logger.log(TAG, "deleteRecipe ERROR: ${e.message}", e)
            }
        }
    }
}
