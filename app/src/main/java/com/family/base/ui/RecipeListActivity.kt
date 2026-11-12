package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.databinding.ActivityRecipeListBinding
import com.family.base.ui.adapter.RecipeAdapter
import com.family.base.ui.viewmodel.RecipeViewModel
import com.family.base.util.Logger

/**
 * 🆕 v21.1.0 (Шаг 1.3 + 1.4a + 1.4b-1): экран «Рецепты».
 *
 * Что делает на этом шаге (1.4b-1):
 *  - Принимает EXTRA_SOURCE ("ai" | "user" | "all"), ставит заголовок;
 *  - Подключает RecipeViewModel + RecipeAdapter + RecyclerView;
 *  - Поиск: клик по btnSearch разворачивает searchLayout/etSearch,
 *    ввод с debounce 250 мс → ViewModel.setSearchQuery;
 *  - Сортировка: чипы 🕐 Новые / 🔤 А-Я → ViewModel.setSort;
 *  - FAB «+» (для "ai" / "user") открывает RecipeEditActivity;
 *  - Тап по emptyState (для "ai" / "user") — то же самое;
 *  - По возврату из RecipeEditActivity перезагружает список;
 *  - Клик по карточке — пока Toast («Открою рецепт: <title>»).
 *    Реальный просмотр — 1.4b-2 (RecipeDetailActivity).
 *
 * ВАЖНО (правило проекта):
 *  - Заголовок ставим через supportActionBar?.title = ..., а НЕ через
 *    binding.toolbar.title = ... Иначе ActionBar перетирает его на
 *    android:label из манифеста (= "BAZA").
 *  - Корневая «🍳 Рецепты» (source = "all") этот экран НЕ открывает —
 *    она открывается как обычная папка (MainActivity.navigateToFolder).
 */
class RecipeListActivity : AppCompatActivity() {

    companion object {
        const val TAG = "RecipeListActivity"

        const val EXTRA_SOURCE = "recipe_source"

        const val EXTRA_SOURCE_ALL = "all"

        private const val SEARCH_DEBOUNCE_MS = 250L
    }

    private lateinit var binding: ActivityRecipeListBinding

    private val viewModel: RecipeViewModel by viewModels()

    private var source: String = EXTRA_SOURCE_ALL

    private lateinit var adapter: RecipeAdapter

    private var searchVisible = false

    private var searchRunnable: Runnable? = null

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.log(TAG, "editLauncher result: ${result.resultCode}")
        viewModel.loadRecipes()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== RecipeListActivity onCreate START ===")

        try {
            binding = ActivityRecipeListBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        source = intent.getStringExtra(EXTRA_SOURCE) ?: EXTRA_SOURCE_ALL
        Logger.log(TAG, "onCreate: source=$source")

        viewModel.init(source)

        setupToolbar()
        setupRecycler()
        setupSearch()
        setupSortChips()
        setupEmptyState()
        setupFab()
        observeViewModel()

        viewModel.loadRecipes()
    }

    // ============================================================
    // ТУЛБАР
    // ============================================================

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            Logger.log(TAG, "toolbar up clicked")
            finish()
        }

        val title = when (source) {
            RecipeEntity.SOURCE_AI -> FolderTitle.AI
            RecipeEntity.SOURCE_USER -> FolderTitle.MY
            else -> FolderTitle.ALL
        }
        // ВАЖНО: через supportActionBar?.title, а не binding.toolbar.title.
        supportActionBar?.title = title.text

        binding.btnSearch.setOnClickListener {
            toggleSearch()
        }
    }

    // ============================================================
    // RECYCLER + АДАПТЕР
    // ============================================================

    private fun setupRecycler() {
        adapter = RecipeAdapter(
            onRecipeClick = { recipe ->
                // 1.4b-1: Toast. 1.4b-2: открыть RecipeDetailActivity.
                Toast.makeText(this, "Открою рецепт: ${recipe.title}", Toast.LENGTH_SHORT).show()
                Logger.log(TAG, "recipe clicked: id=${recipe.id}, title='${recipe.title}'")
            },
            onRecipeLongClick = { recipe ->
                Logger.log(TAG, "recipe longClicked: id=${recipe.id}")
                // Контекстное меню / удаление — 1.4b-2.
            }
        )

        binding.rvRecipes.layoutManager = LinearLayoutManager(this)
        binding.rvRecipes.adapter = adapter
    }

    // ============================================================
    // ПОИСК
    // ============================================================

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearchNow(binding.etSearch.text?.toString() ?: "")
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    private fun scheduleSearch(query: String) {
        searchRunnable?.let { binding.root.removeCallbacks(it) }
        val r = Runnable { viewModel.setSearchQuery(query) }
        searchRunnable = r
        binding.root.postDelayed(r, SEARCH_DEBOUNCE_MS)
    }

    private fun performSearchNow(query: String) {
        searchRunnable?.let { binding.root.removeCallbacks(it) }
        viewModel.setSearchQuery(query)
    }

    private fun toggleSearch() {
        if (searchVisible) {
            // Свернуть поиск и очистить.
            searchVisible = false
            binding.searchLayout.visibility = View.GONE
            binding.etSearch.setText("")
            viewModel.clearSearch()
            hideKeyboard()
        } else {
            searchVisible = true
            binding.searchLayout.visibility = View.VISIBLE
            binding.etSearch.requestFocus()
            showKeyboard()
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        } catch (e: Exception) {
            Logger.log(TAG, "hideKeyboard error: ${e.message}", e)
        }
    }

    private fun showKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Logger.log(TAG, "showKeyboard error: ${e.message}", e)
        }
    }

    // ============================================================
    // СОРТИРОВКА
    // ============================================================

    private fun setupSortChips() {
        binding.chipSortNewest.setOnClickListener {
            if (viewModel.isSortNewest()) return@setOnClickListener
            viewModel.setSort(true)
        }
        binding.chipSortAlphabetical.setOnClickListener {
            if (!viewModel.isSortNewest()) return@setOnClickListener
            viewModel.setSort(false)
        }
    }

    // ============================================================
    // ПУСТОЕ СОСТОЯНИЕ
    // ============================================================

    private fun setupEmptyState() {
        val (icon, title, subtitle) = when (source) {
            RecipeEntity.SOURCE_AI -> Triple(
                "🤖",
                "Рецептов ИИ пока нет",
                "Скажи, что хочешь приготовить, — и ИИ предложит рецепт.\n(Появится на Шаге 2)"
            )
            RecipeEntity.SOURCE_USER -> Triple(
                "📝",
                "Здесь пока пусто",
                "Нажми «+», чтобы добавить первый рецепт:\nназвание, описание, а скоро — фото и видео."
            )
            else -> Triple(
                "🍳",
                "Рецептов пока нет",
                "Загляни в «🤖 Рецепты ИИ» или «📝 Мои рецепты»."
            )
        }

        binding.tvEmptyIcon.text = icon
        binding.tvEmptyTitle.text = title
        binding.tvEmptySubtitle.text = subtitle

        binding.emptyState.setOnClickListener {
            if (source == RecipeEntity.SOURCE_AI || source == RecipeEntity.SOURCE_USER) {
                openCreateRecipe()
            }
        }
    }

    // ============================================================
    // FAB
    // ============================================================

    private fun setupFab() {
        if (source == RecipeEntity.SOURCE_AI || source == RecipeEntity.SOURCE_USER) {
            binding.fabAddRecipe.visibility = View.VISIBLE
            binding.fabAddRecipe.setOnClickListener {
                openCreateRecipe()
            }
        } else {
            binding.fabAddRecipe.visibility = View.GONE
            binding.fabAddRecipe.setOnClickListener(null)
        }
    }

    private fun openCreateRecipe() {
        Logger.log(TAG, "openCreateRecipe: source=$source")
        val intent = Intent(this, RecipeEditActivity::class.java).apply {
            putExtra(RecipeEditActivity.EXTRA_SOURCE, source)
        }
        editLauncher.launch(intent)
    }

    // ============================================================
    // НАБЛЮДЕНИЕ ЗА VIEWMODEL
    // ============================================================

    private fun observeViewModel() {
        viewModel.recipes.observe(this) { list ->
            Logger.log(TAG, "recipes observer: count=${list.size}")
            adapter.submitList(list)
            updateUiState(isEmpty = list.isEmpty())
        }

        viewModel.isEmpty.observe(this) { empty ->
            updateUiState(isEmpty = empty)
        }

        viewModel.isLoading.observe(this) { loading ->
            Logger.log(TAG, "isLoading: $loading")
            // На 1.4b-1 прогресс-бар не завезли — просто не мешаем.
            // Когда loading == true и список пуст — emptyState не показываем,
            // чтобы не мигало. Пустое состояние вернётся, когда isEmpty == true
            // и loading == false (см. updateUiState).
            updateUiState(isEmpty = viewModel.isEmpty.value ?: true)
        }
    }

    private fun updateUiState(isEmpty: Boolean) {
        val loading = viewModel.isLoading.value ?: false
        val hasItems = !isEmpty

        // RecyclerView показываем, если есть элементы.
        binding.rvRecipes.visibility = if (hasItems) View.VISIBLE else View.GONE

        // EmptyState показываем, только если пусто И не идёт загрузка.
        binding.emptyState.visibility =
            if (isEmpty && !loading) View.VISIBLE else View.GONE

        // Чипы сортировки — только когда есть элементы.
        binding.sortChipsScroll.visibility =
            if (hasItems) View.VISIBLE else View.GONE

        // Поиск имеет смысл, даже если пока пусто, но оставим btnSearch всегда.
    }

    private enum class FolderTitle(val text: String) {
        AI("🤖 Рецепты ИИ"),
        MY("📝 Мои рецепты"),
        ALL("🍳 Рецепты")
    }
}
