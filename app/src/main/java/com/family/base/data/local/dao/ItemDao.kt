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

    /**
     * Читает предмет по id БЕЗ фильтра isDeleted — нужно для синка
     * и для операций, которые должны видеть tombstones (например,
     * mergeData при проверке «а не удалён ли уже»).
     */
    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItemById(id: String): ItemEntity?

    /** Прямое удаление по id (без загрузки сущности). Только для cleanup tombstones. */
    @Query("DELETE FROM items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: String)

    // ============================================================
    // ЗАПРОСЫ БЕЗ АРХИВА (ДЛЯ ОСНОВНОГО СПИСКА)
    // Фильтр isDeleted = 0 обязателен — tombstones не показываем.
    // ============================================================

    @Query("SELECT * FROM items WHERE isArchived = 0 AND isDeleted = 0")
    suspend fun getAllItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE parentId = :parentId AND isArchived = 0 AND isDeleted = 0")
    suspend fun getItemsByParent(parentId: String?): List<ItemEntity>

    @Query("SELECT * FROM items WHERE addedDate BETWEEN :startDate AND :endDate AND isArchived = 0 AND isDeleted = 0")
    suspend fun getItemsByDateRange(startDate: Long, endDate: Long): List<ItemEntity>

    // ============================================================
    // ЗАПРОСЫ БЕЗ ФИЛЬТРОВ (ДЛЯ СИНХРОНИЗАЦИИ)
    // Видят ВСЁ, включая tombstones и архив — нужны для changelog.
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
     * Включает tombstones (у них syncStatus = 'pending', пока не уехали).
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
     * Прямые дети предмета (неархивные, не tombstone), отсортированы по имени.
     * Используется в карточке предмета — секция «📦 Вложенные».
     */
    @Query("""
        SELECT * FROM items 
        WHERE parentItemId = :parentItemId 
          AND isArchived = 0 
          AND isDeleted = 0
        ORDER BY name ASC
    """)
    suspend fun getItemsByParentItem(parentItemId: String): List<ItemEntity>

    /**
     * Прямые дети предмета БЕЗ фильтра архива и tombstone.
     * Используется при синхронизации и каскадных операциях.
     */
    @Query("""
        SELECT * FROM items 
        WHERE parentItemId = :parentItemId 
        ORDER BY name ASC
    """)
    suspend fun getItemsByParentItemRaw(parentItemId: String): List<ItemEntity>

    /**
     * Есть ли у предмета неархивные и не-tombstone дети (только прямые).
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM items 
            WHERE parentItemId = :itemId 
              AND isArchived = 0
              AND isDeleted = 0
        )
    """)
    suspend fun hasItemChildren(itemId: String): Boolean

    /**
     * Количество неархивных и не-tombstone прямых детей предмета.
     */
    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE parentItemId = :itemId 
          AND isArchived = 0
          AND isDeleted = 0
    """)
    suspend fun getItemChildCount(itemId: String): Int

    /**
     * Отвязать всех прямых детей от предмета-родителя.
     * Используется при «удалить родителя, отвязав детей».
     * Работает и с tombstones — они тоже отвязываются, чтобы
     * не висели с битым parentItemId.
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
    // Архив — это НЕ tombstone. Архивные предметы остаются в базе навсегда.
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

    @Query("SELECT * FROM items WHERE isArchived = 1 AND isDeleted = 0 ORDER BY archivedDate DESC")
    suspend fun getArchivedItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE isArchived = 1 AND archivedReason = :reason AND isDeleted = 0 ORDER BY archivedDate DESC")
    suspend fun getArchivedItemsByReason(reason: String): List<ItemEntity>

    // ============================================================
    // ЧАСТИЧНОЕ СПИСАНИЕ (originalId)
    // ============================================================

    @Query("SELECT * FROM items WHERE originalId = :originalId AND isArchived = 1 AND isDeleted = 0")
    suspend fun getItemsByOriginalId(originalId: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE originalId = :originalId AND isArchived = 0 AND isDeleted = 0")
    suspend fun getActiveItemsByOriginalId(originalId: String): List<ItemEntity>

    // ============================================================
    // ЗАЙМ (ВЫДАЧА)
    // ============================================================

    @Query("SELECT * FROM items WHERE isLent = 1 AND isArchived = 0 AND isDeleted = 0 ORDER BY lentDate DESC")
    suspend fun getLentItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE isLent = 1 AND lentTo = :personName AND isDeleted = 0 ORDER BY lentDate DESC")
    suspend fun getLentItemsByPerson(personName: String): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE isLent = 1 AND isArchived = 0 AND isDeleted = 0")
    suspend fun getLentItemsCount(): Int

    // ============================================================
    // СТАТИСТИКА АРХИВА
    // ============================================================

    @Query("SELECT SUM(price * quantity) FROM items WHERE isArchived = 1 AND price IS NOT NULL AND isDeleted = 0")
    suspend fun getTotalArchivedSum(): Double?

    @Query("SELECT SUM(price * quantity) FROM items WHERE isArchived = 0 AND price IS NOT NULL AND isDeleted = 0")
    suspend fun getTotalActiveSum(): Double?

    @Query("""
        SELECT archivedReason, SUM(price * quantity) as total
        FROM items 
        WHERE isArchived = 1 AND price IS NOT NULL AND isDeleted = 0
        GROUP BY archivedReason
    """)
    suspend fun getArchivedStatsByReason(): List<ArchiveStatsRow>

    @Query("SELECT COUNT(*) FROM items WHERE isArchived = 1 AND isDeleted = 0")
    suspend fun getArchivedItemsCount(): Int

    @Query("SELECT COUNT(*) FROM items WHERE isArchived = 0 AND isDeleted = 0")
    suspend fun getActiveItemsCount(): Int

    // ============================================================
    // СТАТИСТИКА ЗАЙМА
    // ============================================================

    @Query("SELECT SUM(price * quantity) FROM items WHERE isLent = 1 AND isArchived = 0 AND price IS NOT NULL AND isDeleted = 0")
    suspend fun getTotalLentSum(): Double?

    @Query("SELECT DISTINCT lentTo FROM items WHERE isLent = 1 AND lentTo IS NOT NULL AND isArchived = 0 AND isDeleted = 0")
    suspend fun getLentPersons(): List<String>

    // ============================================================
    // ПОИСК ПО ШТРИХ-КОДУ / QR-КОДУ
    // ============================================================

    @Query("SELECT * FROM items WHERE barcode = :barcode AND isArchived = 0 AND isDeleted = 0")
    suspend fun getItemsByBarcode(barcode: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE barcode = :barcode AND isDeleted = 0")
    suspend fun getItemsByBarcodeRaw(barcode: String): List<ItemEntity>

    // ============================================================
    // АУДИТ БАЗЫ
    // ============================================================

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND (price IS NULL OR price = 0)
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutPrice(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND expiryDate IS NULL
          AND (itemType = 'food' OR itemType = 'medicine')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutExpiry(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND (barcode IS NULL OR barcode = '')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutBarcode(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND (description IS NULL OR description = '')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutDescription(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND (itemType IS NULL OR itemType = '')
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutType(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND isLent = 1 
          AND lentDate IS NOT NULL 
          AND lentDate < :thresholdDate
        ORDER BY lentDate ASC
    """)
    suspend fun getItemsLentLongAgo(thresholdDate: Long): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND expiryDate IS NOT NULL 
          AND expiryDate < :now
        ORDER BY expiryDate ASC
    """)
    suspend fun getExpiredItems(now: Long): List<ItemEntity>

    // -------- РЕВИЗИЯ --------

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND lastRevisionDate IS NULL
        ORDER BY name ASC
    """)
    suspend fun getItemsWithoutRevision(): List<ItemEntity>

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND lastRevisionDate IS NOT NULL 
          AND lastRevisionDate < :thresholdDate
        ORDER BY lastRevisionDate ASC
    """)
    suspend fun getItemsWithOldRevision(thresholdDate: Long): List<ItemEntity>

    // -------- ДУБЛИКАТЫ --------

    @Query("""
        SELECT barcode FROM items
        WHERE isArchived = 0 AND isDeleted = 0
          AND barcode IS NOT NULL
          AND barcode != ''
        GROUP BY barcode
        HAVING COUNT(*) > 1
    """)
    suspend fun getDuplicateBarcodes(): List<String>

    @Query("""
        SELECT * FROM items
        WHERE isArchived = 0 AND isDeleted = 0
          AND barcode = :barcode
        ORDER BY addedDate ASC
    """)
    suspend fun getItemsBySameBarcode(barcode: String): List<ItemEntity>

    // -------- СЧЁТЧИКИ --------

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND (price IS NULL OR price = 0)
    """)
    suspend fun countItemsWithoutPrice(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND expiryDate IS NULL
          AND (itemType = 'food' OR itemType = 'medicine')
    """)
    suspend fun countItemsWithoutExpiry(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND (barcode IS NULL OR barcode = '')
    """)
    suspend fun countItemsWithoutBarcode(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND (description IS NULL OR description = '')
    """)
    suspend fun countItemsWithoutDescription(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND (itemType IS NULL OR itemType = '')
    """)
    suspend fun countItemsWithoutType(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND isLent = 1 
          AND lentDate IS NOT NULL 
          AND lentDate < :thresholdDate
    """)
    suspend fun countItemsLentLongAgo(thresholdDate: Long): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND expiryDate IS NOT NULL 
          AND expiryDate < :now
    """)
    suspend fun countExpiredItems(now: Long): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND lastRevisionDate IS NULL
    """)
    suspend fun countItemsWithoutRevision(): Int

    @Query("""
        SELECT COUNT(*) FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND lastRevisionDate IS NOT NULL 
          AND lastRevisionDate < :thresholdDate
    """)
    suspend fun countItemsWithOldRevision(thresholdDate: Long): Int

    @Query("""
        SELECT COUNT(*) FROM items
        WHERE isArchived = 0 AND isDeleted = 0
          AND barcode IS NOT NULL
          AND barcode != ''
          AND barcode IN (
              SELECT barcode FROM items
              WHERE isArchived = 0 AND isDeleted = 0
                AND barcode IS NOT NULL
                AND barcode != ''
              GROUP BY barcode
              HAVING COUNT(*) > 1
          )
    """)
    suspend fun countDuplicateBarcodeItems(): Int

    // ============================================================
    // РЕМОНТ БАЗЫ: ОСИРОТЕВШИЕ ПРЕДМЕТЫ
    // Ремонт НЕ трогает tombstones — они не «осиротевшие», они удалённые.
    // ============================================================

    @Query("UPDATE items SET parentId = NULL WHERE parentId = :folderId AND isDeleted = 0")
    suspend fun moveItemsToRoot(folderId: String)

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND parentId IS NOT NULL 
          AND parentId NOT IN (SELECT id FROM folders)
    """)
    suspend fun getOrphanItems(): List<ItemEntity>

    @Query("""
        UPDATE items 
        SET parentId = NULL 
        WHERE isArchived = 0 AND isDeleted = 0
          AND parentId IS NOT NULL 
          AND parentId NOT IN (SELECT id FROM folders)
    """)
    suspend fun fixOrphanItems()

    // ============================================================
    // РЕМОНТ БАЗЫ: ОСИРОТЕВШИЕ ВЛОЖЕННЫЕ
    // ============================================================

    @Query("""
        SELECT * FROM items 
        WHERE isArchived = 0 AND isDeleted = 0
          AND parentItemId IS NOT NULL 
          AND parentItemId NOT IN (SELECT id FROM items WHERE isDeleted = 0)
    """)
    suspend fun getOrphanNestedItems(): List<ItemEntity>

    @Query("""
        UPDATE items 
        SET parentItemId = NULL 
        WHERE isArchived = 0 AND isDeleted = 0
          AND parentItemId IS NOT NULL 
          AND parentItemId NOT IN (SELECT id FROM items WHERE isDeleted = 0)
    """)
    suspend fun fixOrphanNestedItems()

    // ============================================================
    // РЕМОНТ БАЗЫ: НЕПРАВИЛЬНЫЕ imageUrl
    // ============================================================

    @Query("SELECT * FROM items WHERE imageUrl IS NOT NULL AND imageUrl != '' AND isDeleted = 0")
    suspend fun getAllItemsWithImageUrl(): List<ItemEntity>

    @Query("UPDATE items SET imageUrl = NULL WHERE id = :itemId")
    suspend fun clearImageUrl(itemId: String)

    // ============================================================
    // СЧЁТЧИКИ ДЛЯ СИСТЕМНЫХ ПАПОК
    // ============================================================

    @Query("""
        SELECT COUNT(*) FROM items
        WHERE parentId = :folderId
          AND parentItemId IS NULL
          AND isArchived = 0
          AND isDeleted = 0
    """)
    suspend fun countItemsInFolderShallow(folderId: String): Int

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
          AND isDeleted = 0
    """)
    suspend fun countItemsInFolderRecursive(folderId: String): Int

    @Query("""
        SELECT * FROM items
        WHERE parentId = :folderId
          AND parentItemId IS NULL
          AND isArchived = 0
          AND isDeleted = 0
        ORDER BY name COLLATE NOCASE ASC
    """)
    suspend fun getItemsInFolderShallow(folderId: String): List<ItemEntity>

    @Query("""
        SELECT * FROM items
        WHERE parentId IN (:folderIds)
          AND parentItemId IS NULL
          AND isArchived = 0
          AND isDeleted = 0
        ORDER BY name COLLATE NOCASE ASC
    """)
    suspend fun getItemsByParentIds(folderIds: List<String>): List<ItemEntity>

    // ============================================================
    // 🆕 v20.1.1: СТАТИСТИКА ПАПОК (количество + сумма)
    // ============================================================

    /**
     * 🆕 v20.1.1: агрегаты по СПИСКУ папок за один запрос — чтобы
     * не делать N+1 при отрисовке списка каталога.
     *
     * Считаем только прямых детей-предметов:
     *  - parentItemId IS NULL — предметы внутри предметов-контейнеров
     *    не учитываем, иначе счётчик папки раздуется;
     *  - isArchived = 0 — архивные в папке не видны;
     *  - isDeleted = 0 — tombstones не считаем.
     *
     * Сумма: SUM(price * quantity). Если у всех детей price = NULL,
     * COALESCE вернёт 0.0 — отличаем «нет цены» от «пусто» по itemCount.
     *
     * ВАЖНО: parentId здесь — это id папки, а не предмета.
     */
    @Query("""
        SELECT parentId AS folderId,
               COUNT(*) AS itemCount,
               COALESCE(SUM(price * quantity), 0.0) AS totalPrice
        FROM items
        WHERE parentId IN (:folderIds)
          AND parentItemId IS NULL
          AND isArchived = 0
          AND isDeleted = 0
        GROUP BY parentId
    """)
    suspend fun getFolderStatsShallow(folderIds: List<String>): List<FolderStatsRow>

    /**
     * 🆕 v20.1.1: рекурсивная статистика для системной папки «Покупки».
     *
     * Предметы лежат в подпапках «Покупка DD.MM.YYYY #N», поэтому
     * обходим дерево папок через CTE (по folders.parentId), затем
     * агрегируем предметы по всем найденным папкам.
     *
     * Возвращает null, если в дереве нет ни одного подходящего предмета
     * (COUNT(*) в агрегатном запросе без GROUP BY всегда даёт строку,
     * поэтому проверяем itemCount > 0 в коде).
     */
    @Query("""
        WITH RECURSIVE folder_tree(id) AS (
            SELECT :folderId
            UNION ALL
            SELECT f.id FROM folders f
            INNER JOIN folder_tree ft ON f.parentId = ft.id
        )
        SELECT :folderId AS folderId,
               COUNT(*) AS itemCount,
               COALESCE(SUM(price * quantity), 0.0) AS totalPrice
        FROM items
        WHERE parentId IN (SELECT id FROM folder_tree)
          AND parentItemId IS NULL
          AND isArchived = 0
          AND isDeleted = 0
    """)
    suspend fun getFolderStatsRecursive(folderId: String): FolderStatsRow?

    /**
     * 🆕 v20.1.2: рекурсивная статистика сразу по СПИСКУ папок —
     * один запрос, без N+1.
     *
     * Для каждой папки из :folderIds строим своё поддерево
     * (root_id запоминает, от какой папки из списка пошёл обход),
     * затем LEFT JOIN с items — чтобы папки без предметов тоже
     * попали в результат (itemCount = 0, totalPrice = 0.0).
     *
     * Нужен для корня «Домашнего каталога»: «Продукты», «Лекарства»,
     * «Вещи» и т.д. теперь показывают количество и сумму по ВСЕМ
     * вложенным подпапкам, как «🛒 Покупки».
     *
     * ⚠️ Если передать и родителя, и его потомка — предметы потомка
     * попадут в счётчики обоих. Для корня каталога это не проблема
     * (там только папки верхнего уровня), но помнить надо.
     */
    @Query("""
        WITH RECURSIVE folder_tree(root_id, id) AS (
            SELECT id, id FROM folders WHERE id IN (:folderIds)
            UNION ALL
            SELECT ft.root_id, f.id
            FROM folders f
            INNER JOIN folder_tree ft ON f.parentId = ft.id
        )
        SELECT ft.root_id AS folderId,
               COUNT(i.id) AS itemCount,
               COALESCE(SUM(i.price * i.quantity), 0.0) AS totalPrice
        FROM folder_tree ft
        LEFT JOIN items i
               ON i.parentId = ft.id
              AND i.parentItemId IS NULL
              AND i.isArchived = 0
              AND i.isDeleted = 0
        GROUP BY ft.root_id
    """)
    suspend fun getFolderStatsRecursiveBatch(folderIds: List<String>): List<FolderStatsRow>

    // ============================================================
    // ПРОВЕРКА ПАПКИ НА ПУСТОТУ + ОТВЯЗКА АРХИВНЫХ
    // ============================================================

    @Query("""
        SELECT COUNT(*) FROM items
        WHERE parentId = :folderId
          AND parentItemId IS NULL
          AND isDeleted = 0
    """)
    suspend fun countItemsInFolderIncludingArchived(folderId: String): Int

    @Query("""
        UPDATE items
        SET parentId = :newParentId,
            parentItemId = NULL,
            updatedDate = :date,
            syncStatus = 'pending'
        WHERE parentId = :folderId
          AND isArchived = 1
          AND isDeleted = 0
    """)
    suspend fun moveArchivedItemsFromFolder(
        folderId: String,
        newParentId: String,
        date: Long
    ): Int

    // ============================================================
    // ПЕРЕНОС ВСЕХ ПРЕДМЕТОВ ИЗ ПАПКИ
    // ============================================================

    @Query("""
        UPDATE items
        SET parentId = :newParentId,
            parentItemId = NULL,
            updatedDate = :date,
            syncStatus = 'pending'
        WHERE parentId = :folderId
          AND isDeleted = 0
    """)
    suspend fun moveAllItemsToFolder(
        folderId: String,
        newParentId: String,
        date: Long
    ): Int

    // ============================================================
    // ПРОВЕРКА СУЩЕСТВОВАНИЯ ПАПКИ
    // ============================================================

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE id = :folderId AND isDeleted = 0)")
    suspend fun folderExists(folderId: String): Boolean

    // ============================================================
    // 🆕 v19 (№10): TOMBSTONES
    // ============================================================

    /**
     * Пометить предмет как удалённый (tombstone).
     * Не стирает запись физически — она остаётся в базе
     * и уедет через changelog, чтобы удаление разошлось по всем устройствам.
     *
     * Ставит syncStatus = 'pending' — SyncManager отправит в outbox.
     * deleteConfirmed = 0 — ещё не подтверждено, что PUSH прошёл.
     */
    @Query("""
        UPDATE items SET
            isDeleted = 1,
            deletedAt = :date,
            deletedBy = :user,
            deleteConfirmed = 0,
            updatedDate = :date,
            syncStatus = 'pending'
        WHERE id = :itemId
    """)
    suspend fun markItemDeleted(itemId: String, date: Long, user: String?)

    /**
     * Пометить tombstone как подтверждённый — PUSH в changelog прошёл успешно.
     * После этого через CLEANUP_RETENTION_MS (1 год) cleanup физически удалит запись.
     */
    @Query("UPDATE items SET deleteConfirmed = 1 WHERE id = :itemId")
    suspend fun confirmItemDeleted(itemId: String)

    /**
     * Подтверждённые tombstones старше указанного времени — для cleanup.
     * Возвращает id, которые можно физически удалить.
     */
    @Query("""
        SELECT id FROM items
        WHERE isDeleted = 1
          AND deleteConfirmed = 1
          AND deletedAt IS NOT NULL
          AND deletedAt < :thresholdMs
    """)
    suspend fun getConfirmedItemsOlderThan(thresholdMs: Long): List<String>

    /**
     * Физическое удаление подтверждённых tombstones по списку id.
     * Используется cleanup раз в N дней для очистки базы.
     */
    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun hardDeleteConfirmedItems(ids: List<String>)

    /**
     * Все tombstones — для диагностики / отладки merge.
     * В UI не используется.
     */
    @Query("SELECT * FROM items WHERE isDeleted = 1")
    suspend fun getAllTombstones(): List<ItemEntity>
}

// ============================================================
// МОДЕЛЬ ДЛЯ СТАТИСТИКИ АРХИВА
// ============================================================
data class ArchiveStatsRow(
    val archivedReason: String?,
    val total: Double?
)

// ============================================================
// 🆕 v20.1.1: МОДЕЛЬ ДЛЯ СТАТИСТИКИ ПАПКИ (количество + сумма)
// ============================================================
data class FolderStatsRow(
    val folderId: String,
    val itemCount: Int,
    val totalPrice: Double
)
