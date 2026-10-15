package com.family.base.ui.viewmodel

import android.content.Context
import android.widget.Toast
import com.family.base.util.ImageUtils
import com.family.base.util.PurchaseFolderHelper
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxLogger
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.model.SubtypeCatalog
import com.family.base.data.repository.CatalogRepository
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Делегат MainViewModel: всё, что связано с папками и предметами.
 *
 * Отвечает за:
 *  - создание / изменение / удаление предметов и папок;
 *  - перемещение (в т.ч. splitAndMoveItem, разделение количества);
 *  - архивацию / разархивацию;
 *  - списание (полное и частичное);
 *  - займ (выдать / вернуть);
 *  - ревизию;
 *  - копирование;
 *  - вложенность (дети предмета, отвязка, счётчики);
 *  - запись в outbox + историю (через HistoryViewModel).
 *
 * НЕ знает про LiveData / UI, кроме Toast-уведомлений о циклах и системных папках.
 * Живёт в MainViewModel как обычное поле.
 *
 * 🆕 v17.0.10:
 *  - writeOffItem: автоудаление опустевшей папки покупки;
 *  - deleteFolder: содержимое подпапки «🛒 Покупок» уезжает
 *    в корень «🛒 Списка покупок», а не в корень базы;
 *  - unarchiveItem: страховка от «битого» parentId.
 */
class CatalogViewModel(
    private val db: AppDatabase,
    private val repository: CatalogRepository,
    private val historyViewModel: HistoryViewModel,
    private val currentUserProvider: () -> String,
    private val currentFolderIdProvider: () -> String?,
    private val appContextProvider: () -> Context,
    private val onContentsChanged: () -> Unit
) {

    private val TAG = "CatalogViewModel"

    private val currentUser: String
        get() = currentUserProvider()

    private fun appContext(): Context = appContextProvider()

    // ============================================================
    // ИСТОРИЯ (прокси в HistoryViewModel)
    // ============================================================

    private suspend fun writeHistory(
        itemId: String,
        itemName: String?,
        action: String,
        oldValue: String?,
        newValue: String?
    ) {
        historyViewModel.writeHistory(itemId, itemName, action, oldValue, newValue)
    }

    // ============================================================
    // ПРЕДМЕТЫ — СОЗДАНИЕ
    // ============================================================

    suspend fun createItem(item: ItemEntity, imageBytes: ByteArray? = null) {
        withContext(Dispatchers.IO) { db.itemDao().insertItem(item) }

        imageBytes?.let { bytes ->
            ImageUtils.saveImageLocally(appContext(), item.id, bytes)
        }

        OutboxLogger.itemChanged(db, item, OutboxEntity.ACTION_CREATE, currentUser)

        writeHistory(
            itemId = item.id,
            itemName = item.name,
            action = "create",
            oldValue = null,
            newValue = "${item.quantity} шт."
        )

        onContentsChanged()
    }

    suspend fun createItemsBatch(items: List<ItemEntity>, folderId: String?): Int {
        if (items.isEmpty()) return 0

        Logger.log(TAG, "createItemsBatch: ${items.size} items, folderId=$folderId")

        return try {
            val prepared = items.map { it.copy(parentId = folderId, parentItemId = null) }

            withContext(Dispatchers.IO) {
                db.itemDao().insertItems(prepared)
            }

            withContext(Dispatchers.IO) {
                OutboxLogger.itemsBatchCreated(db, prepared, currentUser)
            }

            prepared.forEach { item ->
                writeHistory(
                    itemId = item.id,
                    itemName = item.name,
                    action = "create",
                    oldValue = "Импорт из чека",
                    newValue = "${item.quantity} шт."
                )
            }

            Logger.log(TAG, "createItemsBatch: inserted ${prepared.size} items")

            onContentsChanged()
            prepared.size

        } catch (e: Exception) {
            Logger.log(TAG, "createItemsBatch error: ${e.message}", e)
            0
        }
    }

    // ============================================================
    // ДЕТИ ПРЕДМЕТА
    // ============================================================

    suspend fun getChildrenCount(itemId: String): Pair<Int, Int> {
        return try {
            repository.countChildren(itemId)
        } catch (e: Exception) {
            Logger.log(TAG, "Error getChildrenCount: ${e.message}")
            Pair(0, 0)
        }
    }

    suspend fun detachAllChildren(itemId: String): Int {
        return try {
            val count = repository.detachAllChildren(itemId)

            db.itemDao().getItemById(itemId)?.let { parent ->
                val freshParent = db.itemDao().getItemById(itemId)
                if (freshParent != null) {
                    OutboxLogger.itemChanged(db, freshParent, OutboxEntity.ACTION_UPDATE, currentUser)
                }
                writeHistory(
                    itemId = itemId,
                    itemName = parent.name,
                    action = "detach_children",
                    oldValue = null,
                    newValue = "Отвязано детей: $count"
                )
            }

            Logger.log(TAG, "detachAllChildren: item=$itemId, count=$count")
            onContentsChanged()
            count
        } catch (e: Exception) {
            Logger.log(TAG, "Error detachAllChildren: ${e.message}")
            0
        }
    }

    suspend fun detachAllChildrenAndDelete(itemId: String): Boolean {
        return try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                return false
            }

            val detached = repository.detachAllChildren(itemId)
            Logger.log(TAG, "detachAllChildrenAndDelete: detached $detached children from $itemId")

            db.itemDao().deleteItem(item)
            ImageUtils.deleteLocalImage(appContext(), itemId)
            OutboxLogger.itemDeleted(db, itemId, currentUser, item.name)

            writeHistory(
                itemId = itemId,
                itemName = item.name,
                action = "delete",
                oldValue = "Было детей: $detached",
                newValue = null
            )

            Logger.log(TAG, "detachAllChildrenAndDelete: parent $itemId deleted")
            onContentsChanged()
            true
        } catch (e: Exception) {
            Logger.log(TAG, "Error detachAllChildrenAndDelete: ${e.message}")
            false
        }
    }

    suspend fun detachAllChildrenAndArchive(itemId: String, reason: String, note: String?): Boolean {
        return try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                return false
            }

            val detached = repository.detachAllChildren(itemId)
            Logger.log(TAG, "detachAllChildrenAndArchive: detached $detached children from $itemId")

            val now = System.currentTimeMillis()
            db.itemDao().archiveItem(itemId, reason, now, note)
            db.itemDao().getItemById(itemId)?.let { fresh ->
                OutboxLogger.itemChanged(db, fresh, OutboxEntity.ACTION_UPDATE, currentUser)
            }

            writeHistory(
                itemId = itemId,
                itemName = item.name,
                action = "archive",
                oldValue = "В базе (отвязано детей: $detached)",
                newValue = "В архиве (${getArchiveReasonText(reason)}${if (!note.isNullOrEmpty()) ": $note" else ""})"
            )

            Logger.log(TAG, "detachAllChildrenAndArchive: parent $itemId archived")
            onContentsChanged()
            true
        } catch (e: Exception) {
            Logger.log(TAG, "Error detachAllChildrenAndArchive: ${e.message}")
            false
        }
    }

    suspend fun getNestedContent(parentItemId: String): Pair<List<FolderEntity>, List<ItemEntity>> {
        return try {
            val folders = withContext(Dispatchers.IO) {
                repository.getNestedFolders(parentItemId)
            }
            val items = withContext(Dispatchers.IO) {
                repository.getNestedItems(parentItemId)
            }
            Pair(folders, items)
        } catch (e: Exception) {
            Logger.log(TAG, "Error getNestedContent: ${e.message}")
            Pair(emptyList(), emptyList())
        }
    }

    suspend fun createFolderInItem(
        name: String,
        parentItemId: String
    ): String? {
        return try {
            val parentItem = withContext(Dispatchers.IO) { db.itemDao().getItemById(parentItemId) }
            if (parentItem == null) {
                Logger.log(TAG, "createFolderInItem: parent item not found $parentItemId")
                return null
            }

            val parentFolderId = parentItem.parentId
            val folder = withContext(Dispatchers.IO) {
                repository.createFolderInItem(
                    name = name,
                    parentItemId = parentItemId,
                    parentFolderId = parentFolderId,
                    creator = currentUser
                )
            }

            OutboxLogger.folderChanged(db, folder, OutboxEntity.ACTION_CREATE, currentUser)

            writeHistory(
                itemId = parentItemId,
                itemName = parentItem.name,
                action = "add_nested_folder",
                oldValue = null,
                newValue = "📁 $name"
            )

            Logger.log(TAG, "createFolderInItem: created folder ${folder.id} '${folder.name}' in item $parentItemId")
            onContentsChanged()
            folder.id
        } catch (e: Exception) {
            Logger.log(TAG, "Error createFolderInItem: ${e.message}")
            null
        }
    }

    // ============================================================
    // ПАПКИ — ДЕТИ И ОТВЯЗКА
    // ============================================================

    suspend fun getFolderChildrenCount(folderId: String): Pair<Int, Int> {
        return try {
            val folderCount = withContext(Dispatchers.IO) {
                db.folderDao().getSubfolderCountInFolder(folderId)
            }
            val itemCount = withContext(Dispatchers.IO) {
                db.folderDao().getItemCountInFolder(folderId)
            }
            Pair(folderCount, itemCount)
        } catch (e: Exception) {
            Logger.log(TAG, "Error getFolderChildrenCount: ${e.message}")
            Pair(0, 0)
        }
    }

    suspend fun detachAllFolderChildren(folderId: String): Int {
        return try {
            val folder = withContext(Dispatchers.IO) { db.folderDao().getFolderById(folderId) }
            if (folder == null) {
                return 0
            }

            val subfolders = withContext(Dispatchers.IO) {
                db.folderDao().getFoldersByParent(folderId)
            }
            val items = withContext(Dispatchers.IO) {
                db.itemDao().getItemsByParent(folderId)
            }

            val now = System.currentTimeMillis()

            subfolders.forEach { sub ->
                val updated = sub.copy(parentId = null, updatedAt = now)
                withContext(Dispatchers.IO) { db.folderDao().updateFolder(updated) }
                OutboxLogger.folderChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
            }

            items.forEach { item ->
                val updated = item.copy(parentId = null, updatedDate = now, updatedBy = currentUser)
                updated.computeExpiryFields()
                withContext(Dispatchers.IO) { db.itemDao().updateItem(updated) }
                OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)
            }

            val total = subfolders.size + items.size
            Logger.log(
                TAG,
                "detachAllFolderChildren: folder=$folderId, subfolders=${subfolders.size}, items=${items.size}"
            )

            onContentsChanged()
            total
        } catch (e: Exception) {
            Logger.log(TAG, "Error detachAllFolderChildren: ${e.message}")
            0
        }
    }

    suspend fun detachAllFolderChildrenAndDelete(folderId: String): Boolean {
        return try {
            detachAllFolderChildren(folderId)

            val folder = withContext(Dispatchers.IO) { db.folderDao().getFolderById(folderId) }
            if (folder != null) {
                withContext(Dispatchers.IO) { db.folderDao().deleteFolderById(folderId) }
                OutboxLogger.folderDeleted(db, folderId, currentUser, folder.name)

                writeHistory(
                    itemId = folderId,
                    itemName = folder.name,
                    action = "delete_folder",
                    oldValue = "Папка",
                    newValue = null
                )
            }

            onContentsChanged()
            true
        } catch (e: Exception) {
            Logger.log(TAG, "Error detachAllFolderChildrenAndDelete: ${e.message}")
            false
        }
    }

    suspend fun buildArchiveBlockMessage(itemId: String): String? {
        return try {
            val children = repository.countChildren(itemId)
            val foldersCount = children.first
            val itemsCount = children.second
            val total = foldersCount + itemsCount

            if (total == 0) null
            else buildString {
                val item = db.itemDao().getItemById(itemId)
                val name = item?.name ?: "предмет"
                append("У предмета «$name» есть вложенные:\n\n")
                if (foldersCount > 0) append("📁 Папок: $foldersCount\n")
                if (itemsCount > 0) append("📦 Предметов: $itemsCount\n")
                append("\nНельзя архивировать, пока есть вложенные.\n")
                append("Сначала отвяжите их — они поднимутся в ту же папку, где лежит этот предмет.")
            }
        } catch (e: Exception) {
            Logger.log(TAG, "buildArchiveBlockMessage error: ${e.message}")
            null
        }
    }

    // ============================================================
    // ЗАЙМ
    // ============================================================

    suspend fun lendItem(itemId: String, personName: String, note: String? = null) {
        Logger.log(TAG, "lendItem: $itemId to $personName")
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item != null) {
                val updated = item.copy(
                    isLent = true,
                    lentTo = personName,
                    lentDate = System.currentTimeMillis(),
                    lentNote = note,
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = currentUser
                )
                db.itemDao().updateItem(updated)
                OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "lend",
                    oldValue = "В базе",
                    newValue = "Выдан: $personName${if (!note.isNullOrEmpty()) " ($note)" else ""}"
                )

                onContentsChanged()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error lending item: ${e.message}")
        }
    }

    suspend fun returnItem(itemId: String) {
        Logger.log(TAG, "returnItem: $itemId")
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item != null) {
                val updated = item.copy(
                    isLent = false,
                    lentTo = null,
                    lentDate = null,
                    lentNote = null,
                    returnDate = null,
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = currentUser
                )
                db.itemDao().updateItem(updated)
                OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "return",
                    oldValue = "Выдан: ${item.lentTo}",
                    newValue = "Возвращён в базу"
                )

                onContentsChanged()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error returning item: ${e.message}")
        }
    }

    suspend fun getLentItems(): List<ItemEntity> {
        return withContext(Dispatchers.IO) {
            db.itemDao().getLentItems()
        }
    }

    // ============================================================
    // АРХИВ
    // ============================================================

    suspend fun archiveItem(
        itemId: String,
        reason: String,
        note: String?,
        onBlocked: ((String) -> Unit)? = null
    ) {
        try {
            val blockMsg = buildArchiveBlockMessage(itemId)
            if (blockMsg != null) {
                Logger.log(TAG, "archiveItem: BLOCKED for $itemId (has children)")
                onBlocked?.invoke(blockMsg)
                return
            }

            val item = db.itemDao().getItemById(itemId)
            if (item != null) {
                db.itemDao().archiveItem(itemId, reason, System.currentTimeMillis(), note)
                db.itemDao().getItemById(itemId)?.let { fresh ->
                    OutboxLogger.itemChanged(db, fresh, OutboxEntity.ACTION_UPDATE, currentUser)
                }

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "archive",
                    oldValue = "В базе",
                    newValue = "В архиве (${getArchiveReasonText(reason)}${if (!note.isNullOrEmpty()) ": $note" else ""})"
                )

                onContentsChanged()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error archiving item: ${e.message}")
        }
    }

    /**
     * 🆕 v17.0.10: восстановление из архива.
     *
     * Страховка: если parentId предмета битый (папки нет) —
     * вернуть предмет в корень «🛒 Списка покупок»,
     * а не оставлять с висячим parentId.
     */
    suspend fun unarchiveItem(itemId: String) {
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                Logger.log(TAG, "unarchiveItem: item not found $itemId")
                return
            }

            val originalId = item.originalId

            // 🆕 v17.0.10: если parentId битый — подменяем на «Список покупок»
            val safeParentId: String? = resolveSafeParentId(item.parentId)

            if (originalId == null) {
                db.itemDao().unarchiveItem(itemId, System.currentTimeMillis())
                // после unarchive — выставляем безопасный parentId, если он отличался
                val fresh = db.itemDao().getItemById(itemId)
                if (fresh != null && fresh.parentId != safeParentId) {
                    val fixed = fresh.copy(parentId = safeParentId)
                    db.itemDao().updateItem(fixed)
                    OutboxLogger.itemChanged(db, fixed, OutboxEntity.ACTION_UPDATE, currentUser)
                } else if (fresh != null) {
                    OutboxLogger.itemChanged(db, fresh, OutboxEntity.ACTION_UPDATE, currentUser)
                }

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "unarchive",
                    oldValue = "В архиве",
                    newValue = "Вернули в базу"
                )

                Logger.log(TAG, "unarchiveItem: обычный возврат $itemId (parentId=$safeParentId)")
            } else {
                val original = db.itemDao().getItemById(originalId)

                if (original != null && !original.isArchived) {
                    val newQty = original.quantity + item.quantity
                    val updatedOriginal = original.copy(
                        quantity = newQty,
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = currentUser
                    )
                    updatedOriginal.computeExpiryFields()
                    db.itemDao().updateItem(updatedOriginal)
                    OutboxLogger.itemChanged(db, updatedOriginal, OutboxEntity.ACTION_UPDATE, currentUser)

                    db.itemDao().deleteItemById(itemId)
                    OutboxLogger.itemDeleted(db, itemId, currentUser, item.name)

                    ImageUtils.deleteLocalImage(appContext(), itemId)

                    writeHistory(
                        itemId = originalId,
                        itemName = original.name,
                        action = "unarchive_part",
                        oldValue = "${original.quantity} шт.",
                        newValue = "$newQty шт. (возвращено ${item.quantity})"
                    )

                    Logger.log(TAG, "unarchiveItem: часть возвращена в оригинал $originalId, qty=$newQty")
                } else {
                    val restored = item.copy(
                        isArchived = false,
                        archivedReason = null,
                        archivedDate = null,
                        archivedNote = null,
                        originalId = null,
                        parentId = safeParentId,
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = currentUser
                    )
                    restored.computeExpiryFields()
                    db.itemDao().updateItem(restored)
                    OutboxLogger.itemChanged(db, restored, OutboxEntity.ACTION_UPDATE, currentUser)

                    writeHistory(
                        itemId = itemId,
                        itemName = item.name,
                        action = "unarchive",
                        oldValue = "В архиве",
                        newValue = "Вернули в базу (оригинал не найден)"
                    )

                    Logger.log(TAG, "unarchiveItem: оригинал не найден, часть становится активной $itemId (parentId=$safeParentId)")
                }
            }

            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error unarchiving item: ${e.message}", e)
        }
    }

    /**
     * 🆕 v17.0.10: если parentId == null → null (корень базы — валидно).
     * Если parentId задан, но папки нет → «🛒 Список покупок».
     * Иначе — исходный parentId.
     */
    private suspend fun resolveSafeParentId(parentId: String?): String? {
        if (parentId == null) return null
        val exists = try {
            db.itemDao().folderExists(parentId)
        } catch (e: Exception) {
            Logger.log(TAG, "resolveSafeParentId: folderExists error for $parentId: ${e.message}")
            true
        }
        return if (exists) parentId else FolderEntity.SYSTEM_ID_SHOPPING_LIST
    }

    suspend fun getArchivedItems(): List<ItemEntity> {
        return withContext(Dispatchers.IO) { db.itemDao().getArchivedItems() }
    }

    suspend fun getArchivedItemsByReason(reason: String): List<ItemEntity> {
        return withContext(Dispatchers.IO) { db.itemDao().getArchivedItemsByReason(reason) }
    }

    // ============================================================
    // СПИСАНИЕ
    // ============================================================

    suspend fun writeOffItem(
        itemId: String,
        count: Int,
        reason: String?,
        note: String?,
        onBlocked: ((String) -> Unit)? = null
    ) {
        Logger.log(TAG, "writeOffItem: itemId=$itemId, count=$count, reason=$reason")
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                Logger.log(TAG, "writeOffItem: item not found: $itemId")
                return
            }

            if (count <= 0) {
                Logger.log(TAG, "writeOffItem: count <= 0, ignoring")
                return
            }

            if (count > item.quantity) {
                Logger.log(TAG, "writeOffItem: count ($count) > quantity (${item.quantity}), ignoring")
                return
            }

            val isFullWriteOff = (count == item.quantity)
            if (isFullWriteOff) {
                val blockMsg = buildArchiveBlockMessage(itemId)
                if (blockMsg != null) {
                    Logger.log(TAG, "writeOffItem: BLOCKED for $itemId (full write-off = archive, has children)")
                    onBlocked?.invoke(blockMsg)
                    return
                }
            }

            val now = System.currentTimeMillis()
            val reasonKey = reason ?: "used_up"

            // 🆕 v17.0.10: запоминаем папку, из которой списываем —
            // если это папка покупки и она опустеет, удалим её.
            val oldParentId = item.parentId

            if (count == item.quantity) {
                db.itemDao().archiveItem(itemId, reasonKey, now, note)
                db.itemDao().getItemById(itemId)?.let { fresh ->
                    OutboxLogger.itemChanged(db, fresh, OutboxEntity.ACTION_UPDATE, currentUser)
                }

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "write_off",
                    oldValue = "${item.quantity} шт.",
                    newValue = "Списано всё → в архиве (${getArchiveReasonText(reasonKey)}${if (!note.isNullOrEmpty()) ": $note" else ""})"
                )

                Logger.log(TAG, "writeOffItem: fully written off → archived ($reasonKey)")

                // 🆕 v17.0.10: если списали из папки покупки — проверить её на пустоту.
                // Удаляем ТОЛЬКО если в ней не осталось ни активных, ни архивных.
                if (!oldParentId.isNullOrBlank()) {
                    val deleted = PurchaseFolderHelper.deletePurchaseFolderIfEmpty(
                        db = db,
                        folderId = oldParentId,
                        changedBy = currentUser
                    )
                    if (deleted) {
                        Logger.log(TAG, "writeOffItem: опустевшая папка покупки $oldParentId удалена")
                    }
                }

            } else {
                val newId = UUID.randomUUID().toString()

                val archivedPart = item.copy(
                    id = newId,
                    quantity = count,
                    isArchived = true,
                    archivedReason = reasonKey,
                    archivedDate = now,
                    archivedNote = note,
                    originalId = itemId,
                    imageUrl = null,
                    addedDate = now,
                    updatedDate = now,
                    updatedBy = currentUser
                )
                archivedPart.computeExpiryFields()

                withContext(Dispatchers.IO) {
                    db.itemDao().insertItem(archivedPart)
                }
                OutboxLogger.itemChanged(db, archivedPart, OutboxEntity.ACTION_CREATE, currentUser)

                try {
                    val localFile = ImageUtils.getLocalImageFile(appContext(), item.id)
                    if (localFile != null && localFile.exists()) {
                        val bytes = localFile.readBytes()
                        ImageUtils.saveImageLocally(appContext(), newId, bytes)
                        Logger.log(TAG, "writeOffItem: photo copied to $newId")
                    }
                } catch (e: Exception) {
                    Logger.log(TAG, "writeOffItem: failed to copy photo: ${e.message}")
                }

                val newQty = item.quantity - count
                if (newQty == 0) {
                    withContext(Dispatchers.IO) {
                        db.itemDao().deleteItemById(itemId)
                    }
                    OutboxLogger.itemDeleted(db, itemId, currentUser, item.name)
                    ImageUtils.deleteLocalImage(appContext(), itemId)

                    writeHistory(
                        itemId = newId,
                        itemName = item.name,
                        action = "write_off",
                        oldValue = "Отделено от «${item.name}»",
                        newValue = "Списано $count шт. (оригинал удалён, ${getArchiveReasonText(reasonKey)})"
                    )

                    Logger.log(TAG, "writeOffItem: partial → original deleted (qty was $count)")
                } else {
                    val updatedOriginal = item.copy(
                        quantity = newQty,
                        updatedDate = now,
                        updatedBy = currentUser
                    )
                    updatedOriginal.computeExpiryFields()
                    withContext(Dispatchers.IO) {
                        db.itemDao().updateItem(updatedOriginal)
                    }
                    OutboxLogger.itemChanged(db, updatedOriginal, OutboxEntity.ACTION_UPDATE, currentUser)

                    writeHistory(
                        itemId = newId,
                        itemName = item.name,
                        action = "write_off",
                        oldValue = "Отделено от «${item.name}»",
                        newValue = "Списано $count шт. (${getArchiveReasonText(reasonKey)}${if (!note.isNullOrEmpty()) ": $note" else ""})"
                    )

                    writeHistory(
                        itemId = itemId,
                        itemName = item.name,
                        action = "write_off_part",
                        oldValue = "${item.quantity} шт.",
                        newValue = "$newQty шт. (списано $count)"
                    )

                    Logger.log(TAG, "writeOffItem: partial → new archive entry $newId ($count шт.), original=$newQty шт.")
                }
            }

            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error writing off item: ${e.message}", e)
        }
    }

    // ============================================================
    // РЕВИЗИЯ
    // ============================================================

    suspend fun markRevision(itemId: String) {
        Logger.log(TAG, "markRevision: itemId=$itemId")
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                Logger.log(TAG, "markRevision: item not found: $itemId")
                return
            }

            val now = System.currentTimeMillis()
            val updated = item.copy(
                lastRevisionDate = now,
                updatedDate = now,
                updatedBy = currentUser
            )
            updated.computeExpiryFields()
            db.itemDao().updateItem(updated)
            OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)

            writeHistory(
                itemId = itemId,
                itemName = item.name,
                action = "revision",
                oldValue = item.lastRevisionDate?.let { formatDateShort(it) } ?: "не проводилась",
                newValue = formatDateShort(now)
            )

            Logger.log(TAG, "markRevision: done for $itemId")

            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error marking revision: ${e.message}", e)
        }
    }

    // ============================================================
    // РАЗДЕЛЕНИЕ И ПЕРЕМЕЩЕНИЕ
    // ============================================================

    suspend fun splitAndMoveItem(
        itemId: String,
        count: Int,
        newParentId: String?,
        newParentItemId: String? = null
    ) {
        Logger.log(
            TAG,
            "splitAndMoveItem: itemId=$itemId, count=$count, " +
                "newParentId=$newParentId, newParentItemId=$newParentItemId"
        )
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                Logger.log(TAG, "splitAndMoveItem: item not found: $itemId")
                return
            }

            if (count <= 0) {
                Logger.log(TAG, "splitAndMoveItem: count <= 0, ignoring")
                return
            }

            if (count >= item.quantity) {
                Logger.log(TAG, "splitAndMoveItem: count >= quantity → full move")
                moveItem(itemId, newParentId, newParentItemId)
                return
            }

            val resolvedParentId: String? = if (newParentItemId != null) {
                val targetItem = db.itemDao().getItemById(newParentItemId)
                targetItem?.parentId
            } else {
                newParentId
            }

            if (newParentItemId != null && isItemDescendantOf(newParentItemId, itemId)) {
                Logger.log(TAG, "splitAndMoveItem: CYCLE detected (item=$itemId → item=$newParentItemId), aborting")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext(),
                        "Нельзя переместить предмет в себя или в своего потомка",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            val ctx = appContext()
            val now = System.currentTimeMillis()
            val newId = UUID.randomUUID().toString()

            val copy = item.copy(
                id = newId,
                quantity = count,
                parentId = resolvedParentId,
                parentItemId = newParentItemId,
                addedDate = now,
                updatedDate = now,
                updatedBy = currentUser
            )
            copy.computeExpiryFields()

            withContext(Dispatchers.IO) {
                db.itemDao().insertItem(copy)
            }
            OutboxLogger.itemChanged(db, copy, OutboxEntity.ACTION_CREATE, currentUser)

            try {
                val localFile = ImageUtils.getLocalImageFile(ctx, item.id)
                if (localFile != null && localFile.exists()) {
                    val bytes = localFile.readBytes()
                    ImageUtils.saveImageLocally(ctx, newId, bytes)
                }
            } catch (e: Exception) {
                Logger.log(TAG, "splitAndMoveItem: failed to copy photo: ${e.message}")
            }

            val newQty = item.quantity - count
            val updated = item.copy(
                quantity = newQty,
                updatedDate = now,
                updatedBy = currentUser
            )
            updated.computeExpiryFields()
            withContext(Dispatchers.IO) {
                db.itemDao().updateItem(updated)
            }
            OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)

            val placeName = resolvePlaceName(resolvedParentId, newParentItemId)

            writeHistory(
                itemId = newId,
                itemName = item.name,
                action = "split_in",
                oldValue = "Отделено от «${item.name}»",
                newValue = "$count шт. → $placeName"
            )

            writeHistory(
                itemId = itemId,
                itemName = item.name,
                action = "split_out",
                oldValue = "${item.quantity} шт.",
                newValue = "$newQty шт. (отделено $count → $placeName)"
            )

            Logger.log(TAG, "splitAndMoveItem: done. new=$newId ($count шт.), original=$newQty шт.")

            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error in splitAndMoveItem: ${e.message}", e)
        }
    }

    suspend fun resolvePlaceName(parentId: String?, parentItemId: String?): String {
        if (parentItemId != null) {
            val item = db.itemDao().getItemById(parentItemId)
            return if (item != null) "📦 ${item.name}" else "предмет"
        }
        if (parentId == null) return "Корень"
        return db.folderDao().getFolderById(parentId)?.name ?: "Корень"
    }

    fun getArchiveReasonText(reason: String): String {
        return when (reason) {
            "used_up" -> "🧴 Израсходовано"
            "eaten" -> "Съедено"
            "broken" -> "Сломано"
            "thrown" -> "Выброшено"
            "gifted" -> "Подарено"
            "sold" -> "Продано"
            "expired" -> "Истёк срок"
            else -> "Другое"
        }
    }

    // ============================================================
    // КОПИРОВАНИЕ
    // ============================================================

    suspend fun copyItem(itemId: String) {
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item != null) {
                val copy = item.copy(
                    id = UUID.randomUUID().toString(),
                    name = "${item.name} (копия)",
                    addedDate = System.currentTimeMillis(),
                    updatedDate = System.currentTimeMillis()
                )
                db.itemDao().insertItem(copy)

                val ctx = appContext()
                val localFile = ImageUtils.getLocalImageFile(ctx, item.id)
                if (localFile != null && localFile.exists()) {
                    val bytes = localFile.readBytes()
                    ImageUtils.saveImageLocally(ctx, copy.id, bytes)
                }

                OutboxLogger.itemChanged(db, copy, OutboxEntity.ACTION_CREATE, currentUser)

                writeHistory(
                    itemId = copy.id,
                    itemName = copy.name,
                    action = "create",
                    oldValue = "Копия «${item.name}»",
                    newValue = "${copy.quantity} шт."
                )

                onContentsChanged()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error copying item: ${e.message}")
        }
    }

    // ============================================================
    // ПАПКИ — CRUD
    // ============================================================

    suspend fun createFolder(name: String) {
        val parentId = currentFolderIdProvider()
        val folder = FolderEntity(
            name = name,
            parentId = parentId,
            parentItemId = null,
            createdBy = currentUser,
            path = name
        )

        withContext(Dispatchers.IO) { db.folderDao().insertFolder(folder) }
        OutboxLogger.folderChanged(db, folder, OutboxEntity.ACTION_CREATE, currentUser)

        writeHistory(
            itemId = folder.id,
            itemName = folder.name,
            action = "create_folder",
            oldValue = null,
            newValue = "📁 $name"
        )

        onContentsChanged()
    }

    suspend fun renameFolder(folderId: String, newName: String) {
        try {
            val folder = db.folderDao().getFolderById(folderId)
            if (folder != null) {
                if (folder.isSystem) {
                    Logger.log(TAG, "renameFolder: BLOCKED for system folder $folderId")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext(),
                            "Системную папку нельзя переименовать",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }
                val oldName = folder.name
                val updated = folder.copy(name = newName, updatedAt = System.currentTimeMillis())
                db.folderDao().updateFolder(updated)
                OutboxLogger.folderChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)

                writeHistory(
                    itemId = folderId,
                    itemName = newName,
                    action = "rename_folder",
                    oldValue = oldName,
                    newValue = newName
                )

                onContentsChanged()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error renaming folder: ${e.message}")
        }
    }

    suspend fun getFolderStats(folderId: String): Pair<Int, Int> {
        return try {
            val itemCount = db.folderDao().getItemCountInFolder(folderId)
            val folderCount = db.folderDao().getSubfolderCountInFolder(folderId)
            Pair(itemCount, folderCount)
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }

    /**
     * 🆕 v17.0.10: удаление папки.
     *
     * Если папка — подпапка системной «🛒 Покупки» (например,
     * «Покупка DD.MM.YYYY #N»), то её содержимое (активные + архивные)
     * уезжает в корень «🛒 Списка покупок», а не в корень базы.
     *
     * Для всех остальных папок поведение прежнее — всё в корень (null).
     */
   
           suspend fun deleteFolder(folderId: String) {
        try {
            val folder = db.folderDao().getFolderById(folderId)

            if (folder?.isSystem == true) {
                Logger.log(TAG, "deleteFolder: BLOCKED for system folder $folderId")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext(),
                        "Системную папку нельзя удалить",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            val folderName = folder?.name
            val isPurchaseSubfolder = folder?.parentId == FolderEntity.SYSTEM_ID_PURCHASES
            val now = System.currentTimeMillis()

            if (isPurchaseSubfolder) {
                // 🆕 v17.0.10: содержимое подпапки «🛒 Покупок» → в корень «🛒 Списка покупок»
                val moved = db.itemDao().moveAllItemsToFolder(
                    folderId = folderId,
                    newParentId = FolderEntity.SYSTEM_ID_SHOPPING_LIST,
                    date = now
                )
                Logger.log(
                    TAG,
                    "deleteFolder: purchase subfolder $folderId → moved $moved items to ShoppingList root"
                )

                // подпапок у папки покупки быть не должно, но на всякий случай поднимем в корень
                db.folderDao().moveSubfoldersToRoot(folderId)

                // логируем перенос каждого предмета в outbox (для changelog)
                val movedItems = db.itemDao().getItemsByParentRaw(FolderEntity.SYSTEM_ID_SHOPPING_LIST)
                movedItems.forEach { item ->
                    if (item.updatedDate == now) {
                        OutboxLogger.itemChanged(db, item, OutboxEntity.ACTION_UPDATE, currentUser)
                    }
                }

                db.folderDao().deleteFolderById(folderId)
                OutboxLogger.folderDeleted(db, folderId, currentUser, folderName)

                writeHistory(
                    itemId = folderId,
                    itemName = folderName,
                    action = "delete_folder",
                    oldValue = "Папка покупки (содержимое → корень «Списка покупок»)",
                    newValue = null
                )
            } else {
                // Прежнее поведение — всё в корень базы (null)
                db.itemDao().moveItemsToRoot(folderId)
                db.folderDao().moveSubfoldersToRoot(folderId)

                Logger.log(TAG, "deleteFolder: moved items/subfolders to root, folderId=$folderId")

                db.folderDao().deleteFolderById(folderId)
                OutboxLogger.folderDeleted(db, folderId, currentUser, folderName)

                writeHistory(
                    itemId = folderId,
                    itemName = folderName,
                    action = "delete_folder",
                    oldValue = "Папка (содержимое поднято в корень)",
                    newValue = null
                )
            }

            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error deleting folder: ${e.message}")
        }
    }
    // ============================================================
    // ПЕРЕМЕЩЕНИЯ
    // ============================================================

    suspend fun isFolderDescendantOf(folderId: String, potentialAncestorId: String): Boolean {
        if (folderId == potentialAncestorId) return true
        var currentId: String? = potentialAncestorId
        var depth = 0
        while (currentId != null && depth < 50) {
            if (currentId == folderId) return true
            val folder = db.folderDao().getFolderById(currentId) ?: break
            currentId = folder.parentId
            depth++
        }
        return false
    }

    suspend fun isItemDescendantOf(itemId: String, potentialAncestorItemId: String): Boolean {
        if (itemId == potentialAncestorItemId) return true
        var currentId: String? = potentialAncestorItemId
        var depth = 0
        while (currentId != null && depth < 50) {
            if (currentId == itemId) return true
            val item = db.itemDao().getItemById(currentId) ?: break
            currentId = item.parentItemId
            depth++
        }
        return false
    }

    suspend fun moveFolder(
        folderId: String,
        newParentId: String?,
        newParentItemId: String? = null
    ) {
        Logger.log(
            TAG,
            "moveFolder: folderId=$folderId, newParentId=$newParentId, newParentItemId=$newParentItemId"
        )
        try {
            val folder = db.folderDao().getFolderById(folderId)
            if (folder == null) {
                Logger.log(TAG, "moveFolder: folder not found $folderId")
                return
            }

            if (folder.isSystem) {
                Logger.log(TAG, "moveFolder: BLOCKED for system folder $folderId")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext(),
                        "Системную папку нельзя переместить",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            if (newParentItemId == null && newParentId != null) {
                if (isFolderDescendantOf(folderId, newParentId)) {
                    Logger.log(TAG, "moveFolder: CYCLE (folder→folder), aborting")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext(),
                            "Нельзя переместить папку в себя или в свою подпапку",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }
            }

            if (newParentItemId != null) {
                val ancestorItems = collectAncestorItemIdsForFolder(folderId)
                if (newParentItemId in ancestorItems) {
                    Logger.log(TAG, "moveFolder: CYCLE (folder→item), aborting")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext(),
                            "Нельзя переместить папку внутрь связанного предмета",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }
            }

            val resolvedParentId: String? = if (newParentItemId != null) {
                db.itemDao().getItemById(newParentItemId)?.parentId
            } else {
                newParentId
            }

            val updated = folder.copy(
                parentId = resolvedParentId,
                parentItemId = newParentItemId,
                updatedAt = System.currentTimeMillis()
            )
            db.folderDao().updateFolder(updated)
            OutboxLogger.folderChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)

            val placeName = resolvePlaceName(resolvedParentId, newParentItemId)
            writeHistory(
                itemId = folderId,
                itemName = folder.name,
                action = "move_folder",
                oldValue = null,
                newValue = "📁 ${folder.name} → $placeName"
            )

            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error moving folder: ${e.message}")
        }
    }

    private suspend fun collectAncestorItemIdsForFolder(folderId: String): Set<String> {
        val result = mutableSetOf<String>()
        var currentFolderId: String? = folderId
        var depth = 0

        while (currentFolderId != null && depth < 50) {
            depth++
            val folder = db.folderDao().getFolderById(currentFolderId) ?: break

            if (folder.parentItemId != null) {
                result.add(folder.parentItemId)
                var itemId: String? = folder.parentItemId
                var itemDepth = 0
                while (itemId != null && itemDepth < 50) {
                    itemDepth++
                    result.add(itemId)
                    val item = db.itemDao().getItemById(itemId) ?: break
                    itemId = item.parentItemId
                }
                currentFolderId = folder.parentId
            } else {
                currentFolderId = folder.parentId
            }
        }

        return result
    }

    suspend fun moveItem(
        itemId: String,
        newParentId: String?,
        newParentItemId: String? = null
    ) {
        Logger.log(
            TAG,
            "moveItem: itemId=$itemId, newParentId=$newParentId, newParentItemId=$newParentItemId"
        )
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                Logger.log(TAG, "moveItem: item not found $itemId")
                return
            }

            if (newParentItemId != null) {
                if (isItemDescendantOf(newParentItemId, itemId)) {
                    Logger.log(TAG, "moveItem: CYCLE detected (item=$itemId → item=$newParentItemId), aborting")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext(),
                            "Нельзя переместить предмет в себя или в своего потомка",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }
            }

            val resolvedParentId: String? = if (newParentItemId != null) {
                db.itemDao().getItemById(newParentItemId)?.parentId
            } else {
                newParentId
            }

            val updated = item.copy(
                parentId = resolvedParentId,
                parentItemId = newParentItemId,
                updatedDate = System.currentTimeMillis(),
                updatedBy = currentUser
            )
            updated.computeExpiryFields()
            db.itemDao().updateItem(updated)
            OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)

            val placeName = resolvePlaceName(resolvedParentId, newParentItemId)
            writeHistory(
                itemId = itemId,
                itemName = item.name,
                action = "move",
                oldValue = "Из предыдущего места",
                newValue = "$placeName"
            )

            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error moving item: ${e.message}")
        }
    }

    // ============================================================
    // ОБНОВЛЕНИЕ ПРЕДМЕТА
    // ============================================================

    suspend fun updateItemQuantity(itemId: String, newQty: Int) {
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item != null) {
                val oldQty = item.quantity
                if (oldQty == newQty) return

                val updated = item.copy(
                    quantity = newQty,
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = currentUser
                )
                updated.computeExpiryFields()
                db.itemDao().updateItem(updated)
                OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "quantity_change",
                    oldValue = "$oldQty шт.",
                    newValue = "$newQty шт."
                )

                onContentsChanged()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error updating quantity: ${e.message}")
        }
    }

    suspend fun updateItemFull(item: ItemEntity) {
        try {
            val old = db.itemDao().getItemById(item.id)
            if (old == null) {
                Logger.log(TAG, "updateItemFull: item not found ${item.id}")
                return
            }

            val updated = item.copy(
                updatedDate = System.currentTimeMillis(),
                updatedBy = currentUser
            )
            updated.computeExpiryFields()
            db.itemDao().updateItem(updated)
            OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, currentUser)

            val diffs = buildDiff(old, updated)
            if (diffs.isNotEmpty()) {
                writeHistory(
                    itemId = item.id,
                    itemName = updated.name,
                    action = "update",
                    oldValue = null,
                    newValue = diffs.joinToString("\n")
                )
            }

            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error updating item: ${e.message}")
        }
    }

    fun buildDiff(old: ItemEntity, new: ItemEntity): List<String> {
        val diffs = mutableListOf<String>()

        if (old.name != new.name) {
            diffs.add("Название: «${old.name}» → «${new.name}»")
        }
        if (old.quantity != new.quantity) {
            diffs.add("Количество: ${old.quantity} → ${new.quantity}")
        }
        if ((old.price ?: 0.0) != (new.price ?: 0.0)) {
            diffs.add("Цена: ${formatPrice(old.price)} → ${formatPrice(new.price)}")
        }
        if (old.expiryDate != new.expiryDate) {
            diffs.add("Срок годности: ${formatDateOrDash(old.expiryDate)} → ${formatDateOrDash(new.expiryDate)}")
        }
        if (old.purchaseDate != new.purchaseDate) {
            diffs.add("Дата покупки: ${formatDateOrDash(old.purchaseDate)} → ${formatDateOrDash(new.purchaseDate)}")
        }
        if (old.itemType != new.itemType) {
            diffs.add("Тип: ${typeName(old.itemType)} → ${typeName(new.itemType)}")
        }
        if (old.itemSubtype != new.itemSubtype) {
            diffs.add("Подтип: ${subtypeName(old.itemType, old.itemSubtype)} → ${subtypeName(new.itemType, new.itemSubtype)}")
        }
        if (old.barcode != new.barcode) {
            diffs.add("Штрих-код: ${old.barcode ?: "—"} → ${new.barcode ?: "—"}")
        }
        if (old.description != new.description) {
            val oldDesc = if (old.description.isNullOrEmpty()) "—" else "«${truncate(old.description!!, 60)}»"
            val newDesc = if (new.description.isNullOrEmpty()) "—" else "«${truncate(new.description!!, 60)}»"
            diffs.add("Описание: $oldDesc → $newDesc")
        }

        return diffs
    }

    fun formatPrice(p: Double?): String =
        if (p == null || p == 0.0) "—" else "$p ₽"

    fun formatDateOrDash(ts: Long?): String =
        if (ts == null) "—" else formatDateShort(ts)

    fun formatDateShort(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    fun typeName(t: String?): String = when (t) {
        "food" -> "🍎 Еда"
        "medicine" -> "💊 Лекарство"
        "thing" -> "📦 Предмет"
        "other" -> "🗂 Другое"
        else -> "—"
    }

    fun subtypeName(type: String?, subtype: String?): String {
        if (subtype.isNullOrEmpty()) return "—"
        return try {
            SubtypeCatalog.getDisplayName(type ?: "", subtype) ?: subtype
        } catch (e: Exception) {
            subtype
        }
    }

    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    // ============================================================
    // УДАЛЕНИЕ
    // ============================================================

    suspend fun deleteItem(itemId: String) {
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item != null) {
                db.itemDao().deleteItem(item)
                ImageUtils.deleteLocalImage(appContext(), itemId)
                OutboxLogger.itemDeleted(db, itemId, currentUser, item.name)

                writeHistory(
                    itemId = itemId,
                    itemName = item.name,
                    action = "delete",
                    oldValue = "${item.quantity} шт.",
                    newValue = null
                )

                onContentsChanged()
            }
        } catch (e: Exception) {
            Logger.log(TAG, "Error deleting item: ${e.message}")
        }
    }

    suspend fun deleteItemSafely(
        itemId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val children = repository.countChildren(itemId)
            val total = children.first + children.second

            if (total > 0) {
                val msg = "Нельзя удалить: вложенных ${children.first} папок, ${children.second} предметов"
                Logger.log(TAG, "deleteItemSafely: BLOCKED for $itemId — $msg")
                onError(msg)
                return
            }

            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                onError("Предмет не найден")
                return
            }

            db.itemDao().deleteItem(item)
            ImageUtils.deleteLocalImage(appContext(), itemId)
            OutboxLogger.itemDeleted(db, itemId, currentUser, item.name)

            writeHistory(
                itemId = itemId,
                itemName = item.name,
                action = "delete",
                oldValue = "${item.quantity} шт.",
                newValue = null
            )

            Logger.log(TAG, "deleteItemSafely: deleted $itemId")
            onSuccess()
            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error deleteItemSafely: ${e.message}")
            onError("Ошибка удаления: ${e.message}")
        }
    }

    suspend fun deleteFolderSafely(
        folderId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val folder = db.folderDao().getFolderById(folderId)
            if (folder?.isSystem == true) {
                Logger.log(TAG, "deleteFolderSafely: BLOCKED for system folder $folderId")
                onError("Системную папку нельзя удалить")
                return
            }

            val folderCount = withContext(Dispatchers.IO) {
                db.folderDao().getSubfolderCountInFolder(folderId)
            }
            val itemCount = withContext(Dispatchers.IO) {
                db.folderDao().getItemCountInFolder(folderId)
            }
            val total = folderCount + itemCount

            if (total > 0) {
                val msg = "Нельзя удалить: внутри $folderCount подпапок, $itemCount предметов"
                Logger.log(TAG, "deleteFolderSafely: BLOCKED for $folderId — $msg")
                onError(msg)
                return
            }

            if (folder == null) {
                onError("Папка не найдена")
                return
            }

            db.folderDao().deleteFolderById(folderId)
            OutboxLogger.folderDeleted(db, folderId, currentUser, folder.name)

            writeHistory(
                itemId = folderId,
                itemName = folder.name,
                action = "delete_folder",
                oldValue = "Папка",
                newValue = null
            )

            Logger.log(TAG, "deleteFolderSafely: deleted $folderId")
            onSuccess()
            onContentsChanged()
        } catch (e: Exception) {
            Logger.log(TAG, "Error deleteFolderSafely: ${e.message}")
            onError("Ошибка удаления: ${e.message}")
        }
    }
}
