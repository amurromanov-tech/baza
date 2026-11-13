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
 * 🆕 v21.1.0 (Шаг 1.5-3): адаптер галереи ВИДЕО на экране просмотра рецепта.
 *
 * Показывает горизонтальный список видео (item_recipe_video.xml):
 *  - ivVideoThumb — превью (первый кадр, если сгенерировался);
 *  - иконка play по центру (в layout, статична);
 *  - tvVideoBadge — «🎬» в углу.
 *
 * Клик → VideoPlayerActivity (EXTRA_URL = Uri.fromFile(localVideoFile),
 * EXTRA_MIME = media.mimeType).
 *
 * Если превью нет (thumbName = null / файл не создан) — показываем
 * ic_item_default. Видео всё равно открывается.
 *
 * Всё локально, без интернета.
 */
class RecipeVideoAdapter(
    private val recipeId: String,
    private val onVideoClick: (RecipeMediaEntity) -> Unit,
    private val onVideoLongClick: (RecipeMediaEntity) -> Unit
) : RecyclerView.Adapter<RecipeVideoAdapter.ViewHolder>() {

    private var items: List<RecipeMediaEntity> = emptyList()

    fun submitList(newItems: List<RecipeMediaEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recipe_video, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val media = items[position]
        val context = holder.itemView.context

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

        holder.badge.visibility = View.VISIBLE

        holder.itemView.setOnClickListener { onVideoClick(media) }
        holder.itemView.setOnLongClickListener {
            onVideoLongClick(media)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivVideoThumb)
        val badge: TextView = view.findViewById(R.id.tvVideoBadge)
    }
}
