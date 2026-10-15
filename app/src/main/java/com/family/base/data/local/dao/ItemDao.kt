package com.family.base.data.local.dao

import androidx.room.*
import com.family.base.data.local.entity.ItemEntity

@Dao
interface ItemDao {

    // ============================================================
    // ОСНОВНЫЕ ОПЕРАЦИИ
    // ============================================================

    @Insert
    suspend fun insertItem(item: ItemEntity)

    /** Массовая вставка (для чеков и импорта) */
    @Insert
    suspend fun insertItems(items: List<ItemEntity>)

    @Update
    suspend fun updateItem(item: ItemEntity)

    /**
     * 🆕 v15: массовое обновление (для batch-логирования в outbox).
     */
    @Update
    suspend fun updateItems(items: List<ItemEntity>)

    @Delete
    suspend fun deleteItem(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItemById(id: String): ItemEntity?

    /** Прямое удаление по id (без загрузки сущности) */
    @Query("DELETE FROM items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: String)

    // ============================================================
    // ЗАПРОСЫ БЕЗ АРХИВА (ДЛЯ ОСНОВНОГО СПИСКА)
    // ============================================================

    @Query("SELECT * FROM items WHERE isArchived = 0")
    suspend fun getAllItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE parentId = :parentId AND isArchived = 0")
    suspend fun getItemsByParent(parentId: String?): List<ItemEntity>

    @Query("SELECT * FROM items WHERE addedDate BETWEEN :startDate AND :endDate AND isArchived = 0")
    suspend fun getItemsByDateRange(startDate: Long, endDate: Long): List<ItemEntity>

    // ============================================================
    // ЗАПРОСЫ БЕЗ ФИЛЬТРА АРХИВА (ДЛЯ СИНХРОНИЗАЦИИ)
    // ============================================================

    @Query("SELECT * FROM items")
    suspend fun getAllItemsRaw(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE parentId = :parentId")
    suspend fun getItemsByParentRaw(parentId: String?): List<ItemEntity>

    // ============================================================
    // 🆕 v15: СИНХРОНИЗАЦИЯ
    // ============================================================

    /**
     * Все items со статусом pending — для SyncManager.
     */
    @Query("SELECT * FROM items WHERE syncStatus = 'pending'")
    suspend fun getItemsBySyncStatusPending(): List<ItemEntity>

    /**
     * Все items со статусом synced — для отладки.
     */
    @Query("SELECT * FROM items WHERE syncStatus = 'synced'")
    suspend fun getItemsBySyncStatusSynced(): List<ItemEntity>

    /**
     * Обновить syncStatus у одного item.
     */
    @Query("UPDATE items SET syncStatus = :status WHERE id = :itemId")
    suspend fun updateSyncStatus(itemId: String, status: String)

    /**
     * Пометить все переданные items как synced.
     */
    @Query("UPDATE items SET syncStatus = 'synced' WHERE id IN (:ids)")
    suspend fun markItemsSynced(ids: List<String>)

    /**
     * Пометить все переданные items как pending.
     */
    @Query("UPDATE items SET syncStatus = 'pending' WHERE id IN (:ids)")
    suspend fun markItemsPending(ids: List<String>)

    // ============================================================
    // ВЛОЖЕННОСТЬ (parentItemId) — предмет внутри предмета
    // ============================================================

    /**
     * Прямые дети предмета (неархивные), отсортированы по имени.
     * Используется в карточке предмета — секция «📦 Вложенные».
     */
    @Query("""
        SELECT * FROM items 
        WHERE parentItemId = :parentItemId 
          AND isArchived = 0 
        ORDER BY name ASC
    """)
    suspend fun getItemsByParentItem(parentItemId: String): List<ItemEntity>

    /**
     * Прямые дети предмета БЕЗ фильтра архива.
     * Используется при синхронизации и каскадных операциях.
     */
    @Query("""
        SELECT * FROM items 
        WHERE parentItemId = :parentItemId 
        ORDER BY name ASC
    """)
    suspend fun getItemsByParentItemRaw(parentItemId: String): List<ItemEntity>

    /**
     * Есть ли у предмета неархивные дети (любого уровня вложенности — только прямые).
     * Возвращает 1, если есть хотя бы один, иначе 0.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM items 
            WHERE parentItemId = :itemId 
              AND isArchived = 0
        )
    """)
    suspend fun hasItemChildren(itemId: String): Boolean

    /**
     * Количество неархивных прямых детей предмета.
     */
    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE parentItemId = :itemId 
          AND isArchived = 0
    """)
    suspend fun getItemChildCount(itemId: String): Int

    /**
     * Отвязать всех прямых детей от предмета-родителя.
     * Используется при «удалить родителя, отвязав детей» — дети становятся
     * привязанными к папке родителя (parentId родителя) или корневыми.
     *
     * ВАЖНО: сам перенос parentId для детей делает репозиторий,
     * здесь только очищаем parentItemId.
     */
    @Query("""
        UPDATE items 
        SET parentItemId = NULL, updatedDate = :date 
        WHERE parentItemId = :parentItemId
    """)
    suspend fun clearItemParentItem(parentItemId: String, date: Long)

    /**
     * Все потомки рекурсивно (прямые + вложенные на любую глубину).
     * Возвращает только id — для каскадных операций.
     * SQLite поддерживает WITH RECURSIVE с версии 3.8.3 (Android API 21+).
     */
    @Query("""
        WITH RECURSIVE descendants(id) AS (
            SELECT id FROM items WHERE parentItemId = :rootId
            UNION ALL
            SELECT i.id FROM items i
            INNER JOIN descendants d ON i.parentItemId = d.id
        )
        SELECT id FROM descendants
    """)
    suspend fun getAllItemDescendantIds(rootId: String): List<String>

    // ============================================================
    // ОБНОВЛЕНИЕ КОЛИЧЕСТВА
    // ============================================================

    @Query("UPDATE items SET quantity = :qty WHERE id = :itemId")
    suspend fun updateQuantity(itemId: String, qty: Int)

    // ============================================================
    // АРХИВАЦИЯ
    // ============================================================

    @Query("""
        UPDATE items SET 
            isArchived = 1, 
            archivedReason = :reason, 
            archivedDate = :date, 
            archivedNote = :note,
            updatedDate = :date,
            syncStatus = 'pending'
        WHERE id = :itemId
    """)
    suspend fun archiveItem(
        itemId: String,
        reason: String,
        date: Long,
        note: String?
    )

    @Query("""
        UPDATE items SET 
            isArchived = 0, 
            archivedReason = NULL, 
            archivedDate = NULL, 
            archivedNote = NULL,
            updatedDate = :date,
            syncStatus = 'pending'
        WHERE id = :itemId
    """)
    suspend fun unarchiveItem(itemId: String, date: Long)

    @Query("SELECT * FROM items WHERE isArchived = 1 ORDER BY archivedDate DESC")
    suspend fun getArchivedItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE isArchived = 1 AND archivedReason = :reason ORDER BY archivedDate DESC")
    suspend fun getArchivedItemsByReason(reason: String): List<ItemEntity>

    // ============================================================
    // ЧАСТИЧНОЕ СПИСАНИЕ (originalId)
    // ============================================================

    /**
     * Все архивные записи, отделённые от указанного предмета
     * (при частичном списании).
     */
    @Query("SELECT * FROM items WHERE originalId = :originalId AND isArchived = 1")
    suspend fun getItemsByOriginalId(originalId: String): List<ItemEntity>

    /**
     * Активные (неархивные) записи, отделённые от указанного предмета.
     * На случай, если оригинал вернули, а «части» ещё живы.
     */
    @Query("SELECT * FROM items WHERE originalId = :originalId AND isArchived = 0")
    suspend fun getActiveItemsByOriginalId(originalId: String): List<ItemEntity>

    // ============================================================
    // ЗАЙМ (ВЫДАЧА)
    // ============================================================

    @Query("SELECT * FROM items WHERE isLent = 1 AND isArchived = 0 ORDER BY lentDate DESC")
    suspend fun getLentItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE isLent = 1 AND lentTo = :personName ORDER BY lentDate DESC")
    suspend fun getLentItemsByPerson(personName: String): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE isLent = 1 AND isArchived = 0")
    suspend fun getLentItemsCount(): Int

    // ============================================================
    // СТАТИСТИКА АРХИВА
    // ============================================================

    @Query("SELECT SUM(price * quantity) FROM items WHERE isArchived = 1 AND price IS NOT NULL")
    suspend fun getTotalArchivedSum(): Double?

    @Query("SELECT SUM(price * quantity) FROM items WHERE isArchived = 0 AND price IS NOT NULL")
    suspend fun getTotalActiveSum(): Double?

    @Query("""
        SELECT archivedReason, SUM(price * quantity) as total
        FROM items 
        WHERE isArchived = 1 AND price IS NOT NULL 
        GROUP BY archivedReason
    """)
    suspend fun getArchivedStatsByReason(): List<ArchiveStatsRow>

    @Query("SELECT COUNT(*) FROM items WHERE isArchived = 1")
    suspend fun getArchivedItemsCount(): Int

    @Query("SELECT COUNT(*) FROM items WHERE isArchived = 0")
    suspend fun getActiveItemsCount(): Int

    // ============================================================
    // СТАТИСТИКА ЗАЙМА
    // ============================================================

    @Query("SELECT SUM(price * quantity) FROM items WHERE isLent = 1 AND isArchived = 0 AND price IS NOT NULL")
    suspend fun getTotalLentSum(): Double?

    @Query("SELECT DISTINCT lentTo FROM items WHERE isLent = 1 AND lentTo IS NOT NULL AND isArchived = 0")
    suspend fun getLentPersons(): List<String>

    // ============================================================
    // ПОИСК ПО ШТРИХ-КОДУ / QR-КОДУ
    // ============================================================

    @Query("SELECT * FROM items WHERE barcode = :barcode AND isArchived = 0")
    suspend fun getItemsByBarcode(barcode: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE barcode = :barcode")
    suspend fun getItemsByBarcodeRaw(barcode: String): List<ItemEntity>

    // ============================================================
    // АУДИТ БАЗЫ
    // ============================================================

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND (price IS NULL OR price = 0)
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutPrice(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND expiryDate IS NULL
          AND (itemType = 'food' OR itemType = 'medicine')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutExpiry(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND (barcode IS NULL OR barcode = '')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutBarcode(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND (description IS NULL OR description = '')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutDescription(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND (itemType IS NULL OR itemType = '')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutType(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND isLent = 1 
          AND lentDate IS NOT NULL 
          AND lentDate < :thresholdDate
        ORDER BY lentDate ASC
    """)
    suspend fun getItemsLentLongAgo(thresholdDate: Long): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND expiryDate IS NOT NULL 
          AND expiryDate < :now
        ORDER BY expiryDate ASC
    """)
    suspend fun getExpiredItems(now: Long): List<ItemEntity>

    // -------- РЕВИЗИЯ --------

    /**
     * Предметы БЕЗ ревизии (lastRevisionDate IS NULL).
     * Вариант A: не пересекается с «ревизия > 30 дней».
     */
    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND lastRevisionDate IS NULL
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutRevision(): List<ItemEntity>

    /**
     * Предметы, у которых ревизия была, но давно
     * (lastRevisionDate IS NOT NULL AND lastRevisionDate < :thresholdDate).
     */
    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND lastRevisionDate IS NOT NULL 
          AND lastRevisionDate < :thresholdDate
        ORDER BY lastRevisionDate ASC
    """)
    suspend fun getItemsWithOldRevision(thresholdDate: Long): List<ItemEntity>

    // -------- ДУБЛИКАТЫ --------

    @Query("""
        SELECT barcode FROM items
        WHERE isArchived = 0
          AND barcode IS NOT NULL
          AND barcode != ''
        GROUP BY barcode
        HAVING COUNT(*) > 1
    """)
    suspend fun getDuplicateBarcodes(): List<String>

    @Query("""
        SELECT * FROM items
        WHERE isArchived = 0
          AND barcode = :barcode
        ORDER BY addedDate ASC
    """)
    suspend fun getItemsBySameBarcode(barcode: String): List<ItemEntity>

    // -------- СЧЁТЧИКИ --------

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND (price IS NULL OR price = 0)
    """)
    suspend fun countItemsWithoutPrice(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND expiryDate IS NULL
          AND (itemType = 'food' OR itemType = 'medicine')
    """)
    suspend fun countItemsWithoutExpiry(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND (barcode IS NULL OR barcode = '')
    """)
    suspend fun countItemsWithoutBarcode(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND (description IS NULL OR description = '')
    """)
    suspend fun countItemsWithoutDescription(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND (itemType IS NULL OR itemType = '')
    """)
    suspend fun countItemsWithoutType(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND isLent = 1 
          AND lentDate IS NOT NULL 
          AND lentDate < :thresholdDate
    """)
    suspend fun countItemsLentLongAgo(thresholdDate: Long): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND expiryDate IS NOT NULL 
          AND expiryDate < :now
    """)
    suspend fun countExpiredItems(now: Long): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND lastRevisionDate IS NULL
    """)
    suspend fun countItemsWithoutRevision(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 
          AND lastRevisionDate IS NOT NULL 
          AND lastRevisionDate < :thresholdDate
    """)
    suspend fun countItemsWithOldRevision(thresholdDate: Long): Int

    @Query("""
        SELECT COUNT(*) FROM items
        WHERE isArchived = 0
          AND barcode IS NOT NULL
          AND barcode != ''
          AND barcode IN (
              SELECT barcode FROM items
              WHERE isArchived = 0
                AND barcode IS NOT NULL
                AND barcode != ''
              GROUP BY barcode
              HAVING COUNT(*) > 1
          )
    """)
    suspend fun countDuplicateBarcodeItems(): Int

    // ============================================================
    // РЕМОНТ БАЗЫ: ОСИРОТЕВШИЕ ПРЕДМЕТЫ
    // ============================================================

    @Query("UPDATE items SET parentId = NULL WHERE parentId = :folderId")
    suspend fun moveItemsToRoot(folderId: String)

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND parentId IS NOT NULL 
          AND parentId NOT IN (SELECT id FROM folders)
    """)
    suspend fun getOrphanItems(): List<ItemEntity>

    @Query("""
        UPDATE items 
        SET parentId = NULL 
        WHERE isArchived = 0 
          AND parentId IS NOT NULL 
          AND parentId NOT IN (SELECT id FROM folders)
    """)
    suspend fun fixOrphanItems()

    // ============================================================
    // РЕМОНТ БАЗЫ: ОСИРОТЕВШИЕ ВЛОЖЕННЫЕ (родитель-предмет удалён)
    // ============================================================

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 
          AND parentItemId IS NOT NULL 
          AND parentItemId NOT IN (SELECT id FROM items)
    """)
    suspend fun getOrphanNestedItems(): List<ItemEntity>

    @Query("""
        UPDATE items 
        SET parentItemId = NULL 
        WHERE isArchived = 0 
          AND parentItemId IS NOT NULL 
          AND parentItemId NOT IN (SELECT id FROM items)
    """)
    suspend fun fixOrphanNestedItems()

    // ============================================================
    // РЕМОНТ БАЗЫ: НЕПРАВИЛЬНЫЕ imageUrl
    // ============================================================

      @Query("SELECT * FROM items WHERE imageUrl IS NOT NULL AND imageUrl != ''")
    suspend fun getAllItemsWithImageUrl(): List<ItemEntity>

    @Query("UPDATE items SET imageUrl = NULL WHERE id = :itemId")
    suspend fun clearImageUrl(itemId: String)

    // ============================================================
    // 🆕 v16.0.14: СЧЁТЧИКИ ДЛЯ СИСТЕМНЫХ ПАПОК
    // ============================================================

    /**
     * Количество неархивных предметов НЕПОСРЕДСТВЕННО в папке.
     * Не считает предметы в подпапках и не считает вложенные в предметы.
     *
     * Используется:
     *  - для счётчика «🛒 N покупок» на плитке «🛒 Покупки»
     *    (в подпапках «Покупка DD.MM.YYYY #N»);
     *  - для счётчика «🛒 N купить» на плитке «🛒 Список покупок».
     */
     @Query("""
     SELECT COUNT(*) FROM items
     WHERE parentId = :folderId
       AND parentItemId IS NULL
       AND isArchived = 0
 """)
 suspend fun countItemsInFolderShallow(folderId: String): Int

 /**
  * 🆕 v17.0.9: количество неархивных предметов в папке И ВСЕХ её подпапках (рекурсивно).
  * Не считает вложенные предметы (parentItemId IS NULL).
  *
  * Используется:
  *  - для счётчика «🛒 N покупок» на плитке «🛒 Покупки»
  *    (предметы лежат в подпапках «Покупка DD.MM.YYYY #N»).
  */
 @Query("""
     WITH RECURSIVE folder_tree(id) AS (
         SELECT :folderId
         UNION ALL
         SELECT f.id FROM folders f
         INNER JOIN folder_tree ft ON f.parentId = ft.id
     )
     SELECT COUNT(*) FROM items
     WHERE parentId IN (SELECT id FROM folder_tree)
       AND parentItemId IS NULL
       AND isArchived = 0
 """)
 suspend fun countItemsInFolderRecursive(folderId: String): Int

    /**
     * 🆕 v16.0.15: неархивные предметы НЕПОСРЕДСТВЕННО в папке.
     * Используется на экране «🛒 Список покупок»:
     *  - фильтр «Купить» — корень системной «Списка покупок»;
     *  - фильтр «Куплено» — набор подпапок «Покупок».
     */
    @Query("""
        SELECT * FROM items
        WHERE parentId = :folderId
          AND parentItemId IS NULL
          AND isArchived = 0
        ORDER BY name COLLATE NOCASE ASC
    """)
    suspend fun getItemsInFolderShallow(folderId: String): List<ItemEntity>

    /**
     * 🆕 v16.0.15: неархивные предметы, у которых parentId — один из переданных.
     * Нужно для фильтра «Куплено» (несколько подпапок «Покупок» сразу).
     * Возвращает плоский список, сортировка по имени.
     */
    @Query("""
        SELECT * FROM items
        WHERE parentId IN (:folderIds)
          AND parentItemId IS NULL
          AND isArchived = 0
        ORDER BY name COLLATE NOCASE ASC
    """)
    suspend fun getItemsByParentIds(folderIds: List<String>): List<ItemEntity>

    // ============================================================
    // 🆕 v17.0.9: ПРОВЕРКА ПАПКИ НА ПУСТОТУ + ОТВЯЗКА АРХИВНЫХ
    // ============================================================

    /**
     * 🆕 v17.0.9: количество ВСЕХ предметов в папке — включая архивные.
     * Используется при удалении пустой папки покупки:
     * если по активным папка пуста, но есть архивные — их надо
     * перенести в корень «Списка покупок», а папку удалить.
     */
    @Query("""
        SELECT COUNT(*) FROM items
        WHERE parentId = :folderId
          AND parentItemId IS NULL
    """)
    suspend fun countItemsInFolderIncludingArchived(folderId: String): Int

    /**
     * 🆕 v17.0.9: перенести ВСЕ архивные предметы из папки в нового родителя
     * и пометить их как pending (чтобы уехали через changelog).
     *
     * Возвращает количество перенесённых предметов.
     * Используется при удалении пустой папки покупки:
     * архивные предметы «переезжают» в корень «Списка покупок»,
     * чтобы не остаться с висячим parentId.
     */
    @Query("""
        UPDATE items
        SET parentId = :newParentId,
            parentItemId = NULL,
            updatedDate = :date,
            syncStatus = 'pending'
        WHERE parentId = :folderId
          AND isArchived = 1
    """)
    suspend fun moveArchivedItemsFromFolder(
        folderId: String,
        newParentId: String,
        date: Long
    ): Int

    // ============================================================
    // 🆕 v17.0.10: ПЕРЕНОС ВСЕХ ПРЕДМЕТОВ ИЗ ПАПКИ (ПРАВКА B)
    // ============================================================

    /**
     * 🆕 v17.0.10: перенести ВСЕ предметы папки (и активные, и архивные)
     * в нового родителя, пометив их как pending (уедут через changelog).
     *
     * Возвращает количество перенесённых предметов.
     *
     * Используется при ручном удалении подпапки системной «🛒 Покупки»:
     * содержимое «Покупка DD.MM.YYYY #N» (в т.ч. архивные) уезжает
     * в корень «🛒 Списка покупок», а не в корень базы (null).
     */
    @Query("""
        UPDATE items
        SET parentId = :newParentId,
            parentItemId = NULL,
            updatedDate = :date,
            syncStatus = 'pending'
        WHERE parentId = :folderId
    """)
    suspend fun moveAllItemsToFolder(
        folderId: String,
        newParentId: String,
        date: Long
    ): Int

    // ============================================================
    // 🆕 v17.0.10: ПРОВЕРКА СУЩЕСТВОВАНИЯ ПАПКИ (ПРАВКА C)
    // ============================================================

    /**
     * 🆕 v17.0.10: существует ли папка с таким id.
     * Используется при восстановлении предмета из архива (unarchiveItem):
     * если parentId битый (папки нет) — вернуть предмет
     * в корень «🛒 Списка покупок», а не оставлять с висячим parentId.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE id = :folderId)")
    suspend fun folderExists(folderId: String): Boolean
}

// ============================================================
// МОДЕЛЬ ДЛЯ СТАТИСТИКИ АРХИВА
// ============================================================
data class ArchiveStatsRow(
    val archivedReason: String?,
    val total: Double?
)
