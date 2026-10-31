package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.model.GalleryItem
import com.family.base.data.repository.GalleryRepository
import com.family.base.util.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 🆕 v20.4.9: адаптер сетки галереи.
 *
 * 🆕 v20.6.1: превью грузятся через WebDAV (GalleryRepository.downloadPreview),
 * а не по прямой preview-ссылке Диска (она отдаёт 403 без куки).
 *
 * Логика bindPhoto:
 *  1. Показываем плейсхолдер.
 *  2. В applicationScope запускаем downloadPreview(item.path).
 *  3. По завершении на главном потоке грузим байты через Coil.load(byteArray).
 *
 * Кэш: Coil кэширует байты в своём memory/disk cache по ключу byteArray.hashCode().
 * При повторном bind (скролл туда-обратно) повторного запроса не будет.
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

    /**
     * 🆕 v20.6.1: репозиторий для скачивания превью через WebDAV.
     * Передаётся из GalleryActivity (там уже есть GalleryRepository).
     */
    var galleryRepository: GalleryRepository? = null

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

        // Сбрасываем состояние от предыдущего элемента
        holder.ivThumb.load(null)
        holder.tvVideoBadge.visibility = View.GONE
        holder.tvFolderBadge.visibility = View.GONE

        when {
            item.isFolder -> bindFolder(holder, item)
            item.isVideo -> bindVideo(holder, item)
            else -> bindPhoto(holder, item)
        }
    }

    // ============================================================
    // ПАПКА
    // ============================================================

    private fun bindFolder(holder: ViewHolder, item: GalleryItem) {
        holder.ivThumb.load(R.drawable.ic_folder_default) {
            crossfade(false)
            placeholder(R.drawable.ic_folder_default)
            error(R.drawable.ic_folder_default)
        }
        holder.tvFolderBadge.visibility = View.VISIBLE

        holder.itemView.setOnClickListener { onFolderClick(item) }
    }

    // ============================================================
    // ВИДЕО (плейсхолдер в Шаге 1)
    // ============================================================

    private fun bindVideo(holder: ViewHolder, item: GalleryItem) {
        // Превью для видео через WebDAV не запрашиваем —
        // Диск не генерирует превью для видео. Только плейсхолдер.
        holder.ivThumb.load(R.drawable.ic_item_default) {
            crossfade(false)
            placeholder(R.drawable.ic_item_default)
            error(R.drawable.ic_item_default)
        }
        holder.tvVideoBadge.visibility = View.VISIBLE

        holder.itemView.setOnClickListener { onVideoClick(item) }
    }

    // ============================================================
    // ФОТО — 🆕 v20.6.1: через WebDAV
    // ============================================================

    private fun bindPhoto(holder: ViewHolder, item: GalleryItem) {
        // Сначала плейсхолдер, чтобы не было «пустоты» при загрузке.
        holder.ivThumb.load(R.drawable.ic_item_default) {
            crossfade(false)
            placeholder(R.drawable.ic_item_default)
            error(R.drawable.ic_item_default)
        }

        val repo = galleryRepository
        if (repo == null) {
            Logger.log(TAG, "bindPhoto: galleryRepository is null, skip '${item.name}'")
            holder.itemView.setOnClickListener { onPhotoClick(item) }
            return
        }

        val imageView = holder.ivThumb
        val itemName = item.name

        BaseApplication.applicationScope.launch {
            val bytes = repo.downloadPreview(item.path)
            if (bytes == null) {
                Logger.log(TAG, "bindPhoto: preview null for '$itemName'")
                return@launch
            }
            withContext(Dispatchers.Main) {
                imageView.load(bytes) {
                    crossfade(true)
                    placeholder(R.drawable.ic_item_default)
                    error(R.drawable.ic_item_default)
                }
            }
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
