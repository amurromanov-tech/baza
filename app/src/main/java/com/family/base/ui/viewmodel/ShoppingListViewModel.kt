package com.family.base.ui.viewmodel

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxLogger
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.OutboxEntity
import com.family.base.util.Logger
import com.family.base.util.PurchaseFolderHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Делегат для экрана «🛒 Список покупок».
 *
 * Задачи:
 *  - загрузка списка предметов по трём фильтрам: Купить / Куплено / Все;
 *  - перенос предмета из «Купить» в «Куплено»
 *    (через PurchaseFolderHelper.moveItemToPurchases);
 *  - возврат предмета из «Куплено» в «Купить» (unmarkBought).
 *
 * Понятие «куплено» = предмет НЕ лежит в корне системной папки «🛒 Список покупок».
 * Понятие «купить»   = предмет лежит в корне этой папки.
 *
 * Обычный класс, без LiveData. Всю работу отдаёт в suspend-методах.
 */
class ShoppingListViewModel(
    private val db: AppDatabase
) {

    private val TAG = "ShoppingListViewModel"

    enum class Filter { TO_BUY, BOUGHT, ALL }

    data class ListResult(
        val items: List<ItemEntity>,
        val toBuyCount: Int,
        val boughtCount: Int,
        val boughtIds: Set<String>
    ) {
        val totalCount: Int get() = toBuyCount + boughtCount
    }

    // ============================================================
    // ЧТЕНИЕ
    // ============================================================

    /**
     * Возвращает список предметов для отображения.
     * Сортировка: сначала по имени (регистронезависимо).
     *
     *  - TO_BUY — только корень «Списка покупок»;
     *  - BOUGHT — все предметы внутри подпапок «Покупок»;
     *  - ALL    — оба списка.
     */
    suspend fun load(filter: Filter): ListResult = withContext(Dispatchers.IO) {
        try {
            val toBuyFolderId = FolderEntity.SYSTEM_ID_SHOPPING_LIST
            val purchasesFolderId = FolderEntity.SYSTEM_ID_PURCHASES

            val toBuyItems = db.itemDao().getItemsInFolderShallow(toBuyFolderId)

            val purchaseSubfolderIds: List<String> = db.folderDao()
                .getFoldersByParent(purchasesFolderId)
                .map { it.id }

            val boughtItems: List<ItemEntity> = if (purchaseSubfolderIds.isEmpty()) {
                emptyList()
            } else {
                db.itemDao().getItemsByParentIds(purchaseSubfolderIds)
            }

            val toBuyCount = toBuyItems.size
            val boughtCount = boughtItems.size
            val boughtIds = boughtItems.map { it.id }.toSet()

            val result = when (filter) {
                Filter.TO_BUY -> toBuyItems
                Filter.BOUGHT -> boughtItems
                Filter.ALL -> toBuyItems + boughtItems
            }.sortedBy { it.name.lowercase() }

            Logger.log(
                TAG,
                "load($filter): toBuy=$toBuyCount, bought=$boughtCount, returned=${result.size}"
            )

            ListResult(
                items = result,
                toBuyCount = toBuyCount,
                boughtCount = boughtCount,
                boughtIds = boughtIds
            )

        } catch (e: Exception) {
            Logger.log(TAG, "load error: ${e.message}", e)
            ListResult(emptyList(), 0, 0, emptySet())
        }
    }

    // ============================================================
    // ОТМЕТИТЬ «КУПЛЕНО»
    // ============================================================

    /**
     * Отметить предмет как купленный.
     *
     * Действие:
     *  1. Переместить предмет в свежую папку «Покупка DD.MM.YYYY #N»
     *     внутри системной «🛒 Покупки».
     *  2. Вернуть FolderEntity этой новой папки — чтобы активити
     *     могла открыть карточку предмета в edit-режиме.
     *
     * @return FolderEntity (новая папка покупки) или null при ошибке.
     */
    suspend fun markBought(
        itemId: String,
        changedBy: String
    ): FolderEntity? = withContext(Dispatchers.IO) {
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                Logger.log(TAG, "markBought: item not found $itemId")
                return@withContext null
            }

            val ok = PurchaseFolderHelper.moveItemToPurchases(
                db = db,
                itemId = itemId,
                changedBy = changedBy
            )

            if (!ok) {
                Logger.log(TAG, "markBought: moveItemToPurchases returned false for $itemId")
                return@withContext null
            }

            // Узнаём id свежей папки — она имеет максимальный createdAt среди детей «Покупок».
            val purchasesFolderId = FolderEntity.SYSTEM_ID_PURCHASES
            val latestFolder = db.folderDao()
                .getFoldersByParent(purchasesFolderId)
                .maxByOrNull { it.createdAt }

            Logger.log(
                TAG,
                "markBought: item '${item.name}' → '${latestFolder?.name}' (id=${latestFolder?.id})"
            )
            latestFolder

        } catch (e: Exception) {
            Logger.log(TAG, "markBought error for $itemId: ${e.message}", e)
            null
        }
    }

    // ============================================================
    // СНЯТЬ «КУПЛЕНО» (вернуть в «Список покупок»)
    // ============================================================

    /**
     * Снять отметку «куплено»: вернуть предмет в корень
     * системной папки «🛒 Список покупок».
     *
     * Используется, когда пользователь в фильтре «Куплено» снимает чекбокс.
     * Пустые папки покупок не удаляем — оставляем как есть.
     */
    suspend fun unmarkBought(
        itemId: String,
        changedBy: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                Logger.log(TAG, "unmarkBought: item not found $itemId")
                return@withContext false
            }

            val updated = item.copy(
                parentId = FolderEntity.SYSTEM_ID_SHOPPING_LIST,
                parentItemId = null,
                updatedDate = System.currentTimeMillis(),
                updatedBy = changedBy
            )
            updated.computeExpiryFields()
            db.itemDao().updateItem(updated)
            OutboxLogger.itemChanged(db, updated, OutboxEntity.ACTION_UPDATE, changedBy)

            Logger.log(
                TAG,
                "unmarkBought: item '${item.name}' → корень «Списка покупок»"
            )
            true

        } catch (e: Exception) {
            Logger.log(TAG, "unmarkBought error for $itemId: ${e.message}", e)
            false
        }
    }
}
