package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeFolderEntity
import com.family.base.databinding.ActivityRecipeListBinding
import com.family.base.ui.adapter.RecipeCatalogAdapter
import com.family.base.ui.viewmodel.RecipeViewModel
import com.family.base.util.Logger

/**
 * 🆕 v21.1.0 (Шаг 1.3 + 1.4a + 1.4b-1 + 1.4b-2): экран «Рецепты».
 *
 * 🆕 Шаг 1.7-3: ДВУХУРОВНЕВЫЙ СПИСОК (папки + рецепты).
 *
 * Что делает:
 *  - Принимает EXTRA_SOURCE ("ai" | "user" | "all"), ставит заголовок;
 *  - Подключает RecipeViewModel + RecipeCatalogAdapter + RecyclerView;
 *  - В корне «Моих рецептов»: сверху папки, снизу рецепты без папки;
 *  - Внутри папки: её подпапки (если есть) + её рецепты;
 *  - В «Рецептах ИИ»: только рецепты (папок нет);
 *  - Поиск (debounce 250 мс) — ищет рецепты на текущем уровне;
 *  - Сортировка 🕐 Новые / 🔤 А-Я — папки и рецепты;
 *  - FAB «+» — создать рецепт (в текущей папке, если открыта);
 *  - FAB «📁» — создать папку (только source = "user");
 *  - Клик по папке → ViewModel.openFolder(id) (внутрь);
 *  - Клик по рецепту → RecipeDetailActivity;
 *  - Долгий клик по папке → «Переименовать» / «Удалить»;
 *  - Кнопка «назад» (системная или в тулбаре): если внутри папки —
 *    в корень, иначе — finish().
 *
 * ВАЖНО (правило проекта):
 *  - Заголовок ставим через supportActionBar?.title = ..., а НЕ через
 *    binding.toolbar.title = ... Иначе ActionBar перетирает его на
 *    android:label из манифеста (= "BAZA").
 *  - Корневая «🍳 Рецепты» (source = "all") этот экран НЕ открывает —
 *    она открывается как обычная папка (MainActivity.navigateToFolder).
 *
 * 🆕 Шаг 1.7-3 (финал): счётчики рецептов в папках берутся из
 *  RecipeViewModel.folderRecipeCounts (LiveData<Map<String,Int>>),
 *  а не считаются через GlobalScope. Текущий пользователь — из
 *  viewModel.currentUserName (TokenStorage внутри ViewModel).
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

    private lateinit var adapter: RecipeCatalogAdapter

    private var searchVisible = false

    private var searchRunnable: Runnable? = null

    /**
     * 🆕 Шаг 1.7-3: счётчики рецептов в папках (folderId → count).
     * Приходят из RecipeViewModel.folderRecipeCounts.
     */
    private var folderRecipeCounts: Map<String, Int> = emptyMap()

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.log(TAG, "editLauncher result: ${result.resultCode}")
        viewModel.loadContent()
    }

    /**
     * Возврат из RecipeDetailActivity.
     * Если рецепт менялся/удалён (RESULT_OK + EXTRA_RESULT_CHANGED=true) —
     * перезагружаем список. Иначе ничего не делаем.
     */
    private val detailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val changed = result.data?.getBooleanExtra(
            RecipeDetailActivity.EXTRA_RESULT_CHANGED,
            false
        ) ?: false
        Logger.log(TAG, "detailLauncher result: code=${result.resultCode}, changed=$changed")
        if (result.resultCode == RESULT_OK && changed) {
            viewModel.loadContent()
        }
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
        setupFabs()
        setupBackHandler()
        observeViewModel()

        viewModel.loadContent()
    }

    // ============================================================
    // ТУЛБАР + ЗАГОЛОВОК
    // ============================================================

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            Logger.log(TAG, "toolbar up clicked")
            handleBack()
        }

        updateTitle(null)
    }

    /**
     * Обновить заголовок ActionBar.
     * null → базовый заголовок по source (🤖 / 📝 / 🍳).
     * не null → имя открытой папки.
     */
    private fun updateTitle(folderName: String?) {
        val title = when {
            folderName != null -> folderName
            source == RecipeEntity.SOURCE_AI -> "🤖 Рецепты ИИ"
            source == RecipeEntity.SOURCE_USER -> "📝 Мои рецепты"
            else -> "🍳 Рецепты"
        }
        supportActionBar?.title = title
    }

    // ============================================================
    // RECYCLER + АДАПТЕР
    // ============================================================

    private fun setupRecycler() {
        adapter = RecipeCatalogAdapter(
            onFolderClick = { folder ->
                Logger.log(TAG, "folder clicked: id=${folder.id}, name='${folder.name}'")
                viewModel.openFolder(folder.id)
            },
            onRecipeClick = { recipe ->
                Logger.log(TAG, "recipe clicked: id=${recipe.id}, title='${recipe.title}'")
                openRecipeDetail(recipe.id)
            },
            onFolderLongClick = { folder ->
                Logger.log(TAG, "folder longClicked: id=${folder.id}, name='${folder.name}'")
                showFolderActionsDialog(folder)
            },
            onRecipeLongClick = { recipe ->
                Logger.log(TAG, "recipe longClicked: id=${recipe.id}")
                // Контекстное меню / быстрое удаление — можно добавить позже.
            }
        )

        binding.rvRecipes.layoutManager = LinearLayoutManager(this)
        binding.rvRecipes.adapter = adapter
    }

    private fun openRecipeDetail(recipeId: String) {
        val intent = Intent(this, RecipeDetailActivity::class.java).apply {
            putExtra(RecipeDetailActivity.EXTRA_RECIPE_ID, recipeId)
        }
        detailLauncher.launch(intent)
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

        binding.btnSearch.setOnClickListener {
            toggleSearch()
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
        binding.emptyState.setOnClickListener {
            // Тап по пустому состоянию — создать рецепт (только для ai/user).
            if (source == RecipeEntity.SOURCE_AI || source == RecipeEntity.SOURCE_USER) {
                openCreateRecipe()
            }
        }
        updateEmptyStateText(null)
    }

    /**
     * Тексты пустого состояния зависят от source и от того, в папке мы или в корне.
     */
    private fun updateEmptyStateText(folderName: String?) {
        val (icon, title, subtitle) = when {
            source == RecipeEntity.SOURCE_AI -> Triple(
                "🤖",
                "Рецептов ИИ пока нет",
                "Скажи, что хочешь приготовить, — и ИИ предложит рецепт.\n(Появится на Шаге 2)"
            )
            folderName != null -> Triple(
                "📁",
                "В папке «$folderName» пока пусто",
                "Нажми «+», чтобы добавить рецепт в эту папку,\nили «📁», чтобы создать подпапку."
            )
            source == RecipeEntity.SOURCE_USER -> Triple(
                "📝",
                "Здесь пока пусто",
                "Нажми «+», чтобы добавить первый рецепт:\nназвание, описание, а скоро — фото и видео.\nИли «📁», чтобы создать папку."
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
    }

    // ============================================================
    // FAB'ы
    // ============================================================

    private fun setupFabs() {
        // FAB «+» (создать рецепт).
        if (source == RecipeEntity.SOURCE_AI || source == RecipeEntity.SOURCE_USER) {
            binding.fabAddRecipe.visibility = View.VISIBLE
            binding.fabAddRecipe.setOnClickListener {
                openCreateRecipe()
            }
        } else {
            binding.fabAddRecipe.visibility = View.GONE
            binding.fabAddRecipe.setOnClickListener(null)
        }

        // FAB «📁» (создать папку) — только в «Моих рецептах».
        if (viewModel.isFolderModeAvailable()) {
            binding.fabCreateFolder.visibility = View.VISIBLE
            binding.fabCreateFolder.setOnClickListener {
                showCreateFolderDialog()
            }
        } else {
            binding.fabCreateFolder.visibility = View.GONE
            binding.fabCreateFolder.setOnClickListener(null)
        }
    }

    private fun openCreateRecipe() {
        Logger.log(TAG, "openCreateRecipe: source=$source, folderId=${viewModel.currentFolderIdLive.value}")
        val intent = Intent(this, RecipeEditActivity::class.java).apply {
            putExtra(RecipeEditActivity.EXTRA_SOURCE, source)
            // Папку передаём, чтобы новый рецепт сразу попал в открытую папку.
            viewModel.currentFolderIdLive.value?.let {
                putExtra(RecipeEditActivity.EXTRA_FOLDER_ID, it)
            }
        }
        editLauncher.launch(intent)
    }

    // ============================================================
    // ДИАЛОГИ ПАПОК
    // ============================================================

    /**
     * Создать папку на текущем уровне (в корне или в открытой папке).
     * Простой диалог с EditText.
     */
    private fun showCreateFolderDialog() {
        val editText = EditText(this).apply {
            hint = "Название папки"
            setSingleLine(true)
        }

        val container = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle("📁 Новая папка")
            .setView(container)
            .setPositiveButton("Создать") { _, _ ->
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Введите название папки", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val currentUser = viewModel.currentUserName
                viewModel.createFolder(name, currentUser) { error ->
                    runOnUiThread {
                        if (error != null) {
                            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Папка «$name» создана", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Долгий клик по папке: «Переименовать» / «Удалить».
     */
    private fun showFolderActionsDialog(folder: RecipeFolderEntity) {
        val options = arrayOf("✏️ Переименовать", "🗑️ Удалить папку")

        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folder)
                    1 -> confirmDeleteFolder(folder)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showRenameFolderDialog(folder: RecipeFolderEntity) {
        val editText = EditText(this).apply {
            setText(folder.name)
            setSelection(folder.name.length)
            setSingleLine(true)
        }

        val container = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle("✏️ Переименовать папку")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = editText.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Введите название папки", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName == folder.name) return@setPositiveButton
                val currentUser = viewModel.currentUserName
                viewModel.renameFolder(folder.id, newName, currentUser) { error ->
                    runOnUiThread {
                        if (error != null) {
                            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Папка переименована", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeleteFolder(folder: RecipeFolderEntity) {
        AlertDialog.Builder(this)
            .setTitle("🗑️ Удалить папку?")
            .setMessage("Папка «${folder.name}» будет удалена. Это возможно, только если в ней нет рецептов и подпапок.")
            .setPositiveButton("Удалить") { _, _ ->
                val currentUser = viewModel.currentUserName
                viewModel.deleteFolder(folder.id, currentUser) { error ->
                    runOnUiThread {
                        if (error != null) {
                            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Папка удалена", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // НАВИГАЦИЯ НАЗАД
    // ============================================================

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    /**
     * Обработка «назад»:
     *  - если внутри папки — вернуться в корень;
     *  - иначе — finish().
     */
    private fun handleBack() {
        if (viewModel.goBack()) {
            Logger.log(TAG, "handleBack: went to root")
        } else {
            Logger.log(TAG, "handleBack: finish")
            finish()
        }
    }

    // ============================================================
    // НАБЛЮДЕНИЕ ЗА VIEWMODEL
    // ============================================================

    private fun observeViewModel() {
        // Папки уровня.
        viewModel.folders.observe(this) { folders ->
            Logger.log(TAG, "folders observer: count=${folders.size}")
            rebuildList()
        }

        // 🆕 Шаг 1.7-3: счётчики рецептов в папках.
        viewModel.folderRecipeCounts.observe(this) { counts ->
            Logger.log(TAG, "folderRecipeCounts observer: size=${counts.size}")
            folderRecipeCounts = counts
            rebuildList()
        }

        // Рецепты уровня.
        viewModel.recipes.observe(this) { recipes ->
            Logger.log(TAG, "recipes observer: count=${recipes.size}")
            rebuildList()
        }

        // Имя текущей папки (для заголовка и текстов пустого состояния).
        viewModel.currentFolderName.observe(this) { name ->
            Logger.log(TAG, "currentFolderName observer: '$name'")
            updateTitle(name)
            updateEmptyStateText(name)
        }

        viewModel.isEmpty.observe(this) { empty ->
            updateUiState(isEmpty = empty)
        }

        viewModel.isLoading.observe(this) { loading ->
            Logger.log(TAG, "isLoading: $loading")
            updateUiState(isEmpty = viewModel.isEmpty.value ?: true)
        }
    }

    /**
     * Склеить папки и рецепты в один список для RecipeCatalogAdapter:
     * сначала все папки, потом все рецепты.
     */
    private fun rebuildList() {
        val folders = viewModel.folders.value ?: emptyList()
        val recipes = viewModel.recipes.value ?: emptyList()

        val list = ArrayList<RecipeCatalogAdapter.RecipeCatalogItem>(
            folders.size + recipes.size
        )

        folders.forEach { folder ->
            list.add(
                RecipeCatalogAdapter.RecipeCatalogItem.Folder(
                    folder = folder,
                    recipesCount = folderRecipeCounts[folder.id] ?: 0
                )
            )
        }

        recipes.forEach { item ->
            list.add(
                RecipeCatalogAdapter.RecipeCatalogItem.Recipe(
                    recipe = item.recipe,
                    photoFile = item.photoFile
                )
            )
        }

        adapter.submitList(list)
    }

    private fun updateUiState(isEmpty: Boolean) {
        val loading = viewModel.isLoading.value ?: false
        val hasItems = !isEmpty

        binding.rvRecipes.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.emptyState.visibility =
            if (isEmpty && !loading) View.VISIBLE else View.GONE
        binding.sortChipsScroll.visibility =
            if (hasItems) View.VISIBLE else View.GONE
    }
}
