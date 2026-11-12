package com.family.base.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.repository.RecipesRepository
import com.family.base.databinding.ActivityRecipeEditBinding
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🆕 v21.1.0 (Шаг 1.4a): создание / редактирование рецепта (без медиа).
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
 * Сохранение:
 *  - Создание: RecipesRepository.createRecipe(...) → RESULT_OK + EXTRA_RECIPE_ID.
 *  - Редактирование: RecipesRepository.updateRecipe(...) → RESULT_OK + EXTRA_RECIPE_ID.
 *  - Ошибка: Toast + остаёмся на экране (не закрываемся, чтобы не потерять ввод).
 *
 * Фото/видео — на Шаге 1.5. Сейчас — только текст.
 *
 * Возврат: setResult(RESULT_OK, intentWithExtra(EXTRA_RECIPE_ID, id)).
 * RecipeListActivity по возврату перезагрузит список.
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

    private val currentUser: String
        get() = tokenStorage.getCurrentUser()
            ?: tokenStorage.getUserDisplayName()
            ?: "Пользователь"

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

        if (editingRecipeId != null) {
            loadRecipeForEdit(editingRecipeId!!)
        }
    }

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

    /**
     * Загрузка рецепта для редактирования.
     * Если рецепта нет (удалён параллельно) — Toast и finish.
     */
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

    private fun onSaveClicked() {
        val title = binding.etTitle.text?.toString()?.trim().orEmpty()
        if (TextUtils.isEmpty(title)) {
            binding.tilTitle.error = "Введите название"
            binding.etTitle.requestFocus()
            return
        }
        binding.tilTitle.error = null

        val description = binding.etDescription.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }

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
     * Если пользователь изменил название или описание — спросим,
     * точно ли он хочет уйти без сохранения. Иначе просто finish().
     */
    private fun confirmDiscardIfChanged() {
        val titleNow = binding.etTitle.text?.toString()?.trim().orEmpty()
        val descNow = binding.etDescription.text?.toString()?.trim().orEmpty()

        if (titleNow.isEmpty() && descNow.isEmpty()) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Закрыть без сохранения?")
            .setMessage("Введённые изменения не будут сохранены.")
            .setPositiveButton("Закрыть") { _, _ -> finish() }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
