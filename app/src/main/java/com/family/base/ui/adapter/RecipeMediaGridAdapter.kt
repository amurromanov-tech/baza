package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.util.ImageUtils
import java.io.File

/**
 * 🆕 v23.3.x: адаптер СЕТКИ МЕДИА на экране просмотра рецепта (RecipeDetailActivity).
 *
 * Заменяет самодельный GridLayout + FrameLayout/ImageView из populateMediaGrid.
 * Причина замены: GridLayout с layout_weight в ячейках схлопывал их в 0×0,
 * из-за чего галерея на экране деталей была пустой (хотя файлы есть и в
 * RecipeEditActivity они отображались).
 *
 * Особенности:
 *  - ФОТО: грузим ImageUtils.getRecipeMediaFile(recipeId, media.imageUrl).
 *  - ВИДЕО: грузим превью ImageUtils.getRecipeThumbFile(recipeId, media.thumbnailUrl).
 *    Если превью нет — ic_item_default. Поверх — бейдж ▶ (tvTypeBadge).
 *  - Клик: onItemClick(media) — открыть полноэкранное фото / VideoPlayer.
 *  - Долгий клик: onItemLongClick(media) — диалог удаления.
 *  - Никакой кнопки удаления в ячейке нет (удаление — по долгому клику).
 *
 * Используется с GridLayoutManager(context, 3).
 */
class RecipeMediaGridAdapter(
    private val recipeId: String,
    private val onItemClick: (RecipeMediaEntity) -> Unit,
    private val onItemLongClick: (RecipeMediaEntity) -> Unit
) : RecyclerView.Adapter<RecipeMediaGridAdapter.ViewHolder>() {

    private var items: List<RecipeMediaEntity> = emptyList()

    fun submitList(newItems: List<RecipeMediaEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recipe_media_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val media = items[position]
        val context = holder.itemView.context

        val isVideo = media.mediaType == RecipeMediaEntity.TYPE_VIDEO

        holder.typeBadge.visibility = if (isVideo) View.VISIBLE else View.GONE

        if (isVideo) {
            val thumbFile: File? = if (!media.thumbnailUrl.isNullOrBlank()) {
                val f = ImageUtils.getRecipeThumbFile(context, recipeId, media.thumbnailUrl)
                if (f.exists()) f else null
            } else {
                null
            }

            if (thumbFile != null) {
                holder.thumb.load(thumbFile) {
                    crossfade(true)
                    placeholder(R.drawable.ic_item_default)
                    error(R.drawable.ic_item_default)
                }
            } else {
                holder.thumb.load(R.drawable.ic_item_default)
            }
        } else {
            val photoFile: File? = if (!media.imageUrl.isNullOrBlank()) {
                val f = ImageUtils.getRecipeMediaFile(context, recipeId, media.imageUrl)
                if (f.exists()) f else null
            } else {
                null
            }

            if (photoFile != null) {
                holder.thumb.load(photoFile) {
                    crossfade(true)
                    placeholder(R.drawable.ic_item_default)
                    error(R.drawable.ic_item_default)
                }
            } else {
                holder.thumb.load(R.drawable.ic_item_default)
            }
        }

        holder.itemView.setOnClickListener { onItemClick(media) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(media)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivThumb)
        val typeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
    }
}
