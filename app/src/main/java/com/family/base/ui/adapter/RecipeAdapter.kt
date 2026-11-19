package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.RecipeEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🆕 v23.2.3: Переработанный адаптер списка рецептов.
 * 
 * Новый дизайн:
 *  - Название (жирное, сверху)
 *  - Описание (до 3 строк)
 *  - Сетка медиа (фото/видео по 3 в ряд)
 *  - Кнопки "Редактировать" / "Удалить" (внизу)
 */
class RecipeAdapter(
    private val onRecipeClick: (RecipeEntity) -> Unit,
    private val onRecipeLongClick: (RecipeEntity) -> Unit,
    private val onEditClick: (RecipeEntity) -> Unit,
    private val onDeleteClick: (RecipeEntity) -> Unit
) : RecyclerView.Adapter<RecipeAdapter.ViewHolder>() {

    /**
     * Элемент списка: рецепт + список локальных файлов (фото/видео).
     * Пока Activity передаёт только один файл (photoFile), но структура готова к списку.
     */
    data class RecipeListItem(
        val recipe: RecipeEntity,
        val mediaFiles: List<File> = emptyList()
    )

    private var items: List<RecipeListItem> = emptyList()

    private val dateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    }

    fun submitList(newItems: List<RecipeListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recipe, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        bind(holder, item)
    }

    private fun bind(holder: ViewHolder, item: RecipeListItem) {
        val context = holder.itemView.context
        val recipe = item.recipe

        // ---------- Название ----------
        holder.title.text = recipe.title

        // ---------- Описание ----------
        val desc = recipe.description?.trim()
        if (desc.isNullOrEmpty()) {
            holder.description.visibility = View.GONE
        } else {
            holder.description.visibility = View.VISIBLE
            holder.description.text = desc
        }

        // ---------- Сетка медиа (фото и видео) ----------
        holder.mediaGrid.removeAllViews()
        if (item.mediaFiles.isEmpty()) {
            holder.mediaGrid.visibility = View.GONE
        } else {
            holder.mediaGrid.visibility = View.VISIBLE
            
            // Ограничиваем количество отображаемых превью, чтобы не раздувать карточку
            val maxItems = 6
            val displayFiles = item.mediaFiles.take(maxItems)

            for (file in displayFiles) {
                val imageView = ImageView(context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(4, 4, 4, 4)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    
                    // Загружаем изображение (Coil)
                    load(file) {
                        crossfade(true)
                        placeholder(R.drawable.ic_item_default)
                        error(R.drawable.ic_item_default)
                    }
                }
                holder.mediaGrid.addView(imageView)
            }
            
            // Если файлов больше 6, добавляем "плашку" с количеством
            if (item.mediaFiles.size > maxItems) {
                val moreView = TextView(context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(4, 4, 4, 4)
                    }
                    text = "+${item.mediaFiles.size - maxItems}"
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor(android.graphics.Color.parseColor("#44000000"))
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                }
                holder.mediaGrid.addView(moreView)
            }
        }

        // ---------- Мета: источник + дата ----------
        val sourceLabel = when (recipe.source) {
            RecipeEntity.SOURCE_AI -> "🤖 ИИ"
            RecipeEntity.SOURCE_USER -> "📝 Мои"
            else -> "🍳 Рецепт"
        }
        val dateStr = try {
            dateFormat.format(Date(recipe.createdAt))
        } catch (e: Exception) {
            ""
        }
        holder.meta.text = if (dateStr.isEmpty()) sourceLabel else "$sourceLabel · $dateStr"

        // ---------- Цветная полоса: по source ----------
        val colorRes = when (recipe.source) {
            RecipeEntity.SOURCE_AI -> R.color.colorPrimary
            else -> R.color.colorNormal
        }
        holder.colorBar.setBackgroundColor(
            context.resources.getColor(colorRes, context.theme)
        )

        // ---------- Клики ----------
        holder.itemView.setOnClickListener { onRecipeClick(recipe) }
        holder.itemView.setOnLongClickListener {
            onRecipeLongClick(recipe)
            true
        }
        
        holder.btnEdit.setOnClickListener { onEditClick(recipe) }
        holder.btnDelete.setOnClickListener { onDeleteClick(recipe) }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorBar: View = view.findViewById(R.id.colorBar)
        val mediaGrid: GridLayout = view.findViewById(R.id.mediaGrid)
        val title: TextView = view.findViewById(R.id.tvRecipeTitle)
        val description: TextView = view.findViewById(R.id.tvRecipeDescription)
        val meta: TextView = view.findViewById(R.id.tvRecipeMeta)
        val btnEdit: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnEdit)
        val btnDelete: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnDelete)
    }
}
