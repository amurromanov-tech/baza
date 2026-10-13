package com.family.base.ui.viewmodel

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.OutboxLogger
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.SettingsEntity
import com.family.base.data.model.SubtypeCatalog
import com.family.base.util.Logger

/**
 * Делегат MainViewModel: всё, что связано с историей изменений.
 *
 * Отвечает за:
 *  - запись новой записи истории (в БД + в outbox);
 *  - разовую миграцию старой истории (itemName + raw type codes);
 *  - замену «сырых» кодов типов/подтипов на человекочитаемые.
 *
 * НЕ знает про LiveData, UI, синк.
 * Живёт в MainViewModel как обычное поле.
 */
class HistoryViewModel(
    private val db: AppDatabase,
    private val currentUserProvider: () -> String
) {

    private val TAG = "HistoryViewModel"

    private val currentUser: String
        get() = currentUserProvider()

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    /**
     * Записать запись истории: в БД + в outbox (changelog).
     */
    suspend fun writeHistory(
        itemId: String,
        itemName: String?,
        action: String,
        oldValue: String?,
        newValue: String?
    ) {
        try {
            val entry = HistoryEntry(
                itemId = itemId,
                itemName = itemName,
                action = action,
                oldValue = oldValue,
                newValue = newValue,
                changedBy = currentUser
            )
            OutboxLogger.historyAdded(db, entry, currentUser)
        } catch (e: Exception) {
            Logger.log(TAG, "writeHistory error: action=$action, itemId=$itemId, ${e.message}")
        }
    }

    // ============================================================
    // РАЗОВАЯ МИГРАЦИЯ СТАРОЙ ИСТОРИИ (v12)
    // ============================================================

    suspend fun migrateOldHistoryEntries() {
        val settings = db.settingsDao().getSettings()
        if (settings?.historyMigratedV12 == true) {
            Logger.log(TAG, "migrateOldHistoryEntries: already done, skipping")
            return
        }

        Logger.log(TAG, "migrateOldHistoryEntries: START")

        val entriesWithoutName = db.historyDao().getEntriesWithoutItemName()
        Logger.log(TAG, "migrateOldHistoryEntries: found ${entriesWithoutName.size} entries without itemName")

        var nameFilledCount = 0
        for (entry in entriesWithoutName) {
            val item = db.itemDao().getItemById(entry.itemId)
            if (item != null) {
                try {
                    db.historyDao().updateItemName(entry.id, item.name)
                    nameFilledCount++
                } catch (e: Exception) {
                    Logger.log(TAG, "Failed to update itemName for entry ${entry.id}: ${e.message}")
                }
                continue
            }

            val folder = db.folderDao().getFolderById(entry.itemId)
            if (folder != null) {
                try {
                    db.historyDao().updateItemName(entry.id, folder.name)
                    nameFilledCount++
                } catch (e: Exception) {
                    Logger.log(TAG, "Failed to update itemName for folder entry ${entry.id}: ${e.message}")
                }
                continue
            }
        }
        Logger.log(TAG, "migrateOldHistoryEntries: filled itemName for $nameFilledCount entries")

        val entriesWithRawCodes = db.historyDao().getEntriesWithRawTypeCodes()
        Logger.log(TAG, "migrateOldHistoryEntries: found ${entriesWithRawCodes.size} entries with raw type codes")

        var rawFixedCount = 0
        for (entry in entriesWithRawCodes) {
            val newOld = entry.oldValue?.let { replaceRawCodes(it) }
            val newNew = entry.newValue?.let { replaceRawCodes(it) }

            if (newOld != entry.oldValue || newNew != entry.newValue) {
                try {
                    db.historyDao().updateEntryFields(
                        entryId = entry.id,
                        itemName = entry.itemName,
                        oldValue = newOld,
                        newValue = newNew
                    )
                    rawFixedCount++
                } catch (e: Exception) {
                    Logger.log(TAG, "Failed to update entry ${entry.id}: ${e.message}")
                }
            }
        }
        Logger.log(TAG, "migrateOldHistoryEntries: fixed raw codes in $rawFixedCount entries")

        try {
            val current = db.settingsDao().getSettings() ?: SettingsEntity()
            db.settingsDao().insertOrUpdateSettings(current.copy(historyMigratedV12 = true))
            Logger.log(TAG, "migrateOldHistoryEntries: DONE, flag set to true")
        } catch (e: Exception) {
            Logger.log(TAG, "Failed to set historyMigratedV12 flag: ${e.message}")
        }
    }

    // ============================================================
    // ЗАМЕНА «СЫРЫХ» КОДОВ
    // ============================================================

    fun replaceRawCodes(text: String): String {
        var result = text

        val typeMap = mapOf(
            "thing" to "📦 Предмет",
            "food" to "🍎 Еда",
            "medicine" to "💊 Лекарство",
            "other" to "🗂 Другое"
        )
        typeMap.forEach { (code, display) ->
            result = replaceWholeWord(result, code, display)
        }

        try {
            for (type in listOf("food", "medicine", "thing", "other")) {
                val subtypes = SubtypeCatalog.getSubtypes(type)
                subtypes.forEach { subtype ->
                    if (subtype.key.isNotEmpty()) {
                        result = replaceWholeWord(result, subtype.key, subtype.displayName)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "replaceRawCodes: SubtypeCatalog error: ${e.message}")
        }

        return result
    }

    private fun replaceWholeWord(text: String, word: String, replacement: String): String {
        if (word.isEmpty()) return text
        return try {
            text.replace(Regex("\\b${Regex.escape(word)}\\b"), replacement)
        } catch (e: Exception) {
            text
        }
    }
}
