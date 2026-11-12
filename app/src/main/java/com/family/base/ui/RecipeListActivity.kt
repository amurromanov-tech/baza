package com.family.base.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.databinding.ActivityRecipeListBinding
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * 🆕 v21.1.0 (Шаг 1.3): экран «Рецепты».
 *
 * На этом шаге — только каркас:
 *  - MaterialToolbar с заголовком и кнопкой «назад»;
 *  - пустое состояние (emptyState) с разными текстами для «Рецептов ИИ»
 *    и «Моих рецептов»;
 *  - RecyclerView и FAB «+» пока скрыты — реальный список и форма
 *    добавления появятся на Шаге 1.4.
 *
 * Запускается из MainActivity с одним из extra:
 *  - RecipeListActivity.EXTRA_SOURCE = RecipeEntity.SOURCE_AI   → «🤖 Рецепты ИИ»
 *  - RecipeListActivity.EXTRA_SOURCE = RecipeEntity.SOURCE_USER → «📝 Мои рецепты»
 *  - RecipeListActivity.EXTRA_SOURCE = EXTRA_SOURCE_ALL         → «🍳 Все рецепты»
 *    (для корневой папки «Рецепты» — на будущее, пока не используется)
 *
 * БД не трогаем на этом шаге, кроме одного COUNT — чтобы понять,
 * есть ли уже рецепты (для текста пустого состояния). Реальный
 * список будет на Шаге 1.4.
 */
class RecipeListActivity : AppCompatActivity() {

    companion object {
        const val TAG = "RecipeListActivity"

        const val EXTRA_SOURCE = "recipe_source"

        /** Значение extra для «все рецепты» (корневая папка «🍳 Рецепты»). */
        const val EXTRA_SOURCE_ALL = "all"
    }

    private lateinit var binding: ActivityRecipeListBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    /** Источник рецептов: "ai" | "user" | "all". */
    private var source: String = EXTRA_SOURCE_ALL

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
        binding.toolbar.title = title.text
    }

    /**
     * Настраивает тексты пустого состояния в зависимости от source.
     * Сам emptyState показывается в loadCount() — если рецептов нет.
     */
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
                "Скоро здесь можно будет добавлять свои рецепты:\nназвание, описание, фото и видео.\n(Появится на Шаге 1.4)"
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

    /**
     * Один COUNT, чтобы понять, есть ли уже рецепты в выбранном source.
     *
     * На Шаге 1.3 адаптера ещё нет, поэтому:
     *  - если count == 0 → показываем emptyState (это норма сейчас);
     *  - если count > 0   → показываем заглушку «Загрузка…» (не пусто,
     *    но и списка пока нет). На Шаге 1.4 этот метод заменится на
     *    реальную загрузку списка через RecipeViewModel/RecipesRepository.
     */
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
                    // На Шаге 1.4 здесь будет реальный список.
                    // Пока рецептов нет — этот бранч практически не сработает.
                    showEmptyState(
                        overrideTitle = "Загрузка…",
                        overrideSubtitle = "Рецепты есть ($count), но список появится на следующем шаге."
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

    /**
     * Локальный enum — чтобы не тащить строки в strings.xml ради трёх
     * заголовков. Если позже понадобится локализация — вынесем.
     */
    private enum class FolderTitle(val text: String) {
        AI("🤖 Рецепты ИИ"),
        MY("📝 Мои рецепты"),
        ALL("🍳 Рецепты")
    }
}
