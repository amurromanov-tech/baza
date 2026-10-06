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
import com.family.base.data.local.entity.ItemEntity
import com.family.base.ui.adapter.MoveFolderAdapter
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MoveDialogHelper {

    private const val TAG = "MoveDialogHelper"

    /**
     * Показывает диалог перемещения с полным деревом (папки + вложенные папки + предметы).
     *
     * @param onConfirm колбэк с парой (newParentId, newParentItemId):
     *   - переместить в папку:    newParentId = folderId,   newParentItemId = null
     *   - переместить в предмет:  newParentId = parentId предмета, newParentItemId = itemId
     *   - переместить в корень:   (null, null)
     */
    fun show(
        context: Context,
        scope: LifecycleCoroutineScope,
        db: AppDatabase,
        title: String,
        startFromId: String?,
        startFromItemId: String? = null,
        excludedIds: Set<String> = emptySet(),
        onConfirm: (newParentId: String?, newParentItemId: String?) -> Unit
    ) {
        Logger.log(
            TAG,
            "=== show START === title=$title, startFromId=$startFromId, " +
                "startFromItemId=$startFromItemId, excluded=${excludedIds.size}"
        )

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
        var currentItemId: String? = startFromItemId

        fun currentNodeKind(): String = when {
            currentItemId != null -> "item"
            currentFolderId != null -> "folder"
            else -> "root"
        }

        lateinit var loadNode: () -> Unit

        val adapter = MoveFolderAdapter(
            onFolderClick = { folder ->
                Logger.log(TAG, "Folder clicked: ${folder.name} (id=${folder.id})")
                currentFolderId = folder.id
                currentItemId = null
                loadNode()
            },
            onItemClick = { item ->
                Logger.log(TAG, "Item clicked: ${item.name} (id=${item.id})")
                currentItemId = item.id
                currentFolderId = null
                loadNode()
            }
        )

        rvFolders.layoutManager = LinearLayoutManager(context)
        rvFolders.adapter = adapter
        rvFolders.isClickable = true

        loadNode = {
            scope.launch {
                try {
                    val kind = currentNodeKind()
                    val rows: MutableList<MoveRow> = mutableListOf()
                    val crumbs: String

                    when (kind) {
                        "root" -> {
                            val rootFolders = withContext(Dispatchers.IO) {
                                db.folderDao().getRootFolders()
                            }
                            val rootItems = withContext(Dispatchers.IO) {
                                db.itemDao().getItemsByParent(null)
                            }

                            rootFolders
                                .filter { it.id !in excludedIds }
                                .forEach { rows.add(MoveRow.Folder(it, nested = false)) }

                            rootItems
                                .filter { it.parentItemId == null && it.id !in excludedIds }
                                .forEach { rows.add(MoveRow.Item(it)) }

                            crumbs = "📂 Корень"
                        }

                        "folder" -> {
                            val folderId = currentFolderId!!
                            val folder = withContext(Dispatchers.IO) {
                                db.folderDao().getFolderById(folderId)
                            }
                            if (folder == null) {
                                Logger.log(TAG, "loadNode: folder not found $folderId, falling back to root")
                                currentFolderId = null
                                loadNode()
                                return@launch
                            }

                            val subFolders = withContext(Dispatchers.IO) {
                                db.folderDao().getFoldersByParent(folderId)
                            }
                            val items = withContext(Dispatchers.IO) {
                                db.itemDao().getItemsByParent(folderId)
                            }

                            subFolders
                                .filter { it.id !in excludedIds }
                                .forEach { rows.add(MoveRow.Folder(it, nested = false)) }

                            items
                                .filter { it.parentItemId == null && it.id !in excludedIds }
                                .forEach { rows.add(MoveRow.Item(it)) }

                            crumbs = buildBreadcrumbsForFolder(db, folderId)
                        }

                        "item" -> {
                            val itemId = currentItemId!!
                            val item = withContext(Dispatchers.IO) {
                                db.itemDao().getItemById(itemId)
                            }
                            if (item == null) {
                                Logger.log(TAG, "loadNode: item not found $itemId, falling back to root")
                                currentItemId = null
                                loadNode()
                                return@launch
                            }

                            val nestedFolders = withContext(Dispatchers.IO) {
                                db.folderDao().getFoldersByParentItem(itemId)
                            }
                            val nestedItems = withContext(Dispatchers.IO) {
                                db.itemDao().getItemsByParentItemRaw(itemId)
                            }

                            nestedFolders
                                .filter { it.id !in excludedIds }
                                .forEach { rows.add(MoveRow.Folder(it, nested = true)) }

                            nestedItems
                                .filter { it.id !in excludedIds }
                                .forEach { rows.add(MoveRow.Item(it)) }

                            crumbs = buildBreadcrumbsForItem(db, itemId)
                        }

                        else -> {
                            crumbs = "📂 Корень"
                        }
                    }

                    withContext(Dispatchers.Main) {
                        adapter.submitList(rows)

                        if (rows.isEmpty()) {
                            tvEmptyHint.visibility = View.VISIBLE
                            rvFolders.visibility = View.GONE
                        } else {
                            tvEmptyHint.visibility = View.GONE
                            rvFolders.visibility = View.VISIBLE
                        }

                        tvBreadcrumbs.text = crumbs
                        btnGoUp.visibility = if (kind == "root") View.GONE else View.VISIBLE

                        Logger.log(TAG, "Loaded node kind=$kind, rows=${rows.size}, crumbs=$crumbs")
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "Error loading node: ${e.message}", e)
                }
            }
        }

        btnGoUp.setOnClickListener {
            scope.launch {
                try {
                    val kind = currentNodeKind()
                    when (kind) {
                        "item" -> {
                            val itemId = currentItemId!!
                            val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(itemId) }
                            currentItemId = null
                            currentFolderId = item?.parentId
                            Logger.log(TAG, "Go up from item $itemId → folder ${currentFolderId ?: "ROOT"}")
                        }
                        "folder" -> {
                            val folderId = currentFolderId!!
                            val folder = withContext(Dispatchers.IO) { db.folderDao().getFolderById(folderId) }
                            if (folder?.parentItemId != null) {
                                currentItemId = folder.parentItemId
                                currentFolderId = null
                                Logger.log(TAG, "Go up from nested folder $folderId → item ${folder.parentItemId}")
                            } else {
                                currentItemId = null
                                currentFolderId = folder?.parentId
                                Logger.log(TAG, "Go up from folder $folderId → folder ${currentFolderId ?: "ROOT"}")
                            }
                        }
                        else -> {
                            Logger.log(TAG, "Go up from root — ignoring")
                        }
                    }
                    loadNode()
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
            val kind = currentNodeKind()
            val (targetParentId, targetParentItemId) = when (kind) {
                "root" -> Pair(null, null)
                "folder" -> Pair(currentFolderId, null)
                "item" -> {
                    scope.launch {
                        try {
                            val itemId = currentItemId!!
                            val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(itemId) }
                            val parentFolderId = item?.parentId
                            Logger.log(
                                TAG,
                                "Confirmed move INTO item: itemId=$itemId, parentFolderId=${parentFolderId ?: "ROOT"}"
                            )
                            withContext(Dispatchers.Main) {
                                onConfirm(parentFolderId, itemId)
                            }
                            dialog.dismiss()
                        } catch (e: Exception) {
                            Logger.log(TAG, "Error confirming move into item: ${e.message}", e)
                        }
                    }
                    return@setOnClickListener
                }
                else -> Pair(null, null)
            }

            Logger.log(TAG, "Confirmed move to: parentId=$targetParentId, parentItemId=$targetParentItemId")
            onConfirm(targetParentId, targetParentItemId)
            dialog.dismiss()
        }

        dialog.show()

        loadNode()
    }

    // ============================================================
    // ВНУТРЕННЯЯ МОДЕЛЬ СТРОКИ
    // ============================================================
    sealed class MoveRow {
        data class Folder(val folder: FolderEntity, val nested: Boolean) : MoveRow()
        data class Item(val item: ItemEntity) : MoveRow()
    }

    // ============================================================
    // ХЛЕБНЫЕ КРОШКИ
    // ============================================================

    private suspend fun buildBreadcrumbsForFolder(db: AppDatabase, folderId: String): String {
        val segments = mutableListOf<String>()
        var currentFolderId: String? = folderId
        var currentItemId: String? = null
        var depth = 0
        val maxDepth = 100

        while (depth < maxDepth) {
            depth++

            if (currentItemId != null) {
                val item = db.itemDao().getItemById(currentItemId!!) ?: break
                segments.add("📦 ${item.name}")
                currentFolderId = item.parentId
                currentItemId = item.parentItemId
                continue
            }

            if (currentFolderId != null) {
                val folder = db.folderDao().getFolderById(currentFolderId!!) ?: break
                segments.add("📁 ${folder.name}")
                if (folder.parentItemId != null) {
                    currentItemId = folder.parentItemId
                    currentFolderId = folder.parentId
                } else {
                    currentFolderId = folder.parentId
                    currentItemId = null
                }
                continue
            }

            break
        }

        if (segments.isEmpty()) return "📂 Корень"
        return "📂 Корень / " + segments.reversed().joinToString(" / ")
    }

    private suspend fun buildBreadcrumbsForItem(db: AppDatabase, itemId: String): String {
        val segments = mutableListOf<String>()
        var currentFolderId: String? = null
        var currentItemId: String? = itemId
        var depth = 0
        val maxDepth = 100

        while (depth < maxDepth) {
            depth++

            if (currentItemId != null) {
                val item = db.itemDao().getItemById(currentItemId!!) ?: break
                segments.add("📦 ${item.name}")
                currentFolderId = item.parentId
                currentItemId = item.parentItemId
                continue
            }

            if (currentFolderId != null) {
                val folder = db.folderDao().getFolderById(currentFolderId!!) ?: break
                segments.add("📁 ${folder.name}")
                if (folder.parentItemId != null) {
                    currentItemId = folder.parentItemId
                    currentFolderId = folder.parentId
                } else {
                    currentFolderId = folder.parentId
                    currentItemId = null
                }
                continue
            }

            break
        }

        if (segments.isEmpty()) return "📂 Корень"
        return "📂 Корень / " + segments.reversed().joinToString(" / ")
    }
}
