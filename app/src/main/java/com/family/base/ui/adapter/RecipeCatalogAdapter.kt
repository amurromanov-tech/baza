package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.RecipeEntity
import com.family.base.data.local.entity.RecipeFolderEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecipeCatalogAdapter(
    private val onFolderClick: (RecipeFolderEntity) -> Unit,
    private val onRecipeClick: (RecipeEntity) -> Unit,
    private val onFolderLongClick: (RecipeFolderEntity) -> Unit,
    private val onRecipeLongClick: (RecipeEntity) -> Unit,
    // 🆕 v23.4.9: колбэки для лайк/дизлайк/приготовлено (инкремент — короткий клик)
    private val onLikeClick: (RecipeEntity) -> Unit = {},
    private val onDislikeClick: (RecipeEntity) -> Unit = {},
    private val onCookedClick: (RecipeEntity) -> Unit = {},
    // 🆕 v23.4.9 (27.11.2026): колбэки для отмены долгим нажатием (декремент)
    private val onLongLikeClick: (RecipeEntity) -> Unit = {},
    private val onLongDislikeClick: (RecipeEntity) -> Unit = {},
    private val onLongCookedClick: (RecipeEntity) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_RECIPE = 1
    }

    sealed class RecipeCatalogItem {
        data class Folder(
            val folder: RecipeFolderEntity,
            val recipesCount: Int
        ) : RecipeCatalogItem()

        data class Recipe(
            val recipe: RecipeEntity,
            val photoFile: java.io.File?
        ) : RecipeCatalogItem()
    }

    private var items: List<RecipeCatalogItem> = emptyList()

    private val dateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    }

    fun submitList(newItems: List<RecipeCatalogItem>) {
        val oldItems = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = oldItems[oldPos]
                val new = newItems[newPos]
                return when {
                    old is RecipeCatalogItem.Folder && new is RecipeCatalogItem.Folder ->
                        old.folder.id == new.folder.id
                    old is RecipeCatalogItem.Recipe && new is RecipeCatalogItem.Recipe ->
                        old.recipe.id == new.recipe.id
                    else -> false
                }
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = oldItems[oldPos]
                val new = newItems[newPos]
                return when {
                    old is RecipeCatalogItem.Folder && new is RecipeCatalogItem.Folder ->
                        old.folder.name == new.folder.name &&
                            old.folder.parentId == new.folder.parentId &&
                            old.recipesCount == new.recipesCount &&
                            old.folder.updatedAt == new.folder.updatedAt
                    old is RecipeCatalogItem.Recipe && new is RecipeCatalogItem.Recipe ->
                        old.recipe.title == new.recipe.title &&
                            old.recipe.description == new.recipe.description &&
                            old.recipe.cuisineCountry == new.recipe.cuisineCountry &&
                            old.recipe.likes == new.recipe.likes &&
                            old.recipe.dislikes == new.recipe.dislikes &&
                            old.recipe.cookedCount == new.recipe.cookedCount &&
                            old.recipe.updatedAt == new.recipe.updatedAt &&
                            old.photoFile?.absolutePath == new.photoFile?.absolutePath
                    else -> false
                }
            }
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is RecipeCatalogItem.Folder -> TYPE_FOLDER
        is RecipeCatalogItem.Recipe -> TYPE_RECIPE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_FOLDER -> FolderViewHolder(
                inflater.inflate(R.layout.item_recipe_folder, parent, false)
            )
            else -> RecipeViewHolder(
                inflater.inflate(R.layout.item_recipe, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RecipeCatalogItem.Folder -> (holder as FolderViewHolder).bind(item)
            is RecipeCatalogItem.Recipe -> (holder as RecipeViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val colorBar: View = view.findViewById(R.id.colorBar)
        private val ivFolderIcon: ImageView = view.findViewById(R.id.ivFolderIcon)
        private val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
        private val tvFolderMeta: TextView = view.findViewById(R.id.tvFolderMeta)

        fun bind(item: RecipeCatalogItem.Folder) {
            val folder = item.folder
            val context = itemView.context
            colorBar.setBackgroundColor(
                context.resources.getColor(R.color.colorPrimary, context.theme)
            )
            ivFolderIcon.load(R.drawable.ic_folder_default) {
                placeholder(R.drawable.ic_folder_default)
                error(R.drawable.ic_folder_default)
            }
            tvFolderName.text = folder.name
            tvFolderMeta.text = when {
                item.recipesCount <= 0 -> "📁 Пусто"
                else -> "📁 ${item.recipesCount} ${pluralRecipes(item.recipesCount)}"
            }
            itemView.setOnClickListener { onFolderClick(folder) }
            itemView.setOnLongClickListener { onFolderLongClick(folder); true }
        }
    }

    inner class RecipeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val colorBar: View = view.findViewById(R.id.colorBar)
        private val ivRecipePhoto: ImageView = view.findViewById(R.id.ivRecipePhoto)
        private val tvRecipeTitle: TextView = view.findViewById(R.id.tvRecipeTitle)
        // 🆕 v23.4.9: страна кухни
        private val tvRecipeCountry: TextView = view.findViewById(R.id.tvRecipeCountry)
        private val tvRecipeDescription: TextView = view.findViewById(R.id.tvRecipeDescription)
        private val tvRecipeMeta: TextView = view.findViewById(R.id.tvRecipeMeta)
        // 🆕 v23.4.9: кнопки лайк/дизлайк/приготовлено
        private val btnLike: TextView = view.findViewById(R.id.btnLike)
        private val btnDislike: TextView = view.findViewById(R.id.btnDislike)
        private val btnCooked: TextView = view.findViewById(R.id.btnCooked)

        fun bind(item: RecipeCatalogItem.Recipe) {
            val recipe = item.recipe
            val context = itemView.context
            val colorRes = if (recipe.source == RecipeEntity.SOURCE_AI) {
                R.color.colorWarning
            } else {
                R.color.colorNormal
            }
            colorBar.setBackgroundColor(context.resources.getColor(colorRes, context.theme))
            if (item.photoFile != null && item.photoFile.exists()) {
                ivRecipePhoto.load(item.photoFile) {
                    crossfade(true)
                    placeholder(R.drawable.ic_item_default)
                    error(R.drawable.ic_item_default)
                }
            } else {
                ivRecipePhoto.load(R.drawable.ic_item_default)
            }
            tvRecipeTitle.text = recipe.title

            // 🆕 v23.4.9: страна кухни
            val country = recipe.cuisineCountry.trim()
            if (country.isEmpty()) {
                tvRecipeCountry.visibility = View.GONE
            } else {
                tvRecipeCountry.visibility = View.VISIBLE
                tvRecipeCountry.text = "🌍 $country"
            }

            val desc = recipe.description
            if (desc.isNullOrBlank()) {
                tvRecipeDescription.visibility = View.GONE
            } else {
                tvRecipeDescription.visibility = View.VISIBLE
                tvRecipeDescription.text = desc
            }
            val sourceLabel = if (recipe.source == RecipeEntity.SOURCE_AI) "🤖 ИИ" else "📝 Мои"
            val dateStr = try {
                dateFormat.format(Date(recipe.createdAt))
            } catch (e: Exception) {
                ""
            }
            tvRecipeMeta.text = if (dateStr.isEmpty()) sourceLabel else "$sourceLabel · $dateStr"

            // 🆕 v23.4.9: счётчики лайков/дизлайков/приготовлений
            btnLike.text = "👍 ${recipe.likes}"
            btnDislike.text = "👎 ${recipe.dislikes}"
            btnCooked.text = "🍳 ${recipe.cookedCount}"

            // 🆕 v23.4.9: обработчики короткого клика на кнопки (+1)
            btnLike.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onLikeClick(recipe)
            }
            btnDislike.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onDislikeClick(recipe)
            }
            btnCooked.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onCookedClick(recipe)
            }

            // 🆕 v23.4.9 (27.11.2026): обработчики долгого нажатия = -1 (отмена)
            // Защита от многократного срабатывания: setOnLongClickListener срабатывает
            // ОДИН РАЗ за одно долгое нажатие. Пока палец держится — не срабатывает повторно.
            // Отпустил палец → новое долгое нажатие = ещё один -1.
            btnLike.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongLikeClick(recipe)
                true // true — предотвращает всплытие события к itemView
            }
            btnDislike.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongDislikeClick(recipe)
                true
            }
            btnCooked.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongCookedClick(recipe)
                true
            }

            itemView.setOnClickListener { onRecipeClick(recipe) }
            itemView.setOnLongClickListener { onRecipeLongClick(recipe); true }
        }
    }

    private fun pluralRecipes(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "рецепт"
            mod10 in 2..4 && mod100 !in 12..14 -> "рецепта"
            else -> "рецептов"
        }
    }
}

// ============================================================
// 🆕 v23.4.9 (27.11.2026) — кухня мира + рейтинг + отмена долгим нажатием
// Изменения:
//   - Добавлены 3 новых колбэка в конструктор (с дефолтами = {}):
//     * onLongLikeClick(recipe) — долгое нажатие на 👍 (отмена, -1)
//     * onLongDislikeClick(recipe) — долгое нажатие на 👎 (отмена, -1)
//     * onLongCookedClick(recipe) — долгое нажатие на 🍳 (отмена, -1)
//   - В RecipeViewHolder добавлены setOnLongClickListener на btnLike/btnDislike/btnCooked
//   - Haptic feedback LONG_PRESS для долгих нажатий (отличается от VIRTUAL_KEY для коротких)
//   - Возврат true в onLongClickListener предотвращает всплытие события к itemView
//   - Защита от многократного срабатывания: стандартное поведение Android —
//     одно долгое нажатие = одно срабатывание. Отпустил палец → новое долгое = ещё -1.
// ============================================================
