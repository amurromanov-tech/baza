package com.family.base.ui

import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeFolderEntity
import com.family.base.databinding.ActivityRecipeListBinding
import com.family.base.ui.adapter.RecipeCatalogAdapter
import com.family.base.ui.viewmodel.RecipeSortType
import com.family.base.ui.viewmodel.RecipeViewModel
import com.family.base.util.Logger
import com.family.base.util.VoiceInputHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var folderRecipeCounts: Map<String, Int> = emptyMap()
    private var aiProgressDialog: ProgressDialog? = null
    private var pendingVoiceField: EditText? = null

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.log(TAG, "editLauncher result: ${result.resultCode}")
        viewModel.loadContent()
    }

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

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = VoiceInputHelper.extractRecognizedText(result.data)
            val field = pendingVoiceField
            if (!text.isNullOrEmpty() && field != null) {
                VoiceInputHelper.insertRecognizedText(field, text)
                // 🆕 v23.4.9 (27.11.2026): после вставки в поиск — сразу применяем запрос
                if (field === binding.etSearch) {
                    performSearchNow(field.text?.toString() ?: "")
                }
            }
        }
        pendingVoiceField = null
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
        setupSortButton()
        setupCountryFilterChip()
        setupEmptyState()
        setupFabs()
        setupBackHandler()
        observeViewModel()
        viewModel.loadContent()
    }

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

    private fun updateTitle(folderName: String?) {
        val title = when {
            folderName != null -> folderName
            source == RecipeEntity.SOURCE_AI -> "🤖 Рецепты ИИ"
            source == RecipeEntity.SOURCE_USER -> "📝 Мои рецепты"
            else -> "🍳 Рецепты"
        }
        supportActionBar?.title = title
    }

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
                Logger.log(TAG, "recipe longClicked: id=${recipe.id}, title='${recipe.title}'")
                showRecipeActionsDialog(recipe)
            },
            // 🆕 v23.4.9: колбэки для лайков/дизлайков/приготовлений (короткий клик = +1)
            onLikeClick = { recipe ->
                Logger.log(TAG, "like clicked: id=${recipe.id}")
                viewModel.likeRecipe(recipe.id)
            },
            onDislikeClick = { recipe ->
                Logger.log(TAG, "dislike clicked: id=${recipe.id}")
                viewModel.dislikeRecipe(recipe.id)
            },
            onCookedClick = { recipe ->
                Logger.log(TAG, "cooked clicked: id=${recipe.id}")
                viewModel.markCooked(recipe.id)
            },
            // 🆕 v23.4.9 (27.11.2026): долгие нажатия — декремент счётчиков
            onLongLikeClick = { recipe ->
                Logger.log(TAG, "like longClicked: id=${recipe.id}")
                viewModel.decrementLikes(recipe.id)
            },
            onLongDislikeClick = { recipe ->
                Logger.log(TAG, "dislike longClicked: id=${recipe.id}")
                viewModel.decrementDislikes(recipe.id)
            },
            onLongCookedClick = { recipe ->
                Logger.log(TAG, "cooked longClicked: id=${recipe.id}")
                viewModel.decrementCookedCount(recipe.id)
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

    private fun setupSearch() {
        if (source == EXTRA_SOURCE_ALL) {
            binding.btnSearch.visibility = View.GONE
            binding.searchLayout.visibility = View.GONE
            binding.btnSearch.setOnClickListener(null)
            return
        }
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
        // 🆕 v23.4.9 (27.11.2026): микрофон в поле поиска
        binding.tilSearch.setEndIconOnClickListener {
            startVoiceFor(binding.etSearch)
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
    // 🆕 v23.4.9: КНОПКА СОРТИРОВКИ (PopupMenu с 6 пунктами)
    // ============================================================
    private fun setupSortButton() {
        updateSortButtonText(viewModel.getSortType())
        binding.btnSort.setOnClickListener {
            showSortPopupMenu()
        }
    }

    // 🆕 v23.4.9 (27.11.2026): показываем "🔽 Сортировка" до явного выбора,
    // после выбора — иконку + displayName выбранного типа.
    private fun updateSortButtonText(sortType: RecipeSortType) {
        if (!viewModel.isUserSelectedSort()) {
            binding.btnSort.text = "🔽 Сортировка"
            return
        }
        val icon = when (sortType) {
            RecipeSortType.NEWEST -> "🕐"
            RecipeSortType.ALPHABETICAL -> "🔤"
            RecipeSortType.POPULARITY -> "🔥"
            RecipeSortType.LIKES -> "👍"
            RecipeSortType.DISLIKES -> "👎"
        }
        binding.btnSort.text = "$icon ${sortType.displayName}"
    }

    private fun showSortPopupMenu() {
        val popup = PopupMenu(this, binding.btnSort)
        val currentSort = viewModel.getSortType()

        popup.menu.add(0, 1, 1, "🕐 По новизне").apply {
            isChecked = currentSort == RecipeSortType.NEWEST
        }
        popup.menu.add(0, 2, 2, "🔤 По алфавиту").apply {
            isChecked = currentSort == RecipeSortType.ALPHABETICAL
        }
        popup.menu.add(0, 3, 3, "🔥 По популярности").apply {
            isChecked = currentSort == RecipeSortType.POPULARITY
        }
        popup.menu.add(0, 4, 4, "👍 По лайкам").apply {
            isChecked = currentSort == RecipeSortType.LIKES
        }
        popup.menu.add(0, 5, 5, "👎 По дизлайкам").apply {
            isChecked = currentSort == RecipeSortType.DISLIKES
        }
        popup.menu.setGroupCheckable(0, true, true)

        popup.menu.add(0, 6, 6, "🌍 По странам...")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    viewModel.setSortType(RecipeSortType.NEWEST)
                    true
                }
                2 -> {
                    viewModel.setSortType(RecipeSortType.ALPHABETICAL)
                    true
                }
                3 -> {
                    viewModel.setSortType(RecipeSortType.POPULARITY)
                    true
                }
                4 -> {
                    viewModel.setSortType(RecipeSortType.LIKES)
                    true
                }
                5 -> {
                    viewModel.setSortType(RecipeSortType.DISLIKES)
                    true
                }
                6 -> {
                    showCountryBottomSheet()
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    // ============================================================
    // 🆕 v23.4.9: ПЛАШКА ФИЛЬТРА ПО СТРАНЕ
    // ============================================================
    private fun setupCountryFilterChip() {
        binding.chipCountryFilter.setOnCloseIconClickListener {
            viewModel.clearCountryFilter()
        }
        binding.chipCountryFilter.setOnClickListener {
            showCountryBottomSheet()
        }
    }

    private fun updateCountryFilterChip(country: String?) {
        if (country.isNullOrBlank()) {
            binding.chipCountryFilter.visibility = View.GONE
        } else {
            binding.chipCountryFilter.visibility = View.VISIBLE
            binding.chipCountryFilter.text = "🌍 $country ✕"
        }
    }

    // ============================================================
    // 🆕 v23.4.9: BOTTOMSHEET «ПО СТРАНАМ»
    // ============================================================
    private fun showCountryBottomSheet() {
        viewModel.loadCountries()

        val dialog = BottomSheetDialog(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val titleView = TextView(this).apply {
            text = "🌍 Выбрать страну кухни"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
        }
        layout.addView(titleView)

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(listView)

        viewModel.countries.observe(this) { countries ->
            val items = countries.map { "${it.country} (${it.count})" }
            val adapter = android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                items
            )
            listView.adapter = adapter

            listView.setOnItemClickListener { _, _, position, _ ->
                val selectedCountry = countries[position].country
                viewModel.selectCountry(selectedCountry)
                dialog.dismiss()
            }
        }

        dialog.setContentView(layout)
        dialog.show()
    }

    private fun setupEmptyState() {
        binding.emptyState.setOnClickListener {
            if (source == RecipeEntity.SOURCE_AI) {
                showAiRecipeDialog()
            } else if (source == RecipeEntity.SOURCE_USER) {
                openCreateRecipe()
            }
        }
        updateEmptyStateText(null)
    }

    private fun updateEmptyStateText(folderName: String?) {
        val (icon, title, subtitle) = when {
            source == RecipeEntity.SOURCE_AI -> Triple(
                "🤖",
                "Рецептов ИИ пока нет",
                "Нажми «+» и скажи, что хочешь приготовить — ИИ предложит рецепт."
            )
            folderName != null -> Triple(
                "📁",
                "В папке «$folderName» пока пусто",
                "Нажми «+», чтобы добавить рецепт в эту папку,\nили зажми «+», чтобы создать подпапку."
            )
            source == RecipeEntity.SOURCE_USER -> Triple(
                "📝",
                "Здесь пока пусто",
                "Нажми «+», чтобы добавить первый рецепт:\nназвание, описание, а скоро — фото и видео.\nИли зажми «+», чтобы создать папку."
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
    // FAB «+»: короткий клик — создать рецепт, долгий — создать папку
    // 🆕 v23.4.9 (27.11.2026): btnAddFolder удалён из layout
    // ============================================================
    private fun setupFabs() {
        if (source == RecipeEntity.SOURCE_AI) {
            binding.fabAddRecipe.visibility = View.VISIBLE
            binding.fabAddRecipe.setOnClickListener {
                showAiRecipeDialog()
            }
            binding.fabAddRecipe.setOnLongClickListener { false }
        } else if (source == RecipeEntity.SOURCE_USER) {
            binding.fabAddRecipe.visibility = View.VISIBLE
            binding.fabAddRecipe.setOnClickListener {
                openCreateRecipe()
            }
            binding.fabAddRecipe.setOnLongClickListener {
                showCreateFolderConfirmDialog()
                true
            }
        } else {
            binding.fabAddRecipe.visibility = View.GONE
            binding.fabAddRecipe.setOnClickListener(null)
            binding.fabAddRecipe.setOnLongClickListener(null)
        }
    }

    // 🆕 v23.4.9 (27.11.2026): подтверждение создания папки по долгому нажатию FAB
    private fun showCreateFolderConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("📁 Создать папку?")
            .setMessage("Создать новую папку в текущем месте?")
            .setPositiveButton("Да") { _, _ ->
                showCreateFolderDialog()
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun openCreateRecipe() {
        Logger.log(TAG, "openCreateRecipe: source=$source, folderId=${viewModel.currentFolderIdLive.value}")
        val intent = Intent(this, RecipeEditActivity::class.java).apply {
            putExtra(RecipeEditActivity.EXTRA_SOURCE, source)
            viewModel.currentFolderIdLive.value?.let {
                putExtra(RecipeEditActivity.EXTRA_FOLDER_ID, it)
            }
        }
        editLauncher.launch(intent)
    }

    private fun showAiRecipeDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val editText = EditText(this).apply {
            hint = "Например: блины на молоке, паста карбонара..."
            setSingleLine(false)
            minLines = 2
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
        }
        val btnMic = Button(this).apply {
            text = "🎤"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                startVoiceFor(editText)
            }
        }
        layout.addView(editText)
        layout.addView(btnMic)

        AlertDialog.Builder(this)
            .setTitle("🤖 Сгенерировать рецепт")
            .setMessage("Опишите, что хотите приготовить:")
            .setView(layout)
            .setPositiveButton("Сгенерировать") { _, _ ->
                val prompt = editText.text?.toString()?.trim().orEmpty()
                if (prompt.isEmpty()) {
                    Toast.makeText(this, "Введите или скажите запрос", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                requestAiRecipe(prompt)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startVoiceFor(field: EditText) {
        pendingVoiceField = field
        VoiceInputHelper.startVoiceInput(this, voiceLauncher, "Говорите…")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VoiceInputHelper.REQUEST_CODE_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                val field = pendingVoiceField
                if (field != null) {
                    VoiceInputHelper.startVoiceInput(this, voiceLauncher, "Говорите…")
                }
            } else {
                pendingVoiceField = null
                Toast.makeText(
                    this,
                    "Без разрешения на микрофон голосовой ввод недоступен",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun requestAiRecipe(prompt: String) {
        showAiProgressDialog()
        viewModel.generateAiRecipe(prompt) { error ->
            runOnUiThread {
                dismissAiProgressDialog()
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAiProgressDialog() {
        dismissAiProgressDialog()
        aiProgressDialog = ProgressDialog(this).apply {
            setMessage("🤖 ИИ генерирует рецепт...")
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            show()
        }
    }

    private fun dismissAiProgressDialog() {
        aiProgressDialog?.dismiss()
        aiProgressDialog = null
    }

    private fun openCreateRecipeWithAiText(aiText: String) {
        Logger.log(TAG, "openCreateRecipeWithAiText: length=${aiText.length}")
        val intent = Intent(this, RecipeEditActivity::class.java).apply {
            putExtra(RecipeEditActivity.EXTRA_SOURCE, RecipeEntity.SOURCE_AI)
            putExtra(RecipeEditActivity.EXTRA_AI_RECIPE_TEXT, aiText)
        }
        editLauncher.launch(intent)
    }

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

    private fun showRecipeActionsDialog(recipe: RecipeEntity) {
        if (!viewModel.isFolderModeAvailable()) {
            Logger.log(TAG, "showRecipeActionsDialog: folder mode unavailable for source=$source")
            Toast.makeText(this, "У рецептов ИИ нет папок", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("📁 Переместить в папку…")
        AlertDialog.Builder(this)
            .setTitle(recipe.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showMoveToFolderDialog(recipe)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showMoveToFolderDialog(recipe: RecipeEntity) {
        lifecycleScope.launch {
            val folders = try {
                withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(applicationContext)
                        .recipeFolderDao()
                        .getAllLive()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "showMoveToFolderDialog: load folders error: ${e.message}", e)
                Toast.makeText(this@RecipeListActivity, "Не удалось загрузить папки", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val ordered = buildFolderHierarchy(folders)
            if (ordered.isEmpty()) {
                Toast.makeText(
                    this@RecipeListActivity,
                    "Нет папок. Создайте папку долгим нажатием на «+».",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val labels = ArrayList<String>(ordered.size + 1)
            val ids = ArrayList<String?>(ordered.size + 1)
            labels.add("📁 Без папки (корень)")
            ids.add(null)
            ordered.forEach { (folder, depth) ->
                val indent = if (depth > 0) "— ".repeat(depth) else ""
                val currentMark = if (folder.id == recipe.folderId) " •" else ""
                labels.add("$indent${folder.name}$currentMark")
                ids.add(folder.id)
            }
            AlertDialog.Builder(this@RecipeListActivity)
                .setTitle("📁 Переместить в папку")
                .setItems(labels.toTypedArray()) { _, which ->
                    val targetFolderId = ids[which]
                    if (targetFolderId == recipe.folderId) {
                        Toast.makeText(
                            this@RecipeListActivity,
                            "Рецепт уже в этой папке",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setItems
                    }
                    val currentUser = viewModel.currentUserName
                    viewModel.moveRecipeToFolder(recipe.id, targetFolderId, currentUser) { error ->
                        runOnUiThread {
                            if (error != null) {
                                Toast.makeText(this@RecipeListActivity, error, Toast.LENGTH_SHORT).show()
                            } else {
                                val targetName = if (targetFolderId == null) {
                                    "корень"
                                } else {
                                    folders.firstOrNull { it.id == targetFolderId }?.name ?: "папку"
                                }
                                Toast.makeText(
                                    this@RecipeListActivity,
                                    "Перемещено в $targetName",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun buildFolderHierarchy(
        all: List<RecipeFolderEntity>
    ): List<Pair<RecipeFolderEntity, Int>> {
        val byParent = all.groupBy { it.parentId }
        val result = ArrayList<Pair<RecipeFolderEntity, Int>>(all.size)
        fun walk(parentId: String?, depth: Int) {
            val children = byParent[parentId] ?: return
            children.forEach { folder ->
                result.add(folder to depth)
                walk(folder.id, depth + 1)
            }
        }
        walk(null, 0)
        return result
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    private fun handleBack() {
        if (viewModel.goBack()) {
            Logger.log(TAG, "handleBack: went to parent or root")
        } else {
            Logger.log(TAG, "handleBack: finish")
            finish()
        }
    }

    private fun observeViewModel() {
        viewModel.folders.observe(this) { folders ->
            Logger.log(TAG, "folders observer: count=${folders.size}")
            rebuildList()
        }
        viewModel.folderRecipeCounts.observe(this) { counts ->
            Logger.log(TAG, "folderRecipeCounts observer: size=${counts.size}")
            folderRecipeCounts = counts
            rebuildList()
        }
        viewModel.recipes.observe(this) { recipes ->
            Logger.log(TAG, "recipes observer: count=${recipes.size}")
            rebuildList()
        }
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
        viewModel.generatedAiRecipe.observe(this) { recipeText ->
            if (!recipeText.isNullOrBlank()) {
                Logger.log(TAG, "generatedAiRecipe observer: got text, length=${recipeText.length}")
                viewModel.clearAiState()
                openCreateRecipeWithAiText(recipeText)
            }
        }
        viewModel.aiError.observe(this) { error ->
            if (!error.isNullOrBlank()) {
                Logger.log(TAG, "aiError observer: $error")
            }
        }

        // 🆕 v23.4.9: наблюдение за типом сортировки
        viewModel.currentSortType.observe(this) { sortType ->
            Logger.log(TAG, "currentSortType observer: ${sortType.key}")
            updateSortButtonText(sortType)
        }

        // 🆕 v23.4.9 (27.11.2026): флаг явного выбора сортировки
        viewModel.userSelectedSort.observe(this) { selected ->
            Logger.log(TAG, "userSelectedSort observer: $selected")
            updateSortButtonText(viewModel.getSortType())
        }

        // 🆕 v23.4.9: наблюдение за фильтром по стране
        viewModel.selectedCountry.observe(this) { country ->
            Logger.log(TAG, "selectedCountry observer: '$country'")
            updateCountryFilterChip(country)
        }
    }

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
        // 🆕 v23.4.9: показываем кнопку сортировки, если есть элементы
        binding.btnSort.visibility =
            if (hasItems) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissAiProgressDialog()
    }
}
