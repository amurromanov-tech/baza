package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.databinding.ActivityRecipeListBinding
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🆕 v21.1.0 (Шаг 1.3 + 1.4a): экран «Рецепты».
 *
 * Что делает на этом шаге:
 *  - Принимает EXTRA_SOURCE ("ai" | "user" | "all"), ставит заголовок;
 *  - Показывает пустое состояние с разными текстами;
 *  - FAB «+» (для "ai" / "user") открывает RecipeEditActivity
 *    в режиме создания с нужным EXTRA_SOURCE;
 *  - Тап по пустому состоянию (для "ai" / "user") — то же самое;
 *  - По возврату из RecipeEditActivity перезагружает COUNT.
 *
 * ВАЖНО (после жалобы пользователя):
 *  - Заголовок ставим через supportActionBar?.title = ..., а НЕ через
 *    binding.toolbar.title = ... Иначе ActionBar перетирает его
 *    обратно на android:label из манифеста (= "BAZA"), и пользователь
 *    видит "BAZA" вместо "🍳 Рецепты".
 *  - Корневая «🍳 Рецепты» (source = "all") больше НЕ открывает
 *    этот экран — она открывается как обычная папка через
 *    MainActivity.navigateToFolder, чтобы пользователь видел
 *    две подпапки «🤖 Рецепты ИИ» и «📝 Мои рецепты» внутри.
 *    Этот экран используется только для source = "ai" / "user".
 */
class RecipeListActivity : AppCompatActivity() {

    companion object {
        const val TAG = "RecipeListActivity"

        const val EXTRA_SOURCE = "recipe_source"

        const val EXTRA_SOURCE_ALL = "all"
    }

    private lateinit var binding: ActivityRecipeListBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    private var source: String = EXTRA_SOURCE_ALL

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.log(TAG, "editLauncher result: ${result.resultCode}")
        loadCount()
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

        setupToolbar()
        setupEmptyState()
        setupFab()
        loadCount()
    }

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
        // Иначе ActionBar перетирает на android:label ("BAZA").
        supportActionBar?.title = title.text

        // Поиск — Шаг 1.4b. Пока btnSearch скрыт в layout.
    }

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

    private fun loadCount() {
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    when (source) {
                        RecipeEntity.SOURCE_AI -> db.recipeDao().countBySource(RecipeEntity.SOURCE_AI)
                        RecipeEntity.SOURCE_USER -> db.recipeDao().countBySource(RecipeEntity.SOURCE_USER)
                        else -> db.recipeDao().getAllNewest().size
                    }
                }
                Logger.log(TAG, "loadCount: source=$source, count=$count")

                if (count <= 0) {
                    showEmptyState()
                } else {
                    showEmptyState(
                        overrideTitle = "Загрузка…",
                        overrideSubtitle = "Рецепты есть ($count), но список появится на Шаге 1.4b."
                    )
                }
            } catch (e: Exception) {
                Logger.log(TAG, "loadCount error: ${e.message}", e)
                showEmptyState()
            }
        }
    }

    private fun showEmptyState(
        overrideTitle: String? = null,
        overrideSubtitle: String? = null
    ) {
        binding.emptyState.visibility = View.VISIBLE
        binding.rvRecipes.visibility = View.GONE
        if (overrideTitle != null) binding.tvEmptyTitle.text = overrideTitle
        if (overrideSubtitle != null) binding.tvEmptySubtitle.text = overrideSubtitle
    }

    private enum class FolderTitle(val text: String) {
        AI("🤖 Рецепты ИИ"),
        MY("📝 Мои рецепты"),
        ALL("🍳 Рецепты")
    }
}
