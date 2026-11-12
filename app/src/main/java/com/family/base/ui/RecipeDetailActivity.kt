package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.AppUser
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.repository.RecipesRepository
import com.family.base.databinding.ActivityRecipeDetailBinding
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🆕 v21.1.0 (Шаг 1.4b-2): экран просмотра рецепта.
 *
 * Показывает:
 *  - название, описание, мета (источник + дата создания);
 *  - кнопки «✏️ Редактировать» и «🗑️ Удалить».
 *  - Плейсхолдер под фото/видео — реальные галереи на Шаге 1.5.
 *
 * ПРАВА НА УДАЛЕНИЕ (по просьбе пользователя, отличается от предметов/папок):
 *  - Алексей        — может удалять ЛЮБОЙ рецепт;
 *  - Рима / Дима    — только СВОИ (recipe.createdBy == currentUser).
 *  Технически: canDelete = AppUser.canDelete(currentUser) || recipe.createdBy == currentUser.
 *  (AppUser.canDelete(name) == true только для Алексея — оставляем как есть,
 *   для предметов/папок правило не меняется.)
 *
 * Принимает EXTRA_RECIPE_ID (обязателен).
 *
 * Возврат в RecipeListActivity:
 *  - RESULT_OK + EXTRA_RESULT_CHANGED = true, если рецепт изменился/удалён.
 *  - RESULT_CANCELED — если ничего не менялось (просто посмотрели).
 *  RecipeListActivity по RESULT_OK перезагрузит список.
 *
 * Редактирование открывает RecipeEditActivity с EXTRA_RECIPE_ID (режим редактирования).
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
    // ЗАГРУЗКА
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
        // Название
        binding.tvTitle.text = r.title

        // Описание
        val desc = r.description?.trim()
        if (desc.isNullOrEmpty()) {
            binding.tvDescription.visibility = View.GONE
        } else {
            binding.tvDescription.visibility = View.VISIBLE
            binding.tvDescription.text = desc
        }

        // Мета: источник + автор + дата создания
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

        // Кнопки: удаление только если (админ) ИЛИ (автор).
        val canDelete = canDeleteRecipe(r, currentUser)
        binding.btnDelete.visibility = if (canDelete) View.VISIBLE else View.GONE

        // Редактировать разрешаем всем — правка не удаляет данные.
        // (Если хочешь ограничить правку только автором — скажи, поменяю.)
        binding.btnEdit.visibility = View.VISIBLE

        Logger.log(
            TAG,
            "bindRecipe: id=${r.id}, source=${r.source}, createdBy=${r.createdBy}, canDelete=$canDelete"
        )
    }

    /**
     * Может ли текущий пользователь удалить этот рецепт?
     *  - Алексей — всегда да (AppUser.canDelete == true);
     *  - Рима / Дима — только свои (createdBy == currentUser).
     */
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
    // УДАЛЕНИЕ
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
