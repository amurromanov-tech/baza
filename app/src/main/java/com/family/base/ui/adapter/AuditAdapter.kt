package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.util.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuditAdapter(
    private val onItemClick: (ItemEntity) -> Unit,
    private val scope: CoroutineScope,
    private val dbProvider: () -> AppDatabase
) : RecyclerView.Adapter<AuditAdapter.ViewHolder>() {

    private var items: List<ItemEntity> = emptyList()

    fun submitList(newItems: List<ItemEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audit, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // Название
        holder.tvName.text = item.name

        // Цветная полоса по статусу
        val colorRes = when {
            item.isLent -> R.color.status_lent
            item.isExpired -> R.color.status_expired
            item.daysUntilExpiry in 0..3 -> R.color.status_warning
            else -> R.color.status_normal
        }
        holder.colorBar.setBackgroundColor(ContextCompat.getColor(context, colorRes))

        // Иконка — фото или дефолт
        holder.ivIcon.load(null)
        val localFile = ImageUtils.getLocalImageFile(context, item.id)
        if (localFile != null && localFile.exists()) {
            holder.ivIcon.load(localFile) {
                crossfade(true)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }
        } else {
            holder.ivIcon.load(R.drawable.ic_item_default)
        }

        // Путь — строим асинхронно
        holder.tvPath.text = "…"
        val itemIdForPath = item.id
        val parentIdForPath = item.parentId
        scope.launch {
            val path = withContext(Dispatchers.IO) { buildItemPath(parentIdForPath) }
            withContext(Dispatchers.Main) {
                // Проверяем, что этот же предмет всё ещё привязан к холдеру
                val currentItem = holder.itemView.tag as? String
                if (currentItem == itemIdForPath || currentItem == null) {
                    holder.tvPath.text = path
                }
            }
        }
        holder.itemView.tag = itemIdForPath

        // Клик
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    private suspend fun buildItemPath(parentId: String?): String {
        if (parentId == null) return "📂 Корень"

        val db = dbProvider()
        val parts = mutableListOf<String>()
        var id: String? = parentId

        while (id != null) {
            val folder = db.folderDao().getFolderById(id) ?: break
            parts.add(folder.name)
            id = folder.parentId
        }

        if (parts.isEmpty()) return "📂 Корень"
        return "📂 Корень / " + parts.reversed().joinToString(" / ")
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorBar: View = view.findViewById(R.id.colorBar)
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvPath: TextView = view.findViewById(R.id.tvPath)
    }
}
