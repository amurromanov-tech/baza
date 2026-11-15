package com.family.base.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 🆕 v21.1.0+ (Шаг 1.7-1, миграция 22→23): ПАПКИ РЕЦЕПТОВ.
 *
 * Отдельная таблица recipe_folders — ТОЛЬКО для «Моих рецептов»
 * (source = "user"). «Рецепты ИИ» (source = "ai") живут без папок:
 * там просто список сгенерированных рецептов.
 *
 * Зачем отдельная таблица, а не поле в FolderEntity:
 *  - FolderEntity — это папки ОСНОВНОГО каталога (предметы, чеки,
 *    галерея, системные папки). Смешивать с папками рецептов нельзя:
 *    у них разная семантика, разные DAO, разный синк.
 *  - Папки рецептов не должны появляться в основном каталоге
 *    и не должны участвовать в folders.json (mergeData).
 *  - Синк папок рецептов — через changelog (ENTITY_RECIPE_FOLDER),
 *    как items/folders/tasks. Никаких файлов на Диске — только БД.
 *
 * ВЛОЖЕННОСТЬ:
 *  - parentId = null  → папка в корне «Моих рецептов»;
 *  - parentId != null → папка внутри другой папки рецептов.
 *  Глубина не ограничена (как в основном каталоге).
 *
 * РЕЦЕПТЫ БЕЗ ПАПКИ:
 *  - лежат в корне «Моих рецептов» рядом с папками
 *    (RecipeEntity.folderId IS NULL). См. RecipeDao.getRootRecipes.
 *
 * УДАЛЕНИЕ:
 *  - папку нельзя удалить, пока в ней есть рецепты (RecipeDao.countByFolder > 0)
 *    или дочерние папки (RecipeFolderDao.hasChildren > 0).
 *  - удаление — soft-delete (tombstone), как у items/folders/tasks.
 *
 * ПРАВА (по решению пользователя от 15.11.2026):
 *  - Алексей — всё;
 *  - Рима/Дима — только своё (createdBy == currentUser).
 *  Технически: AppUser.canDelete(user) || folder.createdBy == user.
 *
 * ⚠️ Схема @Entity должна точно совпадать с SQL в MIGRATION_22_23
 * (Правило №34):
 *  - syncStatus      TEXT NOT NULL DEFAULT 'synced' → @ColumnInfo(defaultValue = "synced")
 *  - isDeleted       INTEGER NOT NULL DEFAULT 0     → @ColumnInfo(defaultValue = "0")
 *  - deleteConfirmed INTEGER NOT NULL DEFAULT 0     → @ColumnInfo(defaultValue = "0")
 */
@Entity(
    tableName = "recipe_folders",
    indices = [
        Index(value = ["parentId"], name = "index_recipe_folders_parentId"),
        Index(value = ["createdAt"], name = "index_recipe_folders_createdAt"),
        Index(value = ["isDeleted"], name = "index_recipe_folders_isDeleted"),
        Index(
            value = ["deleteConfirmed", "deletedAt"],
            name = "index_recipe_folders_deleteConfirmed_deletedAt"
        )
    ]
)
data class RecipeFolderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),

    /**
     * Название папки. Например: «Супы», «Коктейли», «Алкогольные».
     * По нему же может идти поиск (LIKE '%q%' COLLATE NOCASE) —
     * если решим добавить поиск по папкам в UI.
     */
    val name: String,

    /**
     * Родительская папка рецептов.
     * NULL — папка в корне «Моих рецептов».
     * Не NULL — вложенная папка (parentId = id родителя).
     */
    val parentId: String? = null,

    val createdBy: String,
    val createdAt: Long = System.currentTimeMillis(),

    val updatedBy: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),

    // ===== СИНХРОНИЗАЦИЯ (v15, как у items/folders/tasks/recipes) =====
    /**
     * Статус синхронизации записи.
     *  - "synced"  — синхронизирована с Яндекс.Диском (через changelog);
     *  - "pending" — изменена локально, ждёт отправки в changelog.
     *
     * ⚠️ defaultValue = "synced" обязателен: миграция создаёт колонку
     * с DEFAULT 'synced', Room валидирует схему и требует именно это.
     */
    @ColumnInfo(defaultValue = "synced")
    val syncStatus: String = SYNC_STATUS_SYNCED,

    // ===== TOMBSTONE (v19, как у items/folders/tasks/recipes) =====
    /**
     * Soft-delete для синка.
     * Удалённые папки рецептов помечаются true и уезжают через changelog,
     * чтобы удаление разошлось по всем устройствам и не «воскресло»
     * при merge со старой версией с Диска.
     *
     * В UI такие папки не показываются.
     */
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,

    val deletedAt: Long? = null,
    val deletedBy: String? = null,

    /**
     * Подтверждён ли tombstone (PUSH в changelog прошёл успешно).
     * Через год cleanup может физически удалить запись.
     */
    @ColumnInfo(defaultValue = "0")
    val deleteConfirmed: Boolean = false
) {
    companion object {
        const val SYNC_STATUS_SYNCED = "synced"
        const val SYNC_STATUS_PENDING = "pending"
    }
}
