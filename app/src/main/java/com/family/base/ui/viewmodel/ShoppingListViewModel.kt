package com.family.base.ui.viewmodel

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.util.Logger
import com.family.base.util.PurchaseFolderHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Делегат для экрана «🛒 Список покупок».
 *
 * Задачи:
 *  - загрузка списка предметов по трём фильтрам: Купить / Куплено / Все;
 *  - счётчики для заголовка;
 *  - перенос предмета из «Купить» в «Куплено»
 *    (через PurchaseFolderHelper.moveItemToPurchases).
 *
 * Архитектурно повторяет CatalogViewModel: обычный класс, без LiveData,
 * всю работу отдаёт в suspend-методах. Активити сама решает,
 * когда и что перерисовать.
 */
class ShoppingListViewModel(
    private val db: AppDatabase
) {

    private val TAG = "ShoppingListViewModel"

    /**
     * Режим фильтра.
     *  - TO_BUY — предметы в корне системной папки «Список покупок»;
     *  - BOUGHT — предметы внутри подпапок «Покупок»;
     *  - ALL    — объединение первого и второго.
     */
    enum class Filter { TO_BUY, BOUGHT, ALL }

    data class ListResult(
        val items: List<ItemEntity>,
        val toBuyCount: Int,
        val boughtCount: Int
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
     *  - BOUGHT — все предметы, у которых parentId — подпапка «Покупок»;
     *  - ALL    — оба списка.
     */
    suspend fun load(filter: Filter): ListResult = withContext(Dispatchers.IO) {
        try {
            val toBuyFolderId = FolderEntity.SYSTEM_ID_SHOPPING_LIST
            val purchasesFolderId = FolderEntity.SYSTEM_ID_PURCHASES

            // 1. «Купить» — предметы в корне «Списка покупок».
            val toBuyItems = db.itemDao().getItemsInFolderShallow(toBuyFolderId)

            // 2. «Куплено» — предметы внутри подпапок «Покупок».
            //    Сначала найдём все подпапки внутри «Покупок»,
            //    затем возьмём предметы с parentId из этого набора.
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
                boughtCount = boughtCount
            )

        } catch (e: Exception) {
            Logger.log(TAG, "load error: ${e.message}", e)
            ListResult(emptyList(), 0, 0)
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
     *  2. Записать в историю: «Куплено».
     *
     * Не открывает карточку — это делает активити.
     * Если предмет уже в «Куплено» — повторный вызов не сработает
     * (moveItemToPurchases всё равно переложит его в новую папку,
     * поэтому активити должна сама блокировать это действие).
     *
     * @return true, если предмет переехал.
     */
    suspend fun markBought(
        itemId: String,
        changedBy: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val item = db.itemDao().getItemById(itemId)
            if (item == null) {
                Logger.log(TAG, "markBought: item not found $itemId")
                return@withContext false
            }

            val ok = PurchaseFolderHelper.moveItemToPurchases(
                db = db,
                itemId = itemId,
                changedBy = changedBy
            )

            if (ok) {
                Logger.log(TAG, "markBought: item '${item.name}' → Purchases")
            } else {
                Logger.log(TAG, "markBought: moveItemToPurchases returned false for $itemId")
            }
            ok

        } catch (e: Exception) {
            Logger.log(TAG, "markBought error for $itemId: ${e.message}", e)
            false
        }
    }

    // ============================================================
    // ОБНОВЛЕНИЕ ПОСЛЕ ВНЕШНИХ ИЗМЕНЕНИЙ
    // ============================================================

    /**
     * Хук, чтобы активити могла перезагрузить список после операций
     * (сейчас не используется, но пусть будет — пригодится для сессии 4.4).
     */
    suspend fun reload() {
        // no-op, на будущее
    }
}
