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

/**
 * 🆕 Шаг 1.7-3: универсальный адаптер для экрана «Мои рецепты».
 *
 * Один RecyclerView — два типа элементов:
 *  - ПАПКА  (RecipeFolderEntity) — item_recipe_folder.xml;
 *  - РЕЦЕПТ (RecipeEntity)       — item_recipe.xml (существующий layout).
 *
 * Модель списка — sealed class [RecipeCatalogItem]:
 *  - Folder(folder, recipesCount) — папка + счётчик «живых» рецептов в ней;
 *  - Recipe(recipe, photoFile)    — рецепт + локальный файл первого фото.
 *
 * Склейку двух LiveData (folders + recipes) в один список делает
 * RecipeListActivity: сначала все папки, потом все рецепты.
 *
 * Стиль — как у CatalogAdapter:
 *  - submitList через DiffUtil (папки и рецепты не пересекаются по id);
 *  - onFolderClick / onRecipeClick — клик;
 *  - onFolderLongClick / onRecipeLongClick — долгий клик (контекстное меню).
 *
 * Кто использует:
 *  - RecipeListActivity (source = "user") — папки + рецепты;
 *  - RecipeListActivity (source = "ai")   — только рецепты (folders пустые).
 */
class RecipeCatalogAdapter(
    private val onFolderClick: (RecipeFolderEntity) -> Unit,
    private val onRecipeClick: (RecipeEntity) -> Unit,
    private val onFolderLongClick: (RecipeFolderEntity) -> Unit,
    private val onRecipeLongClick: (RecipeEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_RECIPE = 1
    }

    /**
     * Модель элемента списка.
     *
     * @param Folder  папка + количество «живых» рецептов внутри (для подписи).
     * @param Recipe  рецепт + локальный File первого фото (или null).
     */
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

    // ============================================================
    // ДАННЫЕ
    // ============================================================

    /**
     * Обновить список через DiffUtil (плавные анимации, без мигания).
     *
     * Сравнение:
     *  - Folder: по folder.id + (name, parentId, recipesCount) для areContentsTheSame;
     *  - Recipe: по recipe.id + (title, description, updatedAt, photoFile) для contents.
     */
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
                            old.recipe.updatedAt == new.recipe.updatedAt &&
                            old.photoFile?.absolutePath == new.photoFile?.absolutePath
                    else -> false
                }
            }
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    // ============================================================
    // RECYCLER
    // ============================================================

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

    // ============================================================
    // VIEWHOLDER: ПАПКА
    // ============================================================

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

    // ============================================================
    // VIEWHOLDER: РЕЦЕПТ
    //
    // item_recipe.xml (существующий layout):
    //   colorBar, ivRecipePhoto, tvRecipeTitle, tvRecipeDescription, tvRecipeMeta
    // ============================================================

    inner class RecipeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val colorBar: View = view.findViewById(R.id.colorBar)
        private val ivRecipePhoto: ImageView = view.findViewById(R.id.ivRecipePhoto)
        private val tvRecipeTitle: TextView = view.findViewById(R.id.tvRecipeTitle)
        private val tvRecipeDescription: TextView = view.findViewById(R.id.tvRecipeDescription)
        private val tvRecipeMeta: TextView = view.findViewById(R.id.tvRecipeMeta)

        fun bind(item: RecipeCatalogItem.Recipe) {
            val recipe = item.recipe
            val context = itemView.context

            // Полоска: у ИИ — акцент, у пользовательских — обычный цвет.
            val colorRes = if (recipe.source == RecipeEntity.SOURCE_AI) {
                R.color.colorWarning
            } else {
                R.color.colorNormal
            }
            colorBar.setBackgroundColor(context.resources.getColor(colorRes, context.theme))

            // Фото: локальный файл первого фото, иначе заглушка.
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

            itemView.setOnClickListener { onRecipeClick(recipe) }
            itemView.setOnLongClickListener { onRecipeLongClick(recipe); true }
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНОЕ
    // ============================================================

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
