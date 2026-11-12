package com.family.base.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.repository.RecipesRepository
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
 * Стиль — как у остальных ViewModel в проекте (AndroidViewModel + LiveData).
 * Работа с БД — suspend-методы репозитория, поэтому всё внутри viewModelScope.
 *
 * Почему не Flow: в проекте везде LiveData + suspend, Room без Flow.
 * Не плодим второй стиль.
 *
 * Особенности:
 *  - loadRecipes() отменяет предыдущую загрузку (Job), чтобы при быстром
 *    переключении сортировки/поиска не было гонки и «мигания» старых данных.
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

    private val _recipes = MutableLiveData<List<RecipeEntity>>(emptyList())
    val recipes: LiveData<List<RecipeEntity>> = _recipes

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
                val result: List<RecipeEntity> = if (searchQuery.isBlank()) {
                    recipesRepository.getRecipesBySource(source, sortNewest)
                } else {
                    // Поиск возвращает по createdAt DESC (см. RecipeDao.searchBySource).
                    // Если выбрана сортировка «А-Я» — отсортируем в памяти.
                    val found = recipesRepository.searchRecipes(source, searchQuery)
                    if (sortNewest) found
                    else found.sortedBy { it.title.lowercase() }
                }

                Logger.log(
                    TAG,
                    "loadRecipes: source=$source, query='$searchQuery', sortNewest=$sortNewest, count=${result.size}"
                )

                _recipes.value = result
                _isEmpty.value = result.isEmpty()
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
