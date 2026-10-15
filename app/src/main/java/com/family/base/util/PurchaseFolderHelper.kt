package com.family.base.util

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxLogger
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * Логика создания папок для покупок из чеков.
 *
 * Структура:
 *   BAZA (корень)
 *     └── 🛒 Покупки (системная, id=system-folder-purchases)
 *          ├── Покупка 25.09.2026 #1
 *          ├── Покупка 25.09.2026 #2
 *          └── Покупка 26.09.2026 #1
 *
 *     └── 🛒 Список покупок (системная, id=system-folder-shopping-list)
 *
 * 🆕 v16.0.15: «🛒 Покупки» — системная папка с фиксированным id.
 * Создаётся миграцией 16→17, а не здесь. Здесь только ensure/навигация.
 *
 * 🆕 v17.0.9: добавлено автоудаление пустых папок покупок
 * (при unmarkBought — см. deletePurchaseFolderIfEmpty).
 */
object PurchaseFolderHelper {

    private const val TAG = "PurchaseFolderHelper"

    /** Название корневой папки «Покупки» (для отображения) */
    const val PURCHASES_FOLDER_NAME = "🛒 Покупки"

    /** Название системной папки «Список покупок» (для отображения) */
    const val SHOPPING_LIST_FOLDER_NAME = "🛒 Список покупок"

    /** Шаблон имени папки покупки (без номера) */
    private const val PURCHASE_DATE_PREFIX = "Покупка"

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    // Регулярка для поиска существующих папок: "Покупка 25.09.2026 #N"
    private val PURCHASE_PATTERN = Regex(
        """^Покупка\s+(\d{2}\.\d{2}\.\d{4})\s+#(\d+)$"""
    )

    // ============================================================
    // 1. СИСТЕМНАЯ ПАПКА «🛒 ПОКУПКИ»
    // ============================================================

    /**
     * Возвращает системную папку «🛒 Покупки» (по фиксированному id).
     * Если её нет — создаёт (страховка на случай, если миграция почему-то не отработала).
     */
    suspend fun getOrCreatePurchasesFolder(db: AppDatabase): FolderEntity? {
        return try {
            val existing = db.folderDao()
                .getFolderById(FolderEntity.SYSTEM_ID_PURCHASES)
            if (existing != null) {
                Logger.log(TAG, "Purchases folder exists: id=${existing.id}")
                return existing
            }

            val folder = FolderEntity(
                id = FolderEntity.SYSTEM_ID_PURCHASES,
                name = PURCHASES_FOLDER_NAME,
                parentId = null,
                parentItemId = null,
                createdBy = "system",
                path = PURCHASES_FOLDER_NAME,
                isSystem = true,
                systemKey = FolderEntity.SYSTEM_KEY_PURCHASES
            )
            db.folderDao().insertFolder(folder)
            Logger.log(TAG, "Purchases folder created (fallback): id=${folder.id}")
            folder

        } catch (e: Exception) {
            Logger.log(TAG, "getOrCreatePurchasesFolder error: ${e.message}", e)
            null
        }
    }

    // ============================================================
    // 1б. СИСТЕМНАЯ ПАПКА «🛒 СПИСОК ПОКУПОК»
    // ============================================================

    /**
     * Возвращает системную папку «🛒 Список покупок» (по фиксированному id).
     * Если её нет — создаёт (страховка).
     */
    suspend fun getOrCreateShoppingListFolder(db: AppDatabase): FolderEntity? {
        return try {
            val existing = db.folderDao()
                .getFolderById(FolderEntity.SYSTEM_ID_SHOPPING_LIST)
            if (existing != null) {
                Logger.log(TAG, "ShoppingList folder exists: id=${existing.id}")
                return existing
            }

            val folder = FolderEntity(
                id = FolderEntity.SYSTEM_ID_SHOPPING_LIST,
                name = SHOPPING_LIST_FOLDER_NAME,
                parentId = null,
                parentItemId = null,
                createdBy = "system",
                path = SHOPPING_LIST_FOLDER_NAME,
                isSystem = true,
                systemKey = FolderEntity.SYSTEM_KEY_SHOPPING_LIST
            )
            db.folderDao().insertFolder(folder)
            Logger.log(TAG, "ShoppingList folder created (fallback): id=${folder.id}")
            folder

        } catch (e: Exception) {
            Logger.log(TAG, "getOrCreateShoppingListFolder error: ${e.message}", e)
            null
        }
    }

    // ============================================================
    // 2. СОЗДАНИЕ ПАПКИ ПОКУПКИ «Покупка DD.MM.YYYY #N»
    // ============================================================

    /**
     * Создаёт папку «Покупка DD.MM.YYYY #N» внутри родителя.
     * Родитель — как правило, `system-folder-purchases`.
     * Номер N подбирается как max+1 для этой даты.
     */
    suspend fun createPurchaseFolder(
        db: AppDatabase,
        parentId: String,
        date: Long = System.currentTimeMillis(),
        changedBy: String = "user"
    ): FolderEntity? {
        return try {
            val dateStr = dateFormat.format(Date(date))
            val number = getNextNumberForDate(db, parentId, dateStr)

            val name = "$PURCHASE_DATE_PREFIX $dateStr #$number"

            val folder = FolderEntity(
                name = name,
                parentId = parentId,
                createdBy = changedBy,
                path = name
            )
            db.folderDao().insertFolder(folder)
            OutboxLogger.folderChanged(db, folder, OutboxEntity.ACTION_CREATE, changedBy)
            Logger.log(TAG, "Purchase folder created: $name (id=${folder.id})")
            folder

        } catch (e: Exception) {
            Logger.log(TAG, "createPurchaseFolder error: ${e.message}", e)
            null
        }
    }

    // ============================================================
    // 3. НОМЕР: max #N для конкретной даты +1
    // ============================================================
    private suspend fun getNextNumberForDate(
        db: AppDatabase,
        parentId: String,
        dateStr: String
    ): Int {
        return try {
            val siblings = db.folderDao().getFoldersByParent(parentId)

            var maxNumber = 0
            siblings.forEach { folder ->
                val match = PURCHASE_PATTERN.matchEntire(folder.name)
                if (match != null) {
                    val folderDate = match.groupValues[1]
                    val folderNum = match.groupValues[2].toIntOrNull() ?: 0

                    if (folderDate == dateStr && folderNum > maxNumber) {
                        maxNumber = folderNum
                    }
                }
            }

            val next = maxNumber + 1
            Logger.log(TAG, "Next number for $dateStr in folder $parentId: $next (max found: $maxNumber)")
            next

        } catch (e: Exception) {
            Logger.log(TAG, "getNextNumberForDate error: ${e.message}", e)
            1
        }
    }

    // ============================================================
    // 4. ПЕРЕМЕЩЕНИЕ ПРЕДМЕТА В СВЕЖУЮ ПАПКУ ПОКУПКИ
    // ============================================================

    /**
     * «Купить → куплено».
     *
     * Что делает:
     *  1. Находит/создаёт системную папку «🛒 Покупки».
     *  2. Создаёт внутри неё папку «Покупка DD.MM.YYYY #N»
     *     (номер — по сегодняшней дате).
     *  3. Перемещает предмет (itemId) в эту папку:
     *     parentId = <новая папка>, parentItemId = null.
     *  4. Пишет в outbox (ACTION_UPDATE на item).
     *
     * 🆕 v16.0.15: возвращает FolderEntity созданной папки покупки —
     * чтобы вызывающий мог открыть карточку предмета в edit-режиме.
     *
     * @return FolderEntity — если предмет переехал, иначе null.
     */
    suspend fun moveItemToPurchases(
        db: AppDatabase,
        itemId: String,
        changedBy: String,
        date: Long = System.currentTimeMillis()
    ): FolderEntity? {
        return try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                Logger.log(TAG, "moveItemToPurchases: item not found $itemId")
                return null
            }

            val purchases = getOrCreatePurchasesFolder(db)
            if (purchases == null) {
                Logger.log(TAG, "moveItemToPurchases: cannot ensure purchases folder")
                return null
            }

            val purchaseFolder = createPurchaseFolder(
                db = db,
                parentId = purchases.id,
                date = date,
                changedBy = changedBy
            )
            if (purchaseFolder == null) {
                Logger.log(TAG, "moveItemToPurchases: cannot create purchase folder")
                return null
            }

            val now = System.currentTimeMillis()
            val updated: ItemEntity = item.copy(
                parentId = purchaseFolder.id,
                parentItemId = null,
                updatedDate = now,
                updatedBy = changedBy
            )
            updated.computeExpiryFields()
            db.itemDao().updateItem(updated)
            OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, changedBy)

            Logger.log(
                TAG,
                "moveItemToPurchases: item $itemId '${item.name}' → ${purchaseFolder.name}"
            )
            purchaseFolder

        } catch (e: Exception) {
            Logger.log(TAG, "moveItemToPurchases error for $itemId: ${e.message}", e)
            null
        }
    }

    // ============================================================
    // 5. 🆕 v17.0.9: АВТОУДАЛЕНИЕ ПУСТОЙ ПАПКИ ПОКУПКИ
    // ============================================================

    /**
     * Удаляет папку покупки, если она опустела после unmarkBought.
     *
     * Условия удаления (все три):
     *  1. Папка лежит ВНУТРИ системной «🛒 Покупки» (parentId == SYSTEM_ID_PURCHASES)
     *     и её имя матчится паттерну «Покупка DD.MM.YYYY #N».
     *  2. В папке НЕТ активных предметов (countItemsInFolderShallow == 0).
     *  3. В папке НЕТ подпапок (getSubfolderCountInFolder == 0).
     *
     * Что делает при удалении:
     *  - архивные предметы (если есть) переносит в корень «🛒 Списка покупок»
     *    (parentId = SYSTEM_ID_SHOPPING_LIST) и логирует ACTION_UPDATE;
     *  - удаляет папку из БД;
     *  - пишет ACTION_DELETE в outbox (чтобы папка уехала на Диск).
     *
     * @return true — если папка была удалена; false — если не выполнены условия или ошибка.
     */
    suspend fun deletePurchaseFolderIfEmpty(
        db: AppDatabase,
        folderId: String,
        changedBy: String
    ): Boolean {
        return try {
            if (folderId.isBlank()) return false

            // 1. Это точно папка покупки внутри «🛒 Покупок»?
            if (!isPurchaseSubfolder(db, folderId)) {
                Logger.log(TAG, "deletePurchaseFolderIfEmpty: $folderId — не папка покупки, пропускаем")
                return false
            }

            // 2. Нет активных предметов?
            val activeCount = db.itemDao().countItemsInFolderShallow(folderId)
            if (activeCount > 0) {
                Logger.log(TAG, "deletePurchaseFolderIfEmpty: $folderId — активных=$activeCount, не удаляем")
                return false
            }

            // 3. Нет подпапок?
            val subfolderCount = db.folderDao().getSubfolderCountInFolder(folderId)
            if (subfolderCount > 0) {
                Logger.log(TAG, "deletePurchaseFolderIfEmpty: $folderId — подпапок=$subfolderCount, не удаляем")
                return false
            }

            val folder = db.folderDao().getFolderById(folderId)
            if (folder == null) {
                Logger.log(TAG, "deletePurchaseFolderIfEmpty: $folderId — папка не найдена")
                return false
            }

            // 4. Переносим архивные предметы в корень «Списка покупок»
            val now = System.currentTimeMillis()
            val totalCount = db.itemDao().countItemsInFolderIncludingArchived(folderId)
            if (totalCount > 0) {
                db.itemDao().moveArchivedItemsFromFolder(
                    folderId = folderId,
                    newParentId = FolderEntity.SYSTEM_ID_SHOPPING_LIST,
                    date = now
                )
                Logger.log(
                    TAG,
                    "deletePurchaseFolderIfEmpty: из '${folder.name}' перенесено архивных=$totalCount → корень «Списка покупок»"
                )

                // Логируем в outbox каждый перенесённый предмет — чтобы changelog увёз
                val archivedItems = db.itemDao()
                    .getItemsByParentRaw(folderId)
                    .filter { it.isArchived }

                archivedItems.forEach { item ->
                    val moved = item.copy(
                        parentId = FolderEntity.SYSTEM_ID_SHOPPING_LIST,
                        parentItemId = null,
                        updatedDate = now,
                        updatedBy = changedBy
                    )
                    OutboxLogger.itemChanged(db, moved, OutboxEntity.ACTION_UPDATE, changedBy)
                }
            }

            // 5. Удаляем папку
            db.folderDao().deleteFolderById(folderId)
            OutboxLogger.folderChanged(db, folder, OutboxEntity.ACTION_DELETE, changedBy)

            Logger.log(TAG, "deletePurchaseFolderIfEmpty: '${folder.name}' (id=$folderId) удалена")
            true

        } catch (e: Exception) {
            Logger.log(TAG, "deletePurchaseFolderIfEmpty error for $folderId: ${e.message}", e)
            false
        }
    }

    /**
     * Проверяет, что папка — подпапка системной «🛒 Покупки»
     * и её имя соответствует шаблону «Покупка DD.MM.YYYY #N».
     *
     * Защита от случайного удаления чужой папки.
     */
    private suspend fun isPurchaseSubfolder(
        db: AppDatabase,
        folderId: String
    ): Boolean {
        return try {
            val folder = db.folderDao().getFolderById(folderId) ?: return false
            if (folder.parentId != FolderEntity.SYSTEM_ID_PURCHASES) return false
            PURCHASE_PATTERN.matchEntire(folder.name) != null
        } catch (e: Exception) {
            Logger.log(TAG, "isPurchaseSubfolder error for $folderId: ${e.message}", e)
            false
        }
    }
}
