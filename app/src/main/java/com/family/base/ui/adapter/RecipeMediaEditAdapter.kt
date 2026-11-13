package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.util.ImageUtils
import java.io.File

/**
 * 🆕 v21.1.0 (Шаг 1.5-2): адаптер медиа на экране РЕДАКТИРОВАНИЯ рецепта.
 *
 * Показывает горизонтальный список уже добавленных фото/видео
 * (превью + крестик удаления). Используется в RecipeEditActivity.
 *
 * Для экрана ПРОСМОТРА (RecipeDetailActivity) будет отдельный адаптер
 * (1.5-3), потому что там другое поведение: клик → полноэкранный просмотр
 * / VideoPlayerActivity, без крестика.
 *
 * Элемент: item_recipe_media_edit.xml
 *  - ivThumb      — превью (Coil): фото или первый кадр видео;
 *  - tvTypeBadge  — «🎬» для видео (для фото скрыт);
 *  - btnRemove    — крестик удаления.
 *
 * Источник файла:
 *  - для фото: ImageUtils.getRecipeMediaFile(context, recipeId, media.imageUrl);
 *  - для видео: сначала thumbnailUrl (если есть), иначе — иконка 🎬 на тёмном фоне.
 */
class RecipeMediaEditAdapter(
    private val recipeId: String,
    private val onRemoveClick: (RecipeMediaEntity) -> Unit,
    private val onItemClick: (RecipeMediaEntity) -> Unit
) : RecyclerView.Adapter<RecipeMediaEditAdapter.ViewHolder>() {

    private var items: List<RecipeMediaEntity> = emptyList()

    fun submitList(newItems: List<RecipeMediaEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recipe_media_edit, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val media = items[position]
        val context = holder.itemView.context

        val isVideo = media.mediaType == RecipeMediaEntity.TYPE_VIDEO

        // Бейдж
        holder.typeBadge.visibility = if (isVideo) View.VISIBLE else View.GONE

        // Превью
        if (isVideo) {
            // Для видео: пробуем thumbnail, иначе — заглушка.
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
            // Фото
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

        // Клики
        holder.itemView.setOnClickListener { onItemClick(media) }
        holder.btnRemove.setOnClickListener { onRemoveClick(media) }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivThumb)
        val typeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
    }
}
