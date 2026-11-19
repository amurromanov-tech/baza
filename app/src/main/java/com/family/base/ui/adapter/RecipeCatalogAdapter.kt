package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
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
    private val onEditClick: (RecipeEntity) -> Unit,
    private val onDeleteClick: (RecipeEntity) -> Unit
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
            val photoFile: java.io.File? // Оставляем одно фото для совместимости, но будем использовать сетку
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
        private val mediaGrid: GridLayout = view.findViewById(R.id.mediaGrid)
        private val tvRecipeTitle: TextView = view.findViewById(R.id.tvRecipeTitle)
        private val tvRecipeDescription: TextView = view.findViewById(R.id.tvRecipeDescription)
        private val tvRecipeMeta: TextView = view.findViewById(R.id.tvRecipeMeta)
        private val btnEdit: View = view.findViewById(R.id.btnEdit)
        private val btnDelete: View = view.findViewById(R.id.btnDelete)

        fun bind(item: RecipeCatalogItem.Recipe) {
            val recipe = item.recipe
            val context = itemView.context

            val colorRes = if (recipe.source == RecipeEntity.SOURCE_AI) {
                R.color.colorWarning
            } else {
                R.color.colorNormal
            }
            colorBar.setBackgroundColor(context.resources.getColor(colorRes, context.theme))

            tvRecipeTitle.text = recipe.title

            val desc = recipe.description
            if (desc.isNullOrBlank()) {
                tvRecipeDescription.visibility = View.GONE
            } else {
                tvRecipeDescription.visibility = View.VISIBLE
                tvRecipeDescription.text = desc
            }

            // --- СЕТКА МЕДИА ---
            mediaGrid.removeAllViews()
            if (item.photoFile != null && item.photoFile.exists()) {
                mediaGrid.visibility = View.VISIBLE
                // Создаём ImageView программно
                val imageView = ImageView(context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(4, 4, 4, 4)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    load(item.photoFile) {
                        crossfade(true)
                        placeholder(R.drawable.ic_item_default)
                        error(R.drawable.ic_item_default)
                    }
                }
                mediaGrid.addView(imageView)
            } else {
                mediaGrid.visibility = View.GONE
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
            
            btnEdit.setOnClickListener { onEditClick(recipe) }
            btnDelete.setOnClickListener { onDeleteClick(recipe) }
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
