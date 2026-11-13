package com.family.base.ui

import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.MenuItem
import android.view.View
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🆕 v21.1.0 (Шаг 1.4a + 1.5-2): создание / редактирование рецепта.
 *
 * Режимы:
 *  - Создание: extra EXTRA_RECIPE_ID НЕ передан.
 *              extra EXTRA_SOURCE обязателен: "ai" или "user".
 *  - Редактирование: extra EXTRA_RECIPE_ID передан.
 *              extra EXTRA_SOURCE игнорируется — берётся из рецепта.
 *
 * Поля:
 *  - Название: обязательно, обрезается trim(), не может быть пустым.
 *  - Описание: необязательно, многострочное. Пустая строка → null.
 *
 * МЕДИА (🆕 Шаг 1.5-2):
 *  - В режиме РЕДАКТИРОВАНИЯ (рецепт уже сохранён) показываем блок
 *    «Фото и видео»: горизонтальный список уже добавленных файлов
 *    + кнопки «📷 Добавить фото» / «🎬 Добавить видео».
 *  - В режиме СОЗДАНИЯ медиа-блок скрыт с подсказкой
 *    «Сначала сохраните рецепт…» — потому что recipe_media.recipeId
 *    требует существующего рецепта.
 *  - Фото: PickVisualMedia(ImageOnly) → RecipeMediaController.savePhotoFromUri
 *          → RecipesRepository.addMedia(mediaType=photo, mediaId=...).
 *  - Видео: GetContent("video/*") → RecipeMediaController.saveVideoFromUri
 *          → RecipesRepository.addMedia(mediaType=video, mediaId=...).
 *  - Удаление: RecipeMediaController.deleteMediaFiles + RecipesRepository.deleteMedia.
 *  - ВСЁ РАБОТАЕТ ОФЛАЙН: файлы пишутся в filesDir/recipe_media/<recipeId>/.
 *    Синк на Диск — Шаг 1.6.
 *
 * Сохранение:
 *  - Создание: RecipesRepository.createRecipe(...) → RESULT_OK + EXTRA_RECIPE_ID.
 *  - Редактирование: RecipesRepository.updateRecipe(...) → RESULT_OK + EXTRA_RECIPE_ID.
 *  - Ошибка: Toast + остаёмся на экране (не закрываемся, чтобы не потерять ввод).
 *
 * Возврат: setResult(RESULT_OK, intentWithExtra(EXTRA_RECIPE_ID, id)).
 */
class RecipeEditActivity : AppCompatActivity() {

    companion object {
        const val TAG = "RecipeEditActivity"

        const val EXTRA_RECIPE_ID = "recipe_id"
        const val EXTRA_SOURCE = "recipe_source"
    }

    private lateinit var binding: ActivityRecipeEditBinding
    private lateinit var repository: RecipesRepository
    private lateinit var tokenStorage: TokenStorage

    /** id рецепта для редактирования. null — режим создания. */
    private var editingRecipeId: String? = null

    /** source рецепта: "ai" или "user". Для создания обязателен. */
    private var source: String = RecipeEntity.SOURCE_USER

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

    /**
     * Выбор фото (системный пикер, без permission).
     * Если пользователь отменил — uri == null.
     */
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) {
            Logger.log(TAG, "pickPhotoLauncher: cancelled")
            return@registerForActivityResult
        }
        onPhotoPicked(uri)
    }

    /**
     * Выбор видео (системный пикер).
     * GetContent("video/*") — стандартный SAF, работает без permission.
     */
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

        if (editingRecipeId == null) {
            // Режим создания — source обязателен.
            if (extraSource != RecipeEntity.SOURCE_AI && extraSource != RecipeEntity.SOURCE_USER) {
                Logger.log(TAG, "onCreate: CREATE without valid source (got=$extraSource), finish")
                Toast.makeText(this, "Не указан источник рецепта", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            source = extraSource!!
            Logger.log(TAG, "onCreate: CREATE mode, source=$source")
        } else {
            Logger.log(TAG, "onCreate: EDIT mode, recipeId=$editingRecipeId")
        }

        setupToolbar()
        setupSaveButton()
        setupMediaBlock()

        if (editingRecipeId != null) {
            loadRecipeForEdit(editingRecipeId!!)
            loadMediaForEdit(editingRecipeId!!)
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
    // МЕДИА-БЛОК (🆕 1.5-2)
    // ============================================================

    private fun setupMediaBlock() {
        mediaAdapter = RecipeMediaEditAdapter(
            recipeId = editingRecipeId ?: "",
            onRemoveClick = { media -> onRemoveMediaClicked(media) },
            onItemClick = { media ->
                Logger.log(TAG, "media item clicked: id=${media.id}, type=${media.mediaType}")
                // В режиме редактирования клик по превью пока ничего не открывает.
                // Просмотр — RecipeDetailActivity (1.5-3).
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

    /**
     * Видимость блока «Фото и видео»:
     *  - создание (editingRecipeId == null): rvMedia/кнопки скрыты,
     *    tvMediaHint показывает «Сначала сохраните рецепт…»;
     *  - редактирование: кнопки видны всегда, rvMedia — если есть медиа,
     *    tvMediaHint показывает «Нет фото и видео» или скрыт.
     */
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
            binding.etTitle.setText(recipe.title)
            binding.etDescription.setText(recipe.description ?: "")
            Logger.log(TAG, "loadRecipeForEdit: loaded '${recipe.title}', source=${recipe.source}")
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
                    // Не удалось записать в БД — файл уже сохранён, почистим.
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

                // Локальные файлы удаляем сразу — UI не должен показывать
                // превью того, чего уже нет в рецепте.
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

        val description = binding.etDescription.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }

        saving = true
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            try {
                val recipeId = editingRecipeId
                val ok: Boolean
                val resultId: String?

                if (recipeId == null) {
                    // Создание
                    val created = withContext(Dispatchers.IO) {
                        repository.createRecipe(
                            title = title,
                            description = description,
                            source = source,
                            currentUser = currentUser
                        )
                    }
                    if (created == null) {
                        ok = false
                        resultId = null
                    } else {
                        ok = true
                        resultId = created.id
                        // 🆕 1.5-2: после создания переходим в режим редактирования,
                        // чтобы пользователь сразу мог добавить фото/видео.
                        // Для этого НЕ закрываем Activity, а перезапускаем её же
                        // с EXTRA_RECIPE_ID. Результат RESULT_OK отдадим при
                        // следующем закрытии.
                        if (ok && resultId != null) {
                            Logger.log(
                                TAG,
                                "onSaveClicked: created id=$resultId, reopening in EDIT mode"
                            )
                            withContext(Dispatchers.Main) {
                                reopenInEditMode(resultId)
                            }
                            return@launch
                        }
                    }
                } else {
                    // Редактирование
                    ok = withContext(Dispatchers.IO) {
                        repository.updateRecipe(
                            recipeId = recipeId,
                            newTitle = title,
                            newDescription = description,
                            currentUser = currentUser
                        )
                    }
                    resultId = recipeId
                }

                saving = false
                binding.btnSave.isEnabled = true

                if (ok && resultId != null) {
                    Logger.log(TAG, "onSaveClicked: OK, recipeId=$resultId")
                    val result = android.content.Intent().apply {
                        putExtra(EXTRA_RECIPE_ID, resultId)
                    }
                    setResult(RESULT_OK, result)
                    finish()
                } else {
                    Logger.log(TAG, "onSaveClicked: failed (ok=$ok, resultId=$resultId)")
                    Toast.makeText(
                        this@RecipeEditActivity,
                        "Не удалось сохранить рецепт",
                        Toast.LENGTH_SHORT
                    ).show()
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

    /**
     * После создания рецепта — перезапускаем Activity в режиме
     * редактирования, чтобы пользователь сразу мог добавить фото/видео.
     *
     * Реализовано как startActivity + finish (без relaunch Activity).
     * RESULT_OK от новой Activity вернётся вызывающему (RecipeListActivity)
     * через editLauncher в RecipeListActivity — потому что startActivityForResult
     * была запущена именно оттуда.
     *
     * НО: чтобы RESULT_OK дошёл до RecipeListActivity, нам нужно,
     * чтобы новая Activity запускалась «на место» старой. Используем
     * startActivity с FLAG_ACTIVITY_FORWARD_RESULT — тогда результат
     * новой Activity уйдёт в исходный launcher (editLauncher в RecipeListActivity).
     */
    private fun reopenInEditMode(recipeId: String) {
        val intent = android.content.Intent(this, RecipeEditActivity::class.java).apply {
            putExtra(EXTRA_RECIPE_ID, recipeId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        }
        startActivity(intent)
        finish()
    }

    // ============================================================
    // ПОДТВЕРЖДЕНИЕ ВЫХОДА
    // ============================================================

    /**
     * Если пользователь изменил название или описание — спросим,
     * точно ли он хочет уйти без сохранения. Иначе просто finish().
     *
     * Медиа (фото/видео) сохраняются сразу при добавлении, поэтому
     * «несохранённых медиа» не бывает — их не проверяем.
     */
    private fun confirmDiscardIfChanged() {
        if (saving) return

        val titleNow = binding.etTitle.text?.toString()?.trim().orEmpty()
        val descNow = binding.etDescription.text?.toString()?.trim().orEmpty()

        if (titleNow.isEmpty() && descNow.isEmpty()) {
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
