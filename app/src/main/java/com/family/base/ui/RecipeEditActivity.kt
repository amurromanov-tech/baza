package com.family.base.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.data.repository.RecipesRepository
import com.family.base.databinding.ActivityRecipeEditBinding
import com.family.base.ui.adapter.RecipeMediaEditAdapter
import com.family.base.util.Logger
import com.family.base.util.RecipeMediaController
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🆕 v21.1.0 (Шаг 1.4a + 1.5-2): создание / редактирование рецепта.
 * 🆕 Шаг 1.7-4: ПАПКА ПРИ СОЗДАНИИ.
 * При создании рецепта из открытой папки RecipeListActivity передаёт
 * EXTRA_FOLDER_ID — новый рецепт сразу попадает в эту папку.
 * Для «Рецептов ИИ» (source = "ai") EXTRA_FOLDER_ID игнорируется
 * (у ИИ папок нет).
 * Блок «Папка» в этом экране НЕ показываем (решение пользователя:
 * перемещение — через долгий клик в RecipeListActivity, Шаг 1.7-4).
 *
 * 🆕 Шаг 2: AI-ГЕНЕРАЦИЯ.
 * При создании рецепта из «🤖 Рецепты ИИ» RecipeListActivity передаёт
 * EXTRA_AI_RECIPE_TEXT — сгенерированный ИИ текст рецепта.
 * Он автоматически заполняет поле описания. Пользователь может
 * отредактировать текст и ввести название перед сохранением.
 *
 * 🆕 v23.4.9: СТРАНА КУХНИ.
 * Добавлено обязательное поле "Страна кухни". При клике на поле
 * открывается BottomSheet со списком популярных кухонь мира.
 * Пользователь может выбрать предопределённую страну или ввести свою.
 * При сохранении проверяется, что страна не пустая.
 *
 * Режимы:
 * - Создание: extra EXTRA_RECIPE_ID НЕ передан.
 *   extra EXTRA_SOURCE обязателен: "ai" или "user".
 *   для source="ai" опционально EXTRA_AI_RECIPE_TEXT.
 * - Редактирование: extra EXTRA_RECIPE_ID передан.
 *   extra EXTRA_SOURCE игнорируется — берётся из рецепта.
 *
 * Поля:
 * - Название: обязательно, обрезается trim(), не может быть пустым.
 * - Страна кухни: обязательно, выбирается из списка.
 * - Описание: необязательно, многострочное. Пустая строка → null.
 *
 * МЕДИА (🆕 Шаг 1.5-2):
 * В режиме РЕДАКТИРОВАНИЯ показываем блок «Фото и видео»:
 * горизонтальный список уже добавленных файлов + кнопки
 * «📷 Добавить фото» / «🎬 Добавить видео».
 * В режиме СОЗДАНИЯ медиа-блок скрыт с подсказкой
 * «Сначала сохраните рецепт…».
 *
 * ВСЁ РАБОТАЕТ ОФЛАЙН: файлы пишутся в filesDir/recipe_media/<recipeId>/.
 */
class RecipeEditActivity : AppCompatActivity() {

    companion object {
        const val TAG = "RecipeEditActivity"
        const val EXTRA_RECIPE_ID = "recipe_id"
        const val EXTRA_SOURCE = "recipe_source"

        /**
         * 🆕 Шаг 1.7-4: id папки, в которую нужно положить новый рецепт.
         * Передаётся из RecipeListActivity, если создание идёт из открытой папки.
         * Для source = "ai" игнорируется.
         */
        const val EXTRA_FOLDER_ID = "recipe_folder_id"

        /**
         * 🆕 Шаг 2: текст рецепта, сгенерированный ИИ.
         * Передаётся из RecipeListActivity при создании рецепта в «🤖 Рецепты ИИ».
         * Автоматически заполняет поле описания.
         */
        const val EXTRA_AI_RECIPE_TEXT = "ai_recipe_text"

        /**
         * 🆕 v23.4.9: Список популярных стран кухни для BottomSheet.
         */
        val POPULAR_COUNTRIES = listOf(
            "Русская",
            "Итальянская",
            "Французская",
            "Японская",
            "Китайская",
            "Индийская",
            "Мексиканская",
            "Испанская",
            "Греческая",
            "Грузинская",
            "Армянская",
            "Азербайджанская",
            "Узбекская",
            "Казахская",
            "Украинская",
            "Белорусская",
            "Польская",
            "Немецкая",
            "Английская",
            "Американская",
            "Бразильская",
            "Тайская",
            "Вьетнамская",
            "Корейская",
            "Турецкая",
            "Марокканская",
            "Ливанская",
            "Израильская",
            "Другая"
        )
    }

    private lateinit var binding: ActivityRecipeEditBinding
    private lateinit var repository: RecipesRepository
    private lateinit var tokenStorage: TokenStorage

    /** id рецепта для редактирования. null — режим создания. */
    private var editingRecipeId: String? = null

    /** source рецепта: "ai" или "user". Для создания обязателен. */
    private var source: String = RecipeEntity.SOURCE_USER

    /**
     * 🆕 Шаг 1.7-4: папка, в которую нужно положить новый рецепт.
     * null — корень «Моих рецептов». Для source = "ai" игнорируется.
     */
    private var targetFolderId: String? = null

    /**
     * 🆕 Шаг 2: текст рецепта от ИИ.
     * Если не null — заполнит поле описания при создании.
     */
    private var aiRecipeText: String? = null

    /**
     * 🆕 v23.4.9: выбранная страна кухни.
     * Пустая строка — не выбрана (валидация не пройдёт).
     */
    private var selectedCountry: String = ""

    /** Текущий пользователь (для createdBy / audit). */
    private val currentUser: String
        get() = tokenStorage.getCurrentUser()
            ?: tokenStorage.getUserDisplayName()
            ?: "Пользователь"

    /** Адаптер медиа (только в режиме редактирования). */
    private lateinit var mediaAdapter: RecipeMediaEditAdapter

    /** true — идёт сохранение, блокирует повторные клики. */
    private var saving = false

    // ============================================================
    // LAUNCHERS
    // ============================================================
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) {
            Logger.log(TAG, "pickPhotoLauncher: cancelled")
            return@registerForActivityResult
        }
        onPhotoPicked(uri)
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            Logger.log(TAG, "pickVideoLauncher: cancelled")
            return@registerForActivityResult
        }
        onVideoPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== RecipeEditActivity onCreate START ===")
        try {
            binding = ActivityRecipeEditBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        val db = AppDatabase.getInstance(this)
        repository = RecipesRepository(db, applicationContext)
        tokenStorage = TokenStorage(this)

        editingRecipeId = intent.getStringExtra(EXTRA_RECIPE_ID)
        val extraSource = intent.getStringExtra(EXTRA_SOURCE)

        // 🆕 Шаг 1.7-4: папка для нового рецепта (только для "user").
        val extraFolderId = intent.getStringExtra(EXTRA_FOLDER_ID)

        // 🆕 Шаг 2: текст рецепта от ИИ (только для "ai").
        aiRecipeText = intent.getStringExtra(EXTRA_AI_RECIPE_TEXT)

        if (editingRecipeId == null) {
            if (extraSource != RecipeEntity.SOURCE_AI && extraSource != RecipeEntity.SOURCE_USER) {
                Logger.log(TAG, "onCreate: CREATE without valid source (got=$extraSource), finish")
                Toast.makeText(this, "Не указан источник рецепта", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            source = extraSource!!
            // Папка имеет смысл только для «Моих рецептов». Для ИИ — null.
            targetFolderId = if (source == RecipeEntity.SOURCE_USER) extraFolderId else null
            Logger.log(
                TAG,
                "onCreate: CREATE mode, source=$source, targetFolderId=$targetFolderId, aiRecipeText=${aiRecipeText?.length ?: 0} chars"
            )
        } else {
            Logger.log(TAG, "onCreate: EDIT mode, recipeId=$editingRecipeId")
            // В режиме редактирования папку не меняем — она уже есть у рецепта.
            targetFolderId = null
            aiRecipeText = null // при редактировании AI-текст не используется
        }

        setupToolbar()
        setupSaveButton()
        setupCountryPicker() // 🆕 v23.4.9
        setupMediaBlock()

        if (editingRecipeId != null) {
            loadRecipeForEdit(editingRecipeId!!)
            loadMediaForEdit(editingRecipeId!!)
        } else if (aiRecipeText != null && source == RecipeEntity.SOURCE_AI) {
            // 🆕 Шаг 2: предзаполнение описания текстом от ИИ
            binding.etDescription.setText(aiRecipeText)
            binding.etDescription.setSelection(aiRecipeText!!.length)
            Logger.log(TAG, "onCreate: pre-filled description with AI text (${aiRecipeText!!.length} chars)")
        }
    }

    // ============================================================
    // ТУЛБАР
    // ============================================================
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        val title = if (editingRecipeId == null) {
            if (source == RecipeEntity.SOURCE_AI) "Новый рецепт ИИ" else "Новый рецепт"
        } else {
            "Редактирование рецепта"
        }
        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener {
            Logger.log(TAG, "toolbar up clicked")
            confirmDiscardIfChanged()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            confirmDiscardIfChanged()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        confirmDiscardIfChanged()
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            onSaveClicked()
        }
    }

    // ============================================================
    // 🆕 v23.4.9: ВЫБОР СТРАНЫ КУХНИ
    // ============================================================
    private fun setupCountryPicker() {
        binding.etCountry.setOnClickListener {
            showCountryPickerDialog()
        }
    }

    private fun showCountryPickerDialog() {
        val dialog = BottomSheetDialog(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val titleView = TextView(this).apply {
            text = "🌍 Выберите страну кухни"
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

        val adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            POPULAR_COUNTRIES
        )
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val country = POPULAR_COUNTRIES[position]
            if (country == "Другая") {
                showCustomCountryDialog()
            } else {
                selectedCountry = country
                binding.etCountry.setText(country)
                binding.tilCountry.error = null
                dialog.dismiss()
            }
        }

        dialog.setContentView(layout)
        dialog.show()
    }

    private fun showCustomCountryDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "Введите название страны"
            setSingleLine(true)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle("🌍 Другая страна")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val country = editText.text?.toString()?.trim().orEmpty()
                if (country.isNotEmpty()) {
                    selectedCountry = country
                    binding.etCountry.setText(country)
                    binding.tilCountry.error = null
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // МЕДИА-БЛОК (🆕 1.5-2)
    // ============================================================
    private fun setupMediaBlock() {
        mediaAdapter = RecipeMediaEditAdapter(
            recipeId = editingRecipeId ?: "",
            onRemoveClick = { media -> onRemoveMediaClicked(media) },
            onItemClick = { media ->
                Logger.log(TAG, "media item clicked: id=${media.id}, type=${media.mediaType}")
            }
        )
        binding.rvMedia.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.rvMedia.adapter = mediaAdapter

        binding.btnAddPhoto.setOnClickListener {
            val id = editingRecipeId
            if (id == null) {
                Toast.makeText(
                    this,
                    "Сначала сохраните рецепт",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            Logger.log(TAG, "btnAddPhoto clicked, recipeId=$id")
            pickPhotoLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        binding.btnAddVideo.setOnClickListener {
            val id = editingRecipeId
            if (id == null) {
                Toast.makeText(
                    this,
                    "Сначала сохраните рецепт",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            Logger.log(TAG, "btnAddVideo clicked, recipeId=$id")
            pickVideoLauncher.launch("video/*")
        }

        updateMediaBlockVisibility()
    }

    private fun updateMediaBlockVisibility() {
        val isEdit = editingRecipeId != null
        binding.llAddMediaButtons.visibility = if (isEdit) View.VISIBLE else View.GONE
        if (!isEdit) {
            binding.rvMedia.visibility = View.GONE
            binding.tvMediaHint.visibility = View.VISIBLE
            binding.tvMediaHint.text = "Сначала сохраните рецепт, чтобы добавить фото и видео."
        }
    }

    private fun refreshMediaHint() {
        if (editingRecipeId == null) {
            binding.tvMediaHint.visibility = View.VISIBLE
            binding.tvMediaHint.text = "Сначала сохраните рецепт, чтобы добавить фото и видео."
            binding.rvMedia.visibility = View.GONE
            return
        }
        val count = mediaAdapter.itemCount
        if (count == 0) {
            binding.tvMediaHint.visibility = View.VISIBLE
            binding.tvMediaHint.text = "Пока нет ни одного фото или видео. Добавьте первое 👇"
            binding.rvMedia.visibility = View.GONE
        } else {
            binding.tvMediaHint.visibility = View.GONE
            binding.rvMedia.visibility = View.VISIBLE
        }
    }

    // ============================================================
    // ЗАГРУЗКА РЕЦЕПТА (режим редактирования)
    // ============================================================
    private fun loadRecipeForEdit(recipeId: String) {
        lifecycleScope.launch {
            val recipe = withContext(Dispatchers.IO) {
                try {
                    repository.getRecipeById(recipeId)
                } catch (e: Exception) {
                    Logger.log(TAG, "loadRecipeForEdit error: ${e.message}", e)
                    null
                }
            }
            if (recipe == null) {
                Logger.log(TAG, "loadRecipeForEdit: recipe not found or tombstone: $recipeId")
                Toast.makeText(
                    this@RecipeEditActivity,
                    "Рецепт не найден",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            source = recipe.source
            // 🆕 Шаг 1.7-4: запоминаем текущую папку рецепта (для информации в логах).
            targetFolderId = recipe.folderId

            // 🆕 v23.4.9: загружаем страну кухни
            selectedCountry = recipe.cuisineCountry
            if (selectedCountry.isNotEmpty()) {
                binding.etCountry.setText(selectedCountry)
            } else {
                binding.etCountry.setText("Выберите страну")
            }

            binding.etTitle.setText(recipe.title)
            binding.etDescription.setText(recipe.description ?: "")
            Logger.log(
                TAG,
                "loadRecipeForEdit: loaded '${recipe.title}', source=${recipe.source}, folderId=${recipe.folderId}, country='${recipe.cuisineCountry}'"
            )
        }
    }

    // ============================================================
    // ЗАГРУЗКА МЕДИА (режим редактирования)
    // ============================================================
    private fun loadMediaForEdit(recipeId: String) {
        lifecycleScope.launch {
            val media = withContext(Dispatchers.IO) {
                try {
                    repository.getAllMedia(recipeId)
                } catch (e: Exception) {
                    Logger.log(TAG, "loadMediaForEdit error: ${e.message}", e)
                    emptyList()
                }
            }
            Logger.log(TAG, "loadMediaForEdit: count=${media.size}")
            mediaAdapter.submitList(media)
            refreshMediaHint()
        }
    }

    // ============================================================
    // ДОБАВЛЕНИЕ ФОТО
    // ============================================================
    private fun onPhotoPicked(uri: Uri) {
        val recipeId = editingRecipeId ?: return
        Logger.log(TAG, "onPhotoPicked: recipeId=$recipeId, uri=$uri")
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    RecipeMediaController.savePhotoFromUri(this@RecipeEditActivity, recipeId, uri)
                }
                if (saved == null) {
                    Toast.makeText(
                        this@RecipeEditActivity,
                        "Не удалось сохранить фото",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val media = withContext(Dispatchers.IO) {
                    repository.addMedia(
                        recipeId = recipeId,
                        mediaType = RecipeMediaEntity.TYPE_PHOTO,
                        localName = saved.localName,
                        thumbName = saved.thumbName,
                        mimeType = saved.mimeType,
                        currentUser = currentUser,
                        mediaId = saved.mediaId
                    )
                }
                if (media == null) {
                    withContext(Dispatchers.IO) {
                        RecipeMediaController.deleteMediaFiles(
                            this@RecipeEditActivity,
                            recipeId,
                            saved.localName,
                            saved.thumbName
                        )
                    }
                    Toast.makeText(
                        this@RecipeEditActivity,
                        "Не удалось добавить фото",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                Logger.log(TAG, "onPhotoPicked: added media id=${media.id}")
                loadMediaForEdit(recipeId)
            } catch (e: Exception) {
                Logger.log(TAG, "onPhotoPicked ERROR: ${e.message}", e)
                Toast.makeText(
                    this@RecipeEditActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // ДОБАВЛЕНИЕ ВИДЕО
    // ============================================================
    private fun onVideoPicked(uri: Uri) {
        val recipeId = editingRecipeId ?: return
        Logger.log(TAG, "onVideoPicked: recipeId=$recipeId, uri=$uri")
        lifecycleScope.launch {
            try {
                Toast.makeText(
                    this@RecipeEditActivity,
                    "Сохраняю видео…",
                    Toast.LENGTH_SHORT
                ).show()
                val saved = withContext(Dispatchers.IO) {
                    RecipeMediaController.saveVideoFromUri(this@RecipeEditActivity, recipeId, uri)
                }
                if (saved == null) {
                    Toast.makeText(
                        this@RecipeEditActivity,
                        "Не удалось сохранить видео",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val media = withContext(Dispatchers.IO) {
                    repository.addMedia(
                        recipeId = recipeId,
                        mediaType = RecipeMediaEntity.TYPE_VIDEO,
                        localName = saved.localName,
                        thumbName = saved.thumbName,
                        mimeType = saved.mimeType,
                        currentUser = currentUser,
                        mediaId = saved.mediaId
                    )
                }
                if (media == null) {
                    withContext(Dispatchers.IO) {
                        RecipeMediaController.deleteMediaFiles(
                            this@RecipeEditActivity,
                            recipeId,
                            saved.localName,
                            saved.thumbName
                        )
                    }
                    Toast.makeText(
                        this@RecipeEditActivity,
                        "Не удалось добавить видео",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                Logger.log(TAG, "onVideoPicked: added media id=${media.id}")
                loadMediaForEdit(recipeId)
            } catch (e: Exception) {
                Logger.log(TAG, "onVideoPicked ERROR: ${e.message}", e)
                Toast.makeText(
                    this@RecipeEditActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // УДАЛЕНИЕ МЕДИА
    // ============================================================
    private fun onRemoveMediaClicked(media: RecipeMediaEntity) {
        val recipeId = editingRecipeId ?: return
        AlertDialog.Builder(this)
            .setTitle("Удалить файл?")
            .setMessage(
                if (media.mediaType == RecipeMediaEntity.TYPE_VIDEO) {
                    "Видео будет удалено из рецепта."
                } else {
                    "Фото будет удалено из рецепта."
                }
            )
            .setPositiveButton("Удалить") { _, _ ->
                doRemoveMedia(recipeId, media)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doRemoveMedia(recipeId: String, media: RecipeMediaEntity) {
        lifecycleScope.launch {
            try {
                val okDb = withContext(Dispatchers.IO) {
                    repository.deleteMedia(media.id, currentUser)
                }
                if (!okDb) {
                    Toast.makeText(
                        this@RecipeEditActivity,
                        "Не удалось удалить файл",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    RecipeMediaController.deleteMediaFiles(
                        this@RecipeEditActivity,
                        recipeId,
                        media.imageUrl,
                        media.thumbnailUrl
                    )
                }
                Logger.log(TAG, "doRemoveMedia: removed id=${media.id}")
                loadMediaForEdit(recipeId)
            } catch (e: Exception) {
                Logger.log(TAG, "doRemoveMedia ERROR: ${e.message}", e)
                Toast.makeText(
                    this@RecipeEditActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // СОХРАНЕНИЕ
    // ============================================================
    private fun onSaveClicked() {
        if (saving) return

        val title = binding.etTitle.text?.toString()?.trim().orEmpty()
        if (TextUtils.isEmpty(title)) {
            binding.tilTitle.error = "Введите название"
            binding.etTitle.requestFocus()
            return
        }
        binding.tilTitle.error = null

        // 🆕 v23.4.9: валидация страны кухни
        val country = selectedCountry.trim()
        if (country.isEmpty() || country == "Выберите страну") {
            binding.tilCountry.error = "Выберите страну кухни"
            return
        }
        binding.tilCountry.error = null

        val description = binding.etDescription.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }

        saving = true
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            try {
                val recipeId = editingRecipeId
                if (recipeId == null) {
                    // ============================================================
                    // 🆕 Шаг 1.7-4: создание с папкой.
                    // targetFolderId уже выставлен в onCreate:
                    //  - для "user" — из EXTRA_FOLDER_ID (или null = корень);
                    //  - для "ai"   — всегда null (папок у ИИ нет).
                    // 🆕 v23.4.9: передаём cuisineCountry.
                    // ============================================================
                    val created = withContext(Dispatchers.IO) {
                        repository.createRecipe(
                            title = title,
                            description = description,
                            source = source,
                            currentUser = currentUser,
                            folderId = targetFolderId,
                            cuisineCountry = country
                        )
                    }
                    saving = false
                    binding.btnSave.isEnabled = true
                    if (created == null) {
                        Toast.makeText(
                            this@RecipeEditActivity,
                            "Не удалось сохранить рецепт",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    Logger.log(
                        TAG,
                        "onSaveClicked: created id=${created.id}, folderId=${created.folderId}, country='${created.cuisineCountry}', reopening in EDIT mode"
                    )
                    reopenInEditMode(created.id)
                } else {
                    // 🆕 v23.4.9: обновление рецепта + страны кухни
                    val ok = withContext(Dispatchers.IO) {
                        val okRecipe = repository.updateRecipe(
                            recipeId = recipeId,
                            newTitle = title,
                            newDescription = description,
                            currentUser = currentUser
                        )
                        val okCountry = repository.updateCuisineCountry(
                            recipeId = recipeId,
                            country = country,
                            currentUser = currentUser
                        )
                        okRecipe && okCountry
                    }
                    saving = false
                    binding.btnSave.isEnabled = true
                    if (ok) {
                        Logger.log(TAG, "onSaveClicked: OK, recipeId=$recipeId, country='$country'")
                        val result = Intent().apply {
                            putExtra(EXTRA_RECIPE_ID, recipeId)
                        }
                        setResult(RESULT_OK, result)
                        finish()
                    } else {
                        Toast.makeText(
                            this@RecipeEditActivity,
                            "Не удалось сохранить рецепт",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Logger.log(TAG, "onSaveClicked error: ${e.message}", e)
                saving = false
                binding.btnSave.isEnabled = true
                Toast.makeText(
                    this@RecipeEditActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun reopenInEditMode(recipeId: String) {
        val intent = Intent(this, RecipeEditActivity::class.java).apply {
            putExtra(EXTRA_RECIPE_ID, recipeId)
            addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        }
        startActivity(intent)
        finish()
    }

    // ============================================================
    // ПОДТВЕРЖДЕНИЕ ВЫХОДА
    // ============================================================
    private fun confirmDiscardIfChanged() {
        if (saving) return
        val titleNow = binding.etTitle.text?.toString()?.trim().orEmpty()
        val descNow = binding.etDescription.text?.toString()?.trim().orEmpty()
        val countryNow = selectedCountry.trim()
        if (titleNow.isEmpty() && descNow.isEmpty() && countryNow.isEmpty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Закрыть без сохранения?")
            .setMessage("Введённые изменения не будут сохранены. Фото и видео уже сохранены.")
            .setPositiveButton("Закрыть") { _, _ -> finish() }
            .setNegativeButton("Отмена", null)
            .show()
    }
}

// ============================================================
// 🆕 v23.4.9 (27.11.2026) — кухня мира + рейтинг
// Изменения:
//   - Добавлен список POPULAR_COUNTRIES (29 популярных кухонь)
//   - Добавлена переменная selectedCountry
//   - Добавлен setupCountryPicker() — обработка клика на поле страны
//   - Добавлен showCountryPickerDialog() — BottomSheet со списком стран
//   - Добавлен showCustomCountryDialog() — ввод своей страны (пункт "Другая")
//   - В loadRecipeForEdit() загружаем cuisineCountry из рецепта
//   - В onSaveClicked() валидация страны + передача в createRecipe()
//   - В onSaveClicked() для редактирования — вызов updateCuisineCountry()
//   - В confirmDiscardIfChanged() учитываем countryNow
// ============================================================
