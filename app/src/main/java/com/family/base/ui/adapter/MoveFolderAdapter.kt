package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.ui.MoveDialogHelper

class MoveFolderAdapter(
    private val onFolderClick: (FolderEntity) -> Unit,
    private val onItemClick: (ItemEntity) -> Unit
) : RecyclerView.Adapter<MoveFolderAdapter.ViewHolder>() {

    private var rows: List<MoveDialogHelper.MoveRow> = emptyList()

    fun submitList(newRows: List<MoveDialogHelper.MoveRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_move_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]

        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true

        when (row) {
            is MoveDialogHelper.MoveRow.Folder -> {
                val folder = row.folder
                holder.ivFolderIcon.setImageResource(R.drawable.ic_folder_default)
                holder.tvFolderName.text = if (row.nested) "📁 ${folder.name}" else folder.name

                holder.itemView.setOnClickListener { onFolderClick(folder) }

                holder.ivArrow.visibility = View.VISIBLE
            }

            is MoveDialogHelper.MoveRow.Item -> {
                val item = row.item
                holder.ivFolderIcon.setImageResource(R.drawable.ic_item_default)

                val emoji = when (item.itemType) {
                    "food" -> "🍎"
                    "medicine" -> "💊"
                    "thing" -> "📦"
                    else -> "🗂"
                }
                holder.tvFolderName.text = "$emoji ${item.name}"

                holder.itemView.setOnClickListener { onItemClick(item) }

                holder.ivArrow.visibility = View.VISIBLE
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivFolderIcon: ImageView = view.findViewById(R.id.ivFolderIcon)
        val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
        val ivArrow: ImageView = view.findViewById(R.id.ivArrow)
    }
}
