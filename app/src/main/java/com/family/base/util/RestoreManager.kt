package com.family.base.util

import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.HistoryEntry
import com.family.base.data.local.entity.ItemEntity
import com.family.base.data.local.entity.TaskEntity
import com.family.base.data.repository.CatalogRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🆕 v17.2.7: восстановление БД из restore-файлов.
 *
 * Что делает:
 *  1. Скачивает 4 файла с Яндекс.Диска из /BAZA/restore/:
 *      - restore_items.json
 *      - restore_folders.json
 *      - restore_history.json
 *      - restore_tasks.json
 *  2. Парсит их через Gson.
 *  3. Заливает в БД через DAO с INSERT OR REPLACE (по id).
 *     - Существующие объекты с тем же id ПЕРЕЗАПИСЫВАЮТСЯ.
 *     - Новые объекты ДОБАВЛЯЮТСЯ.
 *     - НИЧЕГО не удаляется.
 *  4. Возвращает RestoreResult со счётчиками.
 *
 * ⚠️ Не трогает:
 *  - outbox (pending-очередь синка)
 *  - processed_changelogs
 *  - sync_info (last_modified)
 *  - settings
 *
 * Это безопасно: восстановление только ДОБАВЛЯЕТ/ОБНОВЛЯЕТ items,
 * folders, history, tasks. Оно не удаляет данные.
 */
class RestoreManager(
    private val db: AppDatabase,
    private val repository: CatalogRepository
) {

    private val TAG = "RestoreManager"
    private val gson = Gson()

    companion object {
        const val FILE_ITEMS = "restore_items.json"
        const val FILE_FOLDERS = "restore_folders.json"
        const val FILE_HISTORY = "restore_history.json"
        const val FILE_TASKS = "restore_tasks.json"
    }

    /**
     * Результат восстановления.
     */
    data class RestoreResult(
        val itemsInserted: Int,
        val itemsUpdated: Int,
        val foldersInserted: Int,
        val foldersUpdated: Int,
        val historyInserted: Int,
        val tasksInserted: Int,
        val tasksUpdated: Int,
        val itemsTotal: Int,
        val foldersTotal: Int,
        val historyTotal: Int,
        val tasksTotal: Int,
        val errors: List<String>
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
        val isEmpty: Boolean
            get() = itemsTotal == 0 && foldersTotal == 0 && historyTotal == 0 && tasksTotal == 0

        fun summary(): String {
            val sb = StringBuilder()
            sb.append("📦 Восстановление завершено\n\n")
            sb.append("Предметы:     ${itemsTotal} (новых: ${itemsInserted}, обновлено: ${itemsUpdated})\n")
            sb.append("Папки:        ${foldersTotal} (новых: ${foldersInserted}, обновлено: ${foldersUpdated})\n")
            sb.append("История:      ${historyTotal} (новых: ${historyInserted})\n")
            sb.append("Задачи:       ${tasksTotal} (новых: ${tasksInserted}, обновлено: ${tasksUpdated})\n")
            if (hasErrors) {
                sb.append("\n⚠️ Ошибки:\n")
                errors.forEach { sb.append("• $it\n") }
            }
            return sb.toString().trim()
        }
    }

    /**
     * Скачивает restore-файлы с Яндекс.Диска и восстанавливает БД.
     *
     * @param onProgress колбэк прогресса: (этап, описание).
     */
    suspend fun restoreFromDisk(
        onProgress: (stage: String, message: String) -> Unit = { _, _ -> }
    ): RestoreResult = withContext(Dispatchers.IO) {

        val errors = mutableListOf<String>()

        // ============================================================
        // 1. Скачиваем все 4 файла
        // ============================================================
        onProgress("download", "Скачивание restore_items.json…")
        val itemsJson = repository.downloadRestoreFile(FILE_ITEMS)
        if (itemsJson == null) errors.add("Не удалось скачать $FILE_ITEMS")

        onProgress("download", "Скачивание restore_folders.json…")
        val foldersJson = repository.downloadRestoreFile(FILE_FOLDERS)
        if (foldersJson == null) errors.add("Не удалось скачать $FILE_FOLDERS")

        onProgress("download", "Скачивание restore_history.json…")
        val historyJson = repository.downloadRestoreFile(FILE_HISTORY)
        if (historyJson == null) errors.add("Не удалось скачать $FILE_HISTORY")

        onProgress("download", "Скачивание restore_tasks.json…")
        val tasksJson = repository.downloadRestoreFile(FILE_TASKS)
        if (tasksJson == null) errors.add("Не удалось скачать $FILE_TASKS")

        if (itemsJson == null && foldersJson == null && historyJson == null && tasksJson == null) {
            Logger.log(TAG, "restoreFromDisk: all files missing, aborting")
            return@withContext RestoreResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, errors)
        }

        // ============================================================
        // 2. Парсим
        // ============================================================
        onProgress("parse", "Разбор JSON…")

        val items: List<ItemEntity> = parseList(itemsJson, FILE_ITEMS, errors)
        val folders: List<FolderEntity> = parseList(foldersJson, FILE_FOLDERS, errors)
        val history: List<HistoryEntry> = parseList(historyJson, FILE_HISTORY, errors)
        val tasks: List<TaskEntity> = parseList(tasksJson, FILE_TASKS, errors)

        Logger.log(
            TAG,
            "restoreFromDisk: parsed items=${items.size}, folders=${folders.size}, " +
                "history=${history.size}, tasks=${tasks.size}"
        )

        // ============================================================
        // 3. Заливаем в БД
        // ============================================================

        // ---------- FOLDERS ----------
        onProgress("folders", "Восстановление папок…")
        var foldersInserted = 0
        var foldersUpdated = 0
        for (folder in folders) {
            try {
                val existing = db.folderDao().getFolderById(folder.id)
                if (existing == null) {
                    db.folderDao().insertFolder(folder)
                    foldersInserted++
                } else {
                    // Не перезаписываем системные флаги у существующих системных папок
                    val safe = if (existing.isSystem) {
                        folder.copy(
                            name = existing.name,
                            parentId = existing.parentId,
                            parentItemId = existing.parentItemId,
                            isSystem = existing.isSystem,
                            systemKey = existing.systemKey,
                            path = existing.path
                        )
                    } else {
                        folder
                    }
                    db.folderDao().updateFolder(safe)
                    foldersUpdated++
                }
            } catch (e: Exception) {
                errors.add("Folder ${folder.id}: ${e.message}")
            }
        }

        // ---------- ITEMS ----------
        onProgress("items", "Восстановление предметов…")
        var itemsInserted = 0
        var itemsUpdated = 0
        for (item in items) {
            try {
                val existing = db.itemDao().getItemById(item.id)
                if (existing == null) {
                    db.itemDao().insertItem(item)
                    itemsInserted++
                } else {
                    db.itemDao().updateItem(item)
                    itemsUpdated++
                }
            } catch (e: Exception) {
                errors.add("Item ${item.id}: ${e.message}")
            }
        }

        // ---------- HISTORY ----------
        onProgress("history", "Восстановление истории…")
        var historyInserted = 0
        try {
            // getAllIds может быть тяжелым, но у нас ~800 записей — ок
            val existingIds = db.historyDao().getAllIds().toSet()
            val toInsert = history.filter { it.id !in existingIds }
            if (toInsert.isNotEmpty()) {
                db.historyDao().insertAll(toInsert)
                historyInserted = toInsert.size
            }
        } catch (e: Exception) {
            errors.add("History insert: ${e.message}")
        }

        // ---------- TASKS ----------
        onProgress("tasks", "Восстановление задач…")
        var tasksInserted = 0
        var tasksUpdated = 0
        for (task in tasks) {
            try {
                val existing = db.taskDao().getById(task.id)
                if (existing == null) {
                    db.taskDao().insert(task)
                    tasksInserted++
                } else {
                    db.taskDao().update(task)
                    tasksUpdated++
                }
            } catch (e: Exception) {
                errors.add("Task ${task.id}: ${e.message}")
            }
        }

        onProgress("done", "Готово")

        val result = RestoreResult(
            itemsInserted = itemsInserted,
            itemsUpdated = itemsUpdated,
            foldersInserted = foldersInserted,
            foldersUpdated = foldersUpdated,
            historyInserted = historyInserted,
            tasksInserted = tasksInserted,
            tasksUpdated = tasksUpdated,
            itemsTotal = items.size,
            foldersTotal = folders.size,
            historyTotal = history.size,
            tasksTotal = tasks.size,
            errors = errors
        )

        Logger.log(TAG, "restoreFromDisk: DONE — ${result.summary().replace("\n", " | ")}")
        return@withContext result
    }

    /**
     * Парсит JSON-массив в список сущностей.
     * Если json == null или парсинг упал — возвращает пустой список и пишет ошибку.
     */
    private inline fun <reified T> parseList(
        json: String?,
        fileName: String,
        errors: MutableList<String>
    ): List<T> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<T>>() {}.type
            val parsed: List<T> = gson.fromJson(json, type) ?: emptyList()
            parsed
        } catch (e: Exception) {
            Logger.log(TAG, "parseList error for $fileName: ${e.message}")
            errors.add("Ошибка разбора $fileName: ${e.message}")
            emptyList()
        }
    }

    /**
     * Утилита: форматирует timestamp в человекочитаемую дату.
     */
    fun formatDate(ts: Long): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
