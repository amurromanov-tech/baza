package com.family.base.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.base.AppUser
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.data.repository.RecipesRepository
import com.family.base.databinding.ActivityRecipeDetailBinding
import com.family.base.ui.adapter.RecipePhotoAdapter
import com.family.base.ui.adapter.RecipeVideoAdapter
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import com.family.base.util.RecipeMediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🆕 v21.1.0 (Шаг 1.4b-2 + 1.5-3): экран просмотра рецепта.
 *
 * Показывает:
 *  - название, описание, мета (источник + автор + дата создания);
 *  - 🆕 галерею ФОТО (горизонтальный RecyclerView, клик → FullscreenImageActivity);
 *  - 🆕 галерею ВИДЕО (горизонтальный RecyclerView, клик → VideoPlayerActivity);
 *  - кнопки «✏️ Редактировать» и «🗑️ Удалить».
 *
 * ПРАВА НА УДАЛЕНИЕ (по просьбе пользователя, отличается от предметов/папок):
 *  - Алексей        — может удалять ЛЮБОЙ рецепт и ЛЮБОЕ его медиа;
 *  - Рима / Дима    — только СВОИ (recipe.createdBy == currentUser)
 *                     и своё медиа (media.createdBy == currentUser).
 *
 * УДАЛЕНИЕ МЕДИА:
 *  - Долгий клик по фото/видео → диалог «Удалить файл?».
 *  - При удалении: RecipesRepository.deleteMedia (tombstone) +
 *    RecipeMediaController.deleteMediaFiles (локальный файл).
 *  - Синк на Диск — Шаг 1.6 (tombstone уедет через outbox).
 *
 * ВСЁ РАБОТАЕТ ОФЛАЙН: файлы читаются из filesDir/recipe_media/<recipeId>/.
 *
 * Принимает EXTRA_RECIPE_ID (обязателен).
 *
 * Возврат в RecipeListActivity:
 *  - RESULT_OK + EXTRA_RESULT_CHANGED = true, если рецепт изменился/удалён.
 *  - RESULT_CANCELED — если ничего не менялось.
 */
class RecipeDetailActivity : AppCompatActivity() {

    companion object {
        const val TAG = "RecipeDetailActivity"

        const val EXTRA_RECIPE_ID = "recipe_id"

        /** true → в RecipeListActivity нужно перезагрузить список. */
        const val EXTRA_RESULT_CHANGED = "result_changed"
    }

    private lateinit var binding: ActivityRecipeDetailBinding
    private lateinit var repository: RecipesRepository
    private lateinit var tokenStorage: TokenStorage

    private var recipeId: String? = null
    private var recipe: RecipeEntity? = null

    /** Был ли рецепт изменён/удалён (для setResult). */
    private var changed = false

    private lateinit var photoAdapter: RecipePhotoAdapter
    private lateinit var videoAdapter: RecipeVideoAdapter

    private val currentUser: String
        get() = tokenStorage.getCurrentUser()
            ?: tokenStorage.getUserDisplayName()
            ?: "Пользователь"

    private val dateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    }

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.log(TAG, "editLauncher result: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            changed = true
            loadRecipe()
            loadMedia()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== RecipeDetailActivity onCreate START ===")

        try {
            binding = ActivityRecipeDetailBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        val db = AppDatabase.getInstance(this)
        repository = RecipesRepository(db, applicationContext)
        tokenStorage = TokenStorage(this)

        recipeId = intent.getStringExtra(EXTRA_RECIPE_ID)
        if (recipeId.isNullOrBlank()) {
            Logger.log(TAG, "onCreate: no recipeId, finish")
            Toast.makeText(this, "Рецепт не найден", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Logger.log(TAG, "onCreate: recipeId=$recipeId, currentUser=$currentUser")

        setupToolbar()
        setupButtons()
        setupMediaGalleries()
        loadRecipe()
        loadMedia()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // ВАЖНО: через supportActionBar?.title, не binding.toolbar.title.
        supportActionBar?.title = "🍳 Рецепт"

        binding.toolbar.setNavigationOnClickListener {
            Logger.log(TAG, "toolbar up clicked")
            finishWithResultIfChanged()
        }
    }

    private fun setupButtons() {
        binding.btnEdit.setOnClickListener {
            val r = recipe
            if (r == null) {
                Toast.makeText(this, "Рецепт не загружен", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openEdit(r.id)
        }

        binding.btnDelete.setOnClickListener {
            val r = recipe
            if (r == null) {
                Toast.makeText(this, "Рецепт не загружен", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmDelete(r)
        }
    }

    // ============================================================
    // ГАЛЕРЕИ ФОТО / ВИДЕО (🆕 1.5-3)
    // ============================================================

    private fun setupMediaGalleries() {
        val rid = recipeId ?: ""

        photoAdapter = RecipePhotoAdapter(
            recipeId = rid,
            onPhotoClick = { media, _ -> openPhotoFullscreen(media) },
            onPhotoLongClick = { media -> onMediaLongClick(media) }
        )

        videoAdapter = RecipeVideoAdapter(
            recipeId = rid,
            onVideoClick = { media -> openVideo(media) },
            onVideoLongClick = { media -> onMediaLongClick(media) }
        )

        binding.rvPhotos.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.rvPhotos.adapter = photoAdapter

        binding.rvVideos.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.rvVideos.adapter = videoAdapter
    }

    private fun loadMedia() {
        val rid = recipeId ?: return
        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) {
                try {
                    repository.getPhotos(rid)
                } catch (e: Exception) {
                    Logger.log(TAG, "loadMedia photos error: ${e.message}", e)
                    emptyList()
                }
            }
            val videos = withContext(Dispatchers.IO) {
                try {
                    repository.getVideos(rid)
                } catch (e: Exception) {
                    Logger.log(TAG, "loadMedia videos error: ${e.message}", e)
                    emptyList()
                }
            }

            Logger.log(TAG, "loadMedia: photos=${photos.size}, videos=${videos.size}")

            photoAdapter.submitList(photos)
            videoAdapter.submitList(videos)

            binding.photosBlock.visibility = if (photos.isNotEmpty()) View.VISIBLE else View.GONE
            binding.videosBlock.visibility = if (videos.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openPhotoFullscreen(media: RecipeMediaEntity) {
        val rid = recipeId ?: return
        val fileName = media.imageUrl
        if (fileName.isNullOrBlank()) {
            Toast.makeText(this, "Файл фото не найден", Toast.LENGTH_SHORT).show()
            return
        }
        val file: File = ImageUtils.getRecipeMediaFile(this, rid, fileName)
        if (!file.exists()) {
            Toast.makeText(this, "Файл фото не найден", Toast.LENGTH_SHORT).show()
            return
        }
        Logger.log(TAG, "openPhotoFullscreen: ${file.absolutePath}")
        val intent = Intent(this, FullscreenImageActivity::class.java).apply {
            putExtra(FullscreenImageActivity.EXTRA_IMAGE_PATH, file.absolutePath)
            putExtra(FullscreenImageActivity.EXTRA_TITLE, recipe?.title ?: "Фото рецепта")
        }
        startActivity(intent)
    }

    private fun openVideo(media: RecipeMediaEntity) {
        val rid = recipeId ?: return
        val fileName = media.imageUrl
        if (fileName.isNullOrBlank()) {
            Toast.makeText(this, "Файл видео не найден", Toast.LENGTH_SHORT).show()
            return
        }
        val file: File = ImageUtils.getRecipeMediaFile(this, rid, fileName)
        if (!file.exists()) {
            Toast.makeText(this, "Файл видео не найден", Toast.LENGTH_SHORT).show()
            return
        }
        Logger.log(TAG, "openVideo: ${file.absolutePath}, mime=${media.mimeType}")
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_URL, Uri.fromFile(file).toString())
            putExtra(VideoPlayerActivity.EXTRA_TITLE, recipe?.title ?: "Видео рецепта")
            if (!media.mimeType.isNullOrBlank()) {
                putExtra(VideoPlayerActivity.EXTRA_MIME, media.mimeType)
            }
        }
        startActivity(intent)
    }

    // ============================================================
    // УДАЛЕНИЕ МЕДИА (долгий клик)
    // ============================================================

    private fun onMediaLongClick(media: RecipeMediaEntity) {
        val canDelete = canDeleteMedia(media, currentUser)
        if (!canDelete) {
            Toast.makeText(
                this,
                "Удалять можно только свои файлы",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val what = if (media.mediaType == RecipeMediaEntity.TYPE_VIDEO) "видео" else "фото"
        AlertDialog.Builder(this)
            .setTitle("Удалить $what?")
            .setMessage("Файл будет удалён из рецепта. Действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ -> doRemoveMedia(media) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doRemoveMedia(media: RecipeMediaEntity) {
        val rid = recipeId ?: return
        lifecycleScope.launch {
            val okDb = withContext(Dispatchers.IO) {
                try {
                    repository.deleteMedia(media.id, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "doRemoveMedia error: ${e.message}", e)
                    false
                }
            }
            if (!okDb) {
                Toast.makeText(
                    this@RecipeDetailActivity,
                    "Не удалось удалить файл",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            withContext(Dispatchers.IO) {
                RecipeMediaController.deleteMediaFiles(
                    this@RecipeDetailActivity,
                    rid,
                    media.imageUrl,
                    media.thumbnailUrl
                )
            }

            Logger.log(TAG, "doRemoveMedia: removed id=${media.id}")
            changed = true
            loadMedia()
        }
    }

    /**
     * Может ли текущий пользователь удалить это медиа?
     *  - Алексей — всегда да (AppUser.canDelete == true);
     *  - Рима / Дима — только своё (media.createdBy == currentUser).
     */
    private fun canDeleteMedia(media: RecipeMediaEntity, user: String): Boolean {
        return AppUser.canDelete(user) || media.createdBy == user
    }

    // ============================================================
    // ЗАГРУЗКА РЕЦЕПТА
    // ============================================================

    private fun loadRecipe() {
        val id = recipeId ?: return
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    repository.getRecipeById(id)
                } catch (e: Exception) {
                    Logger.log(TAG, "loadRecipe error: ${e.message}", e)
                    null
                }
            }

            if (loaded == null) {
                Logger.log(TAG, "loadRecipe: recipe not found or tombstone: $id")
                Toast.makeText(
                    this@RecipeDetailActivity,
                    "Рецепт не найден",
                    Toast.LENGTH_SHORT
                ).show()
                finishWithResultIfChanged()
                return@launch
            }

            recipe = loaded
            bindRecipe(loaded)
        }
    }

    private fun bindRecipe(r: RecipeEntity) {
        binding.tvTitle.text = r.title

        val desc = r.description?.trim()
        if (desc.isNullOrEmpty()) {
            binding.tvDescription.visibility = View.GONE
        } else {
            binding.tvDescription.visibility = View.VISIBLE
            binding.tvDescription.text = desc
        }

        val sourceLabel = when (r.source) {
            RecipeEntity.SOURCE_AI -> "🤖 Рецепт ИИ"
            RecipeEntity.SOURCE_USER -> "📝 Мой рецепт"
            else -> "🍳 Рецепт"
        }
        val created = try {
            dateFormat.format(Date(r.createdAt))
        } catch (e: Exception) {
            ""
        }
        val author = r.createdBy.ifBlank { "—" }
        binding.tvMeta.text = buildString {
            append(sourceLabel)
            append(" · автор: ").append(author)
            if (created.isNotEmpty()) append(" · ").append(created)
        }

        val canDeleteRecipe = canDeleteRecipe(r, currentUser)
        binding.btnDelete.visibility = if (canDeleteRecipe) View.VISIBLE else View.GONE
        binding.btnEdit.visibility = View.VISIBLE

        Logger.log(
            TAG,
            "bindRecipe: id=${r.id}, source=${r.source}, createdBy=${r.createdBy}, canDelete=$canDeleteRecipe"
        )
    }

    private fun canDeleteRecipe(r: RecipeEntity, user: String): Boolean {
        return AppUser.canDelete(user) || r.createdBy == user
    }

    // ============================================================
    // РЕДАКТИРОВАНИЕ
    // ============================================================

    private fun openEdit(id: String) {
        Logger.log(TAG, "openEdit: id=$id")
        val intent = Intent(this, RecipeEditActivity::class.java).apply {
            putExtra(RecipeEditActivity.EXTRA_RECIPE_ID, id)
        }
        editLauncher.launch(intent)
    }

    // ============================================================
    // УДАЛЕНИЕ РЕЦЕПТА
    // ============================================================

    private fun confirmDelete(r: RecipeEntity) {
        AlertDialog.Builder(this)
            .setTitle("Удалить рецепт?")
            .setMessage("«${r.title}» будет удалён. Действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ -> doDelete(r) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doDelete(r: RecipeEntity) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    repository.deleteRecipe(r.id, currentUser)
                } catch (e: Exception) {
                    Logger.log(TAG, "doDelete error: ${e.message}", e)
                    false
                }
            }
            Logger.log(TAG, "doDelete: id=${r.id}, ok=$ok")
            if (ok) {
                Toast.makeText(
                    this@RecipeDetailActivity,
                    "Рецепт удалён",
                    Toast.LENGTH_SHORT
                ).show()
                changed = true
                finishWithResultIfChanged()
            } else {
                Toast.makeText(
                    this@RecipeDetailActivity,
                    "Не удалось удалить рецепт",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // РЕЗУЛЬТАТ
    // ============================================================

    private fun finishWithResultIfChanged() {
        if (changed) {
            val data = Intent().apply {
                putExtra(EXTRA_RESULT_CHANGED, true)
            }
            setResult(RESULT_OK, data)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
