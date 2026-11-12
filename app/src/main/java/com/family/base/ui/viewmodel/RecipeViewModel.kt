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
import com.family.base.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 🆕 v21.1.0 (Шаг 1.4b-1): ViewModel экрана «Рецепты».
 *
 * Отвечает за:
 *  - загрузку списка рецептов по source ("ai" | "user");
 *  - поиск (query) — через RecipesRepository.searchRecipes;
 *  - сортировку (sortNewest: 🕐 Новые / 🔤 А-Я);
 *  - уведомление UI об изменениях (LiveData).
 *
 * Стиль — AndroidViewModel + LiveData (как у остальных ViewModel в проекте).
 * Работа с БД — suspend-методы репозитория, внутри viewModelScope.
 *
 * ⚠️ ФОТО РЕЦЕПТОВ В 1.4b-1 НЕ ГРУЗИМ.
 *  У RecipeEntity нет поля imageUrl — фото живут в recipe_media
 *  (RecipeMediaEntity), а методы для них (ImageUtils.getRecipeMediaFile,
 *  RecipeMediaController) появятся только на Шаге 1.5. Поэтому здесь
 *  photoFile ВСЕГДА null, а RecipeAdapter честно показывает
 *  ic_item_default. На Шаге 1.5 заполним это поле реальным File.
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

    /**
     * Задать source экрана ("ai" | "user"). Вызывается один раз из Activity
     * в onCreate(). После этого loadRecipes() грузит по этому source.
     */
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

                // Фото пока не подставляем (Шаг 1.5). photoFile = null.
                val listItems: List<RecipeAdapter.RecipeListItem> = recipes.map { recipe ->
                    RecipeAdapter.RecipeListItem(recipe = recipe, photoFile = null)
                }

                Logger.log(
                    TAG,
                    "loadRecipes: source=$source, query='$searchQuery', sortNewest=$sortNewest, count=${listItems.size}"
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

    /**
     * Установить поисковый запрос. Пустая строка = сброс поиска.
     * Перезагружает список.
     */
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

    /**
     * Переключить сортировку.
     * @param newest true = «🕐 Новые», false = «🔤 А-Я».
     */
    fun setSort(newest: Boolean) {
        if (sortNewest == newest) return
        sortNewest = newest
        Logger.log(TAG, "setSort: newest=$newest")
        loadRecipes()
    }

    fun isSortNewest(): Boolean = sortNewest

    // ============================================================
    // УДАЛЕНИЕ (пока не используется в 1.4b-1, пригодится в 1.4b-2)
    // ============================================================

    /**
     * Удалить рецепт (tombstone). Права проверяет вызывающий (UI),
     * здесь только техническая операция.
     */
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
