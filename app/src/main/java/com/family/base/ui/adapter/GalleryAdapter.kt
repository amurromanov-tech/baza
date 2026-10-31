package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.ImageRequest
import com.family.base.R
import com.family.base.data.model.GalleryItem
import com.family.base.util.Logger

/**
 * 🆕 v20.4.9: адаптер сетки галереи.
 *
 * 🆕 v20.5.6: исправлен onSuccess — в Coil 2.x у SuccessResult
 * нет поля `image`, только `drawable`. Размеры берём из
 * drawable.intrinsicWidth / intrinsicHeight.
 */
class GalleryAdapter(
    private val onFolderClick: (GalleryItem) -> Unit,
    private val onPhotoClick: (GalleryItem) -> Unit,
    private val onVideoClick: (GalleryItem) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    companion object {
        private const val TAG = "GalleryAdapter"
    }

    private var items: List<GalleryItem> = emptyList()

    fun submitList(newItems: List<GalleryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        bind(holder, item)
    }

    private fun bind(holder: ViewHolder, item: GalleryItem) {
        holder.tvName.text = item.name

        holder.ivThumb.load(null)
        holder.tvVideoBadge.visibility = View.GONE
        holder.tvFolderBadge.visibility = View.GONE

        when {
            item.isFolder -> bindFolder(holder, item)
            item.isVideo -> bindVideo(holder, item)
            else -> bindPhoto(holder, item)
        }
    }

    private fun bindFolder(holder: ViewHolder, item: GalleryItem) {
        holder.ivThumb.load(R.drawable.ic_folder_default) {
            crossfade(false)
            placeholder(R.drawable.ic_folder_default)
            error(R.drawable.ic_folder_default)
        }
        holder.tvFolderBadge.visibility = View.VISIBLE
        holder.itemView.setOnClickListener { onFolderClick(item) }
    }

    private fun bindVideo(holder: ViewHolder, item: GalleryItem) {
        val preview = item.previewUrl
        if (!preview.isNullOrEmpty()) {
            holder.ivThumb.load(preview) {
                crossfade(true)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }
        } else {
            holder.ivThumb.load(R.drawable.ic_item_default) {
                crossfade(false)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }
        }
        holder.tvVideoBadge.visibility = View.VISIBLE
        holder.itemView.setOnClickListener { onVideoClick(item) }
    }

    private fun bindPhoto(holder: ViewHolder, item: GalleryItem) {
        val preview = item.previewUrl
        if (preview.isNullOrEmpty()) {
            Logger.log(TAG, "bindPhoto: preview is null/empty for '${item.name}'")
            holder.ivThumb.load(R.drawable.ic_item_default) {
                crossfade(false)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }
        } else {
            Logger.log(TAG, "bindPhoto: loading preview for '${item.name}' → $preview")
            val request = ImageRequest.Builder(holder.itemView.context)
                .data(preview)
                .crossfade(true)
                .placeholder(R.drawable.ic_item_default)
                .error(R.drawable.ic_item_default)
                .listener(
                    onStart = { req ->
                        Logger.log(TAG, "Coil onStart: ${req.data}")
                    },
                    onSuccess = { req, result ->
                        Logger.log(
                            TAG,
                            "Coil onSuccess: ${req.data} " +
                                "(w=${result.drawable.intrinsicWidth}, " +
                                "h=${result.drawable.intrinsicHeight})"
                        )
                    },
                    onError = { req, result ->
                        Logger.log(
                            TAG,
                            "Coil onError: ${req.data} → ${result.throwable.message}",
                            result.throwable
                        )
                    }
                )
                .build()
            holder.ivThumb.load(request)
        }

        holder.itemView.setOnClickListener { onPhotoClick(item) }
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvVideoBadge: TextView = view.findViewById(R.id.tvVideoBadge)
        val tvFolderBadge: TextView = view.findViewById(R.id.tvFolderBadge)
    }
}
