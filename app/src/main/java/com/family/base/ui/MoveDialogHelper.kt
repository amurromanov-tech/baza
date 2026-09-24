package com.family.base.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.ui.adapter.MoveFolderAdapter
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MoveDialogHelper {

    private const val TAG = "MoveDialogHelper"

    fun show(
        context: Context,
        scope: LifecycleCoroutineScope,
        db: AppDatabase,
        title: String,
        startFromId: String?,
        excludedIds: Set<String> = emptySet(),
        onConfirm: (String?) -> Unit
    ) {
        Logger.log(TAG, "=== show START === title=$title, startFromId=$startFromId, excluded=${excludedIds.size}")

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_move, null, false)
        val tvDialogTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvBreadcrumbs = view.findViewById<TextView>(R.id.tvBreadcrumbs)
        val btnGoUp = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGoUp)
        val rvFolders = view.findViewById<RecyclerView>(R.id.rvFolders)
        val tvEmptyHint = view.findViewById<TextView>(R.id.tvEmptyHint)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)
        val btnMoveHere = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMoveHere)

        tvDialogTitle.text = title

        var currentFolderId: String? = startFromId

        val adapter = MoveFolderAdapter { clickedFolder ->
            currentFolderId = clickedFolder.id
            // загрузка ниже — через локальную функцию
        }
        rvFolders.layoutManager = LinearLayoutManager(context)
        rvFolders.adapter = adapter

        fun loadFolder(folderId: String?) {
            scope.launch {
                try {
                    val allChildren: List<FolderEntity> = withContext(Dispatchers.IO) {
                        val dao = db.folderDao()
                        if (folderId == null) {
                            dao.getRootFolders()
                        } else {
                            dao.getFoldersByParent(folderId)
                        }
                    }

                    val visibleChildren = allChildren.filter { it.id !in excludedIds }

                    withContext(Dispatchers.Main) {
                        adapter.submitList(visibleChildren)

                        if (visibleChildren.isEmpty()) {
                            tvEmptyHint.visibility = View.VISIBLE
                            rvFolders.visibility = View.GONE
                        } else {
                            tvEmptyHint.visibility = View.GONE
                            rvFolders.visibility = View.VISIBLE
                        }

                        val crumbs = buildBreadcrumbs(folderId)
                        tvBreadcrumbs.text = crumbs

                        btnGoUp.visibility = if (folderId == null) View.GONE else View.VISIBLE
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "Error loading folder $folderId: ${e.message}", e)
                }
            }
        }

        // Переустанавливаем обработчик клика — он должен вызывать loadFolder
        val fixedAdapter = MoveFolderAdapter { clickedFolder ->
            currentFolderId = clickedFolder.id
            loadFolder(currentFolderId)
        }
        rvFolders.adapter = fixedAdapter

        btnGoUp.setOnClickListener {
            scope.launch {
                try {
                    val parent = withContext(Dispatchers.IO) {
                        currentFolderId?.let { db.folderDao().getFolderById(it)?.parentId }
                    }
                    currentFolderId = parent
                    loadFolder(currentFolderId)
                } catch (e: Exception) {
                    Logger.log(TAG, "Error navigating up: ${e.message}", e)
                }
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnMoveHere.setOnClickListener {
            Logger.log(TAG, "Confirmed move to: $currentFolderId")
            onConfirm(currentFolderId)
            dialog.dismiss()
        }

        dialog.show()

        loadFolder(currentFolderId)
    }

    private suspend fun buildBreadcrumbs(folderId: String?): String {
        if (folderId == null) return "📂 Корень"

        val parts = mutableListOf<String>()
        var id: String? = folderId

        // Получаем DAO через переданный db — но проще передавать его сюда.
        // Сейчас оставим через контекст: получим из BaseApplication.
        val ctx = com.family.base.BaseApplication.getAppContext()
        val dao = AppDatabase.getInstance(ctx).folderDao()

        while (id != null) {
            val folder = dao.getFolderById(id) ?: break
            parts.add(folder.name)
            id = folder.parentId
        }

        return "📂 Корень / " + parts.reversed().joinToString(" / ")
    }
}
