package com.family.base.util

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity

/**
 * 🆕 ЧЕКИ + АВТОКОМПЛИТ: поиск «эталона» в истории и подстановка полей.
 *
 * Используется в двух местах:
 *  - ReceiptPreviewActivity — при добавлении позиций чека (A + B):
 *    ищем совпадение по имени (или barcode), подставляем поля из шаблона.
 *  - AddItemActivity — автокомплит по названию (C):
 *    пользователь выбирает шаблон, поля подставляются.
 *
 * Логика:
 *  - findMatches: ищет по точному имени (case-insensitive), архивные приоритетнее.
 *    Если точных нет — по вхождению подстроки (для C — расширенный поиск).
 *  - applyTemplate: копирует ТОЛЬКО разрешённые поля из шаблона в target:
 *      itemType, itemSubtype, description (если пусто),
 *      barcode (если пусто), imageUrl/thumbnailUrl (если пусто),
 *      usageInstructions (если пусто).
 *    НЕ копирует: id, name (уже есть), price, purchaseDate, addedDate,
 *      parentId, parentItemId, quantity, isArchived, expiryDate,
 *      manufacturedDate (у нового товара свой срок).
 *
 * Все методы — suspend, вызывать из Dispatchers.IO.
 */
object ItemHistoryHelper {

    private const val TAG = "ItemHistoryHelper"

    /** Лимит совпадений для диалога выбора (B) и автокомплита (C) */
    private const val MATCH_LIMIT = 20

    // ============================================================
    // ПОИСК СОВПАДЕНИЙ
    // ============================================================

    /**
     * Найти совпадения для названия (и опционально barcode).
     *
     * Приоритет:
     *  1. Точное совпадение по имени (COLLATE NOCASE) — архивные и активные.
     *     Если найдено хоть одно — возвращаем только их.
     *  2. Если точных нет, но есть barcode — ищем по barcode (raw, включая архив).
     *  3. Если и по barcode пусто — ищем по вхождению подстроки в имя
     *     (для ручного автокомплита; в чеке это может дать лишние совпадения,
     *     но лучше показать пользователю, чем пропустить).
     *
     * Возвращает список до MATCH_LIMIT записей, отсортированный:
     * архивные → свежие (updatedDate DESC).
     */
    suspend fun findMatches(
        db: AppDatabase,
        name: String,
        barcode: String? = null
    ): List<ItemEntity> {
        if (name.isBlank() && barcode.isNullOrBlank()) return emptyList()

        return try {
            // 1. Точное совпадение по имени
            val exact = if (name.isNotBlank()) {
                db.itemDao().findItemsByNameExact(name.trim(), limit = MATCH_LIMIT)
            } else {
                emptyList()
            }
            if (exact.isNotEmpty()) {
                Logger.log(TAG, "findMatches('$name'): exact=${exact.size}")
                return exact
            }

            // 2. По barcode
            if (!barcode.isNullOrBlank()) {
                val byBarcode = db.itemDao().getItemsByBarcodeRaw(barcode.trim())
                if (byBarcode.isNotEmpty()) {
                    Logger.log(TAG, "findMatches('$name', barcode=$barcode): byBarcode=${byBarcode.size}")
                    return byBarcode.take(MATCH_LIMIT)
                }
            }

            // 3. По вхождению подстроки
            if (name.isNotBlank()) {
                val like = db.itemDao().findItemsByNameLike(name.trim(), limit = MATCH_LIMIT)
                Logger.log(TAG, "findMatches('$name'): like=${like.size}")
                return like
            }

            emptyList()
        } catch (e: Exception) {
            Logger.log(TAG, "findMatches error for '$name': ${e.message}", e)
            emptyList()
        }
    }

    // ============================================================
    // ПОДСТАНОВКА ПОЛЕЙ ИЗ ШАБЛОНА
    // ============================================================

    /**
     * Применить шаблон к новому ItemEntity.
     *
     * Что копируется (только если в target поле пустое):
     *  - itemType (если target.itemType == null или "other")
     *  - itemSubtype (если target.itemSubtype == null или "other_other")
     *  - description (если пусто)
     *  - usageInstructions (если пусто)
     *  - barcode (если пусто и у шаблона есть)
     *  - imageUrl (если пусто)
     *  - thumbnailUrl (если пусто)
     *
     * Что НЕ копируется никогда:
     *  - id, name, price, purchaseDate, addedDate, addedBy, updatedDate,
     *    updatedBy, parentId, parentItemId, quantity,
     *    isArchived, archivedReason, archivedDate, archivedNote,
     *    expiryDate, manufacturedDate, daysUntilExpiry, isExpired,
     *    isDeleted, deletedAt, deletedBy, deleteConfirmed, syncStatus.
     *
     * @return новый ItemEntity (копия target с подставленными полями).
     */
    fun applyTemplate(target: ItemEntity, template: ItemEntity): ItemEntity {
        val type = target.itemType
        val subtype = target.itemSubtype

        // Тип: если у target не задан или "other" — берём из шаблона
        val newType = if (type.isNullOrBlank() || type == "other") {
            template.itemType ?: type
        } else {
            type
        }

        // Подтип: аналогично
        val newSubtype = if (subtype.isNullOrBlank() || subtype == "other_other") {
            template.itemSubtype ?: subtype
        } else {
            subtype
        }

        val result = target.copy(
            itemType = newType,
            itemSubtype = newSubtype,
            description = target.description?.takeIf { it.isNotBlank() }
                ?: template.description?.takeIf { it.isNotBlank() },
            usageInstructions = target.usageInstructions?.takeIf { it.isNotBlank() }
                ?: template.usageInstructions?.takeIf { it.isNotBlank() },
            barcode = target.barcode?.takeIf { it.isNotBlank() }
                ?: template.barcode?.takeIf { it.isNotBlank() },
            imageUrl = target.imageUrl?.takeIf { it.isNotBlank() }
                ?: template.imageUrl?.takeIf { it.isNotBlank() },
            thumbnailUrl = target.thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: template.thumbnailUrl?.takeIf { it.isNotBlank() }
        )

        Logger.log(
            TAG,
            "applyTemplate('${target.name}') ← '${template.name}': " +
                "type=${newType}, subtype=${newSubtype}, " +
                "hasImage=${result.imageUrl != null}, hasBarcode=${result.barcode != null}"
        )
        return result
    }

    // ============================================================
    // ОПИСАНИЕ ДЛЯ ДИАЛОГА ВЫБОРА (B)
    // ============================================================

    /**
     * Человекочитаемое описание шаблона для диалога выбора:
     *   «Хлеб Бородинский (архив, 12.10.2026)»
     *   «Молоко 3,2% (активный, 01.11.2026)»
     */
    fun describe(template: ItemEntity): String {
        val status = if (template.isArchived) "архив" else "активный"
        val date = template.purchaseDate ?: template.addedDate
        val dateStr = formatDate(date)
        val name = template.name.ifBlank { "(без названия)" }
        return "$name ($status, $dateStr)"
    }

    private fun formatDate(millis: Long): String {
        return try {
            val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            sdf.format(java.util.Date(millis))
        } catch (e: Exception) {
            "—"
        }
    }
}
