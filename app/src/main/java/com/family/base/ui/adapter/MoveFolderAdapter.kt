package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.local.entity.FolderEntity

class MoveFolderAdapter(
    private val onFolderClick: (FolderEntity) -> Unit
) : RecyclerView.Adapter<MoveFolderAdapter.ViewHolder>() {

    private var folders: List<FolderEntity> = emptyList()

    fun submitList(newFolders: List<FolderEntity>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_move_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.tvFolderName.text = folder.name

        // Явно разрешаем клик на корневой itemView
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        holder.itemView.setOnClickListener {
            onFolderClick(folder)
        }
    }

    override fun getItemCount(): Int = folders.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivFolderIcon: ImageView = view.findViewById(R.id.ivFolderIcon)
        val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
        val ivArrow: ImageView = view.findViewById(R.id.ivArrow)
    }
}
