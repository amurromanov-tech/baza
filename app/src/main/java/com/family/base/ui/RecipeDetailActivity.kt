package com.family.base.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.AppUser
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeFolderEntity
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.data.repository.RecipesRepository
import com.family.base.databinding.ActivityRecipeDetailBinding
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

class RecipeDetailActivity : AppCompatActivity() {

    companion object {
        const val TAG = "RecipeDetailActivity"
        const val EXTRA_RECIPE_ID = "recipe_id"
        const val EXTRA_RESULT_CHANGED = "result_changed"
    }

    private lateinit var binding: ActivityRecipeDetailBinding
    private lateinit var repository: RecipesRepository
    private lateinit var tokenStorage: TokenStorage

    private var recipeId: String? = null
    private var recipe: RecipeEntity? = null
    private var changed = false
    private var currentFolderPath: String? = null

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
        loadRecipe()
        loadMedia()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "🍳 Рецепт"

        binding.toolbar.setNavigationOnClickListener {
            Logger.log(TAG, "toolbar up clicked")
            finishWithResultIfChanged()
        }
    }

    private fun setupButtons() {
        binding.btnActionEdit.setOnClickListener {
            val r = recipe
            if (r == null) {
                Toast.makeText(this, "Рецепт не загружен", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openEdit(r.id)
        }

        binding.btnActionMove.setOnClickListener {
            val r = recipe
            if (r == null) {
                Toast.makeText(this, "Рецепт не загружен", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showMoveToFolderDialog(r)
        }

        binding.btnActionDelete.setOnClickListener {
            val r = recipe
            if (r == null) {
                Toast.makeText(this, "Рецепт не загружен", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!canDeleteRecipe(r, currentUser)) {
                Toast.makeText(this, "Удалять может только автор или Алексей", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmDelete(r)
        }
    }

    // ============================================================
    // ЗАГРУЗКА И ОТОБРАЖЕНИЕ МЕДИА (сетка)
    // ============================================================

        private fun loadMedia() {
        val rid = recipeId ?: return
        lifecycleScope.launch {
            val allMedia = withContext(Dispatchers.IO) {
                try {
                    repository.getAllMedia(rid)
                } catch (e: Exception) {
                    Logger.log(TAG, "loadMedia error: ${e.message}", e)
                    emptyList()
                }
            }

            // Разделяем на фото и видео для отображения
            val photos = allMedia.filter { it.mediaType == RecipeMediaEntity.TYPE_PHOTO }
            val videos = allMedia.filter { it.mediaType == RecipeMediaEntity.TYPE_VIDEO }

            Logger.log(TAG, "loadMedia: total=${allMedia.size}, photos=${photos.size}, videos=${videos.size}")
            populateMediaGrid(photos, videos)
        }
    }

    private fun populateMediaGrid(photos: List<RecipeMediaEntity>, videos: List<RecipeMediaEntity>) {
        binding.mediaGrid.removeAllViews()
        binding.mediaGrid.visibility = if (photos.isEmpty() && videos.isEmpty()) View.GONE else View.VISIBLE

        val rid = recipeId ?: return

        photos.forEach { media ->
            val fileName = media.imageUrl
            if (!fileName.isNullOrBlank()) {
                val file = ImageUtils.getRecipeMediaFile(this, rid, fileName)
                if (file.exists()) {
                    val imageView = ImageView(this).apply {
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = 0
                            height = 0
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            setMargins(4, 4, 4, 4)
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageURI(Uri.fromFile(file))
                        setOnClickListener { openPhotoFullscreen(media) }
                        setOnLongClickListener { onMediaLongClick(media); true }
                    }
                    binding.mediaGrid.addView(imageView)
                }
            }
        }

        videos.forEach { media ->
            val fileName = media.imageUrl
            if (!fileName.isNullOrBlank()) {
                val file = ImageUtils.getRecipeMediaFile(this, rid, fileName)
                if (file.exists()) {
                    val frameLayout = FrameLayout(this).apply {
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = 0
                            height = 0
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            setMargins(4, 4, 4, 4)
                        }
                        setOnClickListener { openVideo(media) }
                        setOnLongClickListener { onMediaLongClick(media); true }
                    }

                    val imageView = ImageView(this).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageURI(Uri.fromFile(file))
                    }
                    frameLayout.addView(imageView)

                    val playIcon = TextView(this).apply {
                        text = "▶"
                        textSize = 32f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(Color.parseColor("#44000000"))
                    }
                    frameLayout.addView(playIcon)

                    binding.mediaGrid.addView(frameLayout)
                }
            }
        }
    }

    private fun openPhotoFullscreen(media: RecipeMediaEntity) {
        val rid = recipeId ?: return
        val fileName = media.imageUrl
        if (fileName.isNullOrBlank()) {
            Toast.makeText(this, "Файл фото не найден", Toast.LENGTH_SHORT).show()
            return
        }
        val file = ImageUtils.getRecipeMediaFile(this, rid, fileName)
        if (!file.exists()) {
            Toast.makeText(this, "Файл фото не найден", Toast.LENGTH_SHORT).show()
            return
        }
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
        val file = ImageUtils.getRecipeMediaFile(this, rid, fileName)
        if (!file.exists()) {
            Toast.makeText(this, "Файл видео не найден", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_URL, Uri.fromFile(file).toString())
            putExtra(VideoPlayerActivity.EXTRA_TITLE, recipe?.title ?: "Видео рецепта")
            if (!media.mimeType.isNullOrBlank()) {
                putExtra(VideoPlayerActivity.EXTRA_MIME, media.mimeType)
            }
        }
        startActivity(intent)
    }

    private fun onMediaLongClick(media: RecipeMediaEntity) {
        val canDelete = canDeleteMedia(media, currentUser)
        if (!canDelete) {
            Toast.makeText(this, "Удалять можно только свои файлы", Toast.LENGTH_SHORT).show()
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
                try { repository.deleteMedia(media.id, currentUser) } catch (e: Exception) { false }
            }
            if (!okDb) {
                Toast.makeText(this@RecipeDetailActivity, "Не удалось удалить файл", Toast.LENGTH_SHORT).show()
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
            changed = true
            loadMedia()
        }
    }

    // ============================================================
    // ЗАГРУЗКА РЕЦЕПТА
    // ============================================================

    private fun loadRecipe() {
        val id = recipeId ?: return
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try { repository.getRecipeById(id) } catch (e: Exception) { null }
            }
            if (loaded == null) {
                Toast.makeText(this@RecipeDetailActivity, "Рецепт не найден", Toast.LENGTH_SHORT).show()
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

        // Загружаем путь
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    var current = r.folderId
                    if (current == null) return@withContext "📂 Корень / Мои рецепты"
                    val parts = mutableListOf<String>()
                    while (current != null) {
                        val folder = AppDatabase.getInstance(this@RecipeDetailActivity).recipeFolderDao().getLiveFolderById(current)
                        if (folder != null) {
                            parts.add(0, folder.name)
                            current = folder.parentId
                        } else {
                            break
                        }
                    }
                    "📂 Корень / Мои рецепты / ${parts.joinToString(" / ")}"
                } catch (e: Exception) {
                    "📂 Корень / Мои рецепты"
                }
            }
            binding.tvPath.text = path
        }

        val sourceLabel = when (r.source) {
            RecipeEntity.SOURCE_AI -> "🤖 Рецепт ИИ"
            RecipeEntity.SOURCE_USER -> "📝 Мой рецепт"
            else -> "🍳 Рецепт"
        }
        val created = try { dateFormat.format(Date(r.createdAt)) } catch (e: Exception) { "" }
        val author = r.createdBy.ifBlank { "—" }
        binding.tvMeta.text = buildString {
            append(sourceLabel)
            append(" · автор: ").append(author)
            if (created.isNotEmpty()) append(" · ").append(created)
        }

        val canDelete = canDeleteRecipe(r, currentUser)
        binding.btnActionDelete.alpha = if (canDelete) 1f else 0.4f
        binding.btnActionMove.visibility = if (r.source == RecipeEntity.SOURCE_USER) View.VISIBLE else View.GONE
    }

    private fun canDeleteRecipe(r: RecipeEntity, user: String): Boolean {
        return AppUser.canDelete(user) || r.createdBy == user
    }

    private fun canDeleteMedia(media: RecipeMediaEntity, user: String): Boolean {
        return AppUser.canDelete(user) || media.createdBy == user
    }

    // ============================================================
    // ПЕРЕМЕЩЕНИЕ В ПАПКУ
    // ============================================================

    private fun showMoveToFolderDialog(recipe: RecipeEntity) {
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) {
                try { AppDatabase.getInstance(applicationContext).recipeFolderDao().getAllLive() }
                catch (e: Exception) { emptyList() }
            }
            if (folders.isEmpty()) {
                Toast.makeText(this@RecipeDetailActivity, "Нет папок", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val ordered = buildFolderHierarchy(folders)
            val labels = ArrayList<String>()
            val ids = ArrayList<String?>()
            labels.add("📁 Без папки (корень)")
            ids.add(null)
            ordered.forEach { (folder, depth) ->
                val indent = if (depth > 0) "— ".repeat(depth) else ""
                labels.add("$indent${folder.name}")
                ids.add(folder.id)
            }
            AlertDialog.Builder(this@RecipeDetailActivity)
                .setTitle("📁 Переместить в папку")
                .setItems(labels.toTypedArray()) { _, which ->
                    val targetId = ids[which]
                    if (targetId == recipe.folderId) {
                        Toast.makeText(this@RecipeDetailActivity, "Уже в этой папке", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                    viewModelMove(recipe.id, targetId)
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun viewModelMove(recipeId: String, targetFolderId: String?) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { repository.moveRecipeToFolder(recipeId, targetFolderId, currentUser) }
                catch (e: Exception) { false }
            }
            if (ok) {
                Toast.makeText(this@RecipeDetailActivity, "Перемещено", Toast.LENGTH_SHORT).show()
                changed = true
                loadRecipe()
            } else {
                Toast.makeText(this@RecipeDetailActivity, "Ошибка перемещения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildFolderHierarchy(all: List<RecipeFolderEntity>): List<Pair<RecipeFolderEntity, Int>> {
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

    // ============================================================
    // РЕДАКТИРОВАНИЕ / УДАЛЕНИЕ РЕЦЕПТА
    // ============================================================

    private fun openEdit(id: String) {
        val intent = Intent(this, RecipeEditActivity::class.java).apply {
            putExtra(RecipeEditActivity.EXTRA_RECIPE_ID, id)
        }
        editLauncher.launch(intent)
    }

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
                try { repository.deleteRecipe(r.id, currentUser) } catch (e: Exception) { false }
            }
            if (ok) {
                Toast.makeText(this@RecipeDetailActivity, "Рецепт удалён", Toast.LENGTH_SHORT).show()
                changed = true
                finishWithResultIfChanged()
            } else {
                Toast.makeText(this@RecipeDetailActivity, "Не удалось удалить", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun finishWithResultIfChanged() {
        if (changed) {
            setResult(RESULT_OK, Intent().apply { putExtra(EXTRA_RESULT_CHANGED, true) })
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
