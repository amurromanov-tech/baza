package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.RecipeEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🆕 v21.1.0 (Шаг 1.4b-1): адаптер списка рецептов.
 * Использует item_recipe.xml (исходный вид).
 */
class RecipeAdapter(
    private val onRecipeClick: (RecipeEntity) -> Unit,
    private val onRecipeLongClick: (RecipeEntity) -> Unit
) : RecyclerView.Adapter<RecipeAdapter.ViewHolder>() {

    data class RecipeListItem(
        val recipe: RecipeEntity,
        val photoFile: java.io.File?
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

        // ---------- Фото ----------
        if (item.photoFile != null && item.photoFile.exists()) {
            holder.photo.load(item.photoFile) {
                crossfade(true)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }
        } else {
            holder.photo.load(R.drawable.ic_item_default)
        }

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
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorBar: View = view.findViewById(R.id.colorBar)
        val photo: ImageView = view.findViewById(R.id.ivRecipePhoto)
        val title: TextView = view.findViewById(R.id.tvRecipeTitle)
        val description: TextView = view.findViewById(R.id.tvRecipeDescription)
        val meta: TextView = view.findViewById(R.id.tvRecipeMeta)
    }
}
