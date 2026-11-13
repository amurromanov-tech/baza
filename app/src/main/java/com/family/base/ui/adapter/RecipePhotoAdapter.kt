package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.RecipeMediaEntity
import com.family.base.util.ImageUtils
import java.io.File

/**
 * 🆕 v21.1.0 (Шаг 1.5-3): адаптер галереи ФОТО на экране просмотра рецепта.
 *
 * Показывает горизонтальный список фото (item_recipe_photo.xml).
 * Клик по фото — onPhotoClick(media, position) → FullscreenImageActivity.
 *
 * Отличие от RecipeMediaEditAdapter:
 *  - нет крестика удаления (удаление — через долгий клик / контекст, см. Activity);
 *  - клик открывает полноэкранный просмотр;
 *  - размер карточки 120×120 (крупнее для просмотра).
 *
 * Источник файла: ImageUtils.getRecipeMediaFile(context, recipeId, media.imageUrl).
 * Всё локально, без интернета.
 */
class RecipePhotoAdapter(
    private val recipeId: String,
    private val onPhotoClick: (RecipeMediaEntity, Int) -> Unit,
    private val onPhotoLongClick: (RecipeMediaEntity) -> Unit
) : RecyclerView.Adapter<RecipePhotoAdapter.ViewHolder>() {

    private var items: List<RecipeMediaEntity> = emptyList()

    fun submitList(newItems: List<RecipeMediaEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recipe_photo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val media = items[position]
        val context = holder.itemView.context

        val photoFile: File? = if (!media.imageUrl.isNullOrBlank()) {
            val f = ImageUtils.getRecipeMediaFile(context, recipeId, media.imageUrl)
            if (f.exists()) f else null
        } else {
            null
        }

        if (photoFile != null) {
            holder.photo.load(photoFile) {
                crossfade(true)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }
        } else {
            holder.photo.load(R.drawable.ic_item_default)
        }

        holder.itemView.setOnClickListener { onPhotoClick(media, position) }
        holder.itemView.setOnLongClickListener {
            onPhotoLongClick(media)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val photo: ImageView = view.findViewById(R.id.ivPhoto)
    }
}
