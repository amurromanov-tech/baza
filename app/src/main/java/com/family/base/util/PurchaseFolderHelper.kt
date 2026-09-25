package com.family.base.util

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * Логика создания папок для покупок из чеков.
 *
 * Структура:
 *   BAZA (корень)
 *     └── 🛒 Покупки
 *          ├── Покупка 25.09.2026 #1
 *          ├── Покупка 25.09.2026 #2
 *          └── Покупка 26.09.2026 #1
 */
object PurchaseFolderHelper {

    private const val TAG = "PurchaseFolderHelper"

    /** Название корневой папки */
    const val PURCHASES_FOLDER_NAME = "🛒 Покупки"

    /** Шаблон имени папки покупки (без номера) */
    private const val PURCHASE_DATE_PREFIX = "Покупка"

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    // Регулярка для поиска существующих папок: "Покупка 25.09.2026 #N"
    private val PURCHASE_PATTERN = Regex(
        """^Покупка\s+(\d{2}\.\d{2}\.\d{4})\s+#(\d+)$"""
    )

    // ============================================================
    // 1. ПОИСК ИЛИ СОЗДАНИЕ КОРНЕВОЙ ПАПКИ «🛒 Покупки»
    // ============================================================
    suspend fun getOrCreatePurchasesFolder(db: AppDatabase): FolderEntity? {
        return try {
            val rootFolders = db.folderDao().getRootFolders()
            val existing = rootFolders.firstOrNull { it.name == PURCHASES_FOLDER_NAME }

            if (existing != null) {
                Logger.log(TAG, "Purchases folder exists: id=${existing.id}")
                return existing
            }

            // Создаём новую корневую папку
            val folder = FolderEntity(
                name = PURCHASES_FOLDER_NAME,
                parentId = null,
                createdBy = "user",
                path = PURCHASES_FOLDER_NAME
            )
            db.folderDao().insertFolder(folder)
            Logger.log(TAG, "Purchases folder created: id=${folder.id}")
            folder

        } catch (e: Exception) {
            Logger.log(TAG, "getOrCreatePurchasesFolder error: ${e.message}", e)
            null
        }
    }

    // ============================================================
    // 2. СОЗДАНИЕ ПАПКИ ПОКУПКИ «Покупка DD.MM.YYYY #N»
    // ============================================================
    suspend fun createPurchaseFolder(
        db: AppDatabase,
        parentId: String,
        date: Long = System.currentTimeMillis()
    ): FolderEntity? {
        return try {
            val dateStr = dateFormat.format(Date(date))
            val number = getNextNumberForDate(db, parentId, dateStr)

            val name = "$PURCHASE_DATE_PREFIX $dateStr #$number"

            val folder = FolderEntity(
                name = name,
                parentId = parentId,
                createdBy = "user",
                path = name,
                // iconUrl не ставим — будет дефолтная иконка
            )
            db.folderDao().insertFolder(folder)
            Logger.log(TAG, "Purchase folder created: $name (id=${folder.id})")
            folder

        } catch (e: Exception) {
            Logger.log(TAG, "createPurchaseFolder error: ${e.message}", e)
            null
        }
    }

    // ============================================================
    // 3. ОПРЕДЕЛЕНИЕ НОМЕРА: максимальный #N для конкретной даты +1
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
}
