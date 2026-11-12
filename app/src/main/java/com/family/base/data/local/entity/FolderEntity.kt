package com.family.base.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 🆕 v18.1.2 (fix миграции 18→19): добавлены indices для
 * isDeleted и (deleteConfirmed, deletedAt).
 *
 * Миграция 18→19 создаёт индексы:
 *   CREATE INDEX index_folders_isDeleted ON folders(isDeleted);
 *   CREATE INDEX index_folders_deleteConfirmed_deletedAt ON folders(deleteConfirmed, deletedAt);
 *
 * Без @Entity(indices=...) Room ожидал indices=[], а в БД они есть —
 * валидация схемы падала с "Migration didn't properly handle: folders".
 * Теперь ожидаемые и фактические индексы совпадают.
 *
 * 🆕 v20.4.9 (галерея): добавлены константы SYSTEM_KEY_GALLERY
 * и SYSTEM_ID_GALLERY. Схема БД НЕ меняется — это просто строковые
 * константы, миграция Room не нужна.
 *
 * 🆕 v20.7.5 (миграция 20→21): добавлено поле iconSynced.
 *
 * Симметрично ItemEntity.imageSynced: локально сохранённая иконка
 * (iconUrl) и «иконка реально залита на Яндекс.Диск» (iconSynced) —
 * два разных факта. uploadUnsyncedFolderImages теперь смотрит
 * на iconSynced, а не на iconUrl.isNullOrEmpty(), иначе только что
 * созданная папка с локально проставленным iconUrl отсекалась бы
 * фильтром и никогда не уезжала на Диск.
 *
 * 🆕 v21.1.0 (миграция 21→22): добавлены константы для «Рецептов»
 * (SYSTEM_ID_RECIPES / SYSTEM_ID_RECIPES_AI / SYSTEM_ID_RECIPES_MY
 * и SYSTEM_KEY_* / SYSTEM_NAME_*). Схема БД НЕ меняется —
 * это просто строковые константы, миграция Room не нужна.
 */
@Entity(
    tableName = "folders",
    indices = [
        Index(value = ["isDeleted"], name = "index_folders_isDeleted"),
        Index(
            value = ["deleteConfirmed", "deletedAt"],
            name = "index_folders_deleteConfirmed_deletedAt"
        ),
        // 🆕 v20.7.5: индекс под выборку «что ещё не залито на Диск»
        Index(value = ["iconSynced"], name = "index_folders_iconSynced")
    ]
)
data class FolderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,

    /**
     * Родитель-ПАПКА.
     * NULL — если папка корневая или вложена в предмет (см. parentItemId).
     */
    val parentId: String? = null,

    /**
     * Родитель-ПРЕДМЕТ (вложенность папки в предмет).
     * NULL — если папка не вложена в предмет.
     * Если не NULL — папка находится «внутри» предмета-родителя.
     *
     * ИНВАРИАНТ: ровно одно из (parentId, parentItemId) может быть не NULL.
     *  - parentId != null, parentItemId == null → папка внутри папки
     *  - parentId == null, parentItemId != null → папка внутри предмета
     *  - оба null → корневая папка
     */
    val parentItemId: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val createdBy: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val path: String,
    val iconUrl: String? = null,
    val iconThumbnailUrl: String? = null,

    // ===== СИСТЕМНАЯ ПАПКА =====

    /**
     * Является ли папка системной.
     * Системные папки нельзя удалять, перемещать, переименовывать,
     * архивировать. В UI скрываются соответствующие кнопки.
     *
     * Примеры: «🏠 Домашние дела», «🛒 Покупки», «🛒 Список покупок»,
     * «🖼️ Галерея», «🍳 Рецепты» (и две её дочерние).
     */
    val isSystem: Boolean = false,

    /**
     * Ключ системной папки.
     * NULL — для обычных папок.
     * "home_tasks"    — для папки «🏠 Домашние дела».
     * "purchases"     — для папки «🛒 Покупки» (чеки).
     * "shopping_list" — для папки «🛒 Список покупок».
     * "gallery"       — для папки «🖼️ Галерея» (v20.4.9).
     * "recipes"       — для папки «🍳 Рецепты» (v21.1.0).
     * "recipes_ai"    — для папки «🤖 Рецепты ИИ» (v21.1.0).
     * "recipes_my"    — для папки «📝 Мои рецепты» (v21.1.0).
     *
     * Используется для поиска системной папки при старте приложения
     * (upsert, если её нет) и для перехвата клика в MainActivity.
     */
    val systemKey: String? = null,

    // ===== СИНХРОНИЗАЦИЯ (v15) =====
    /**
     * Статус синхронизации записи.
     *  - "synced"  — запись синхронизирована с Яндекс.Диском.
     *  - "pending" — запись изменена локально, ждёт отправки в changelog.
     *  - "conflict" — конфликт при merge (задел на будущее, сейчас не используется).
     *
     * 🆕 v18.1.1 (fix миграции 18→19): defaultValue = "synced" —
     * чтобы схема Entity совпадала с фактической схемой БД,
     * созданной миграцией v14→v15 (ALTER TABLE ... DEFAULT 'synced').
     */
    @ColumnInfo(defaultValue = "synced")
    val syncStatus: String = SYNC_STATUS_SYNCED,

    // ===== TOMBSTONE (v19, №10) =====
    /**
     * Soft-delete для синка.
     * Удалённые папки помечаются true и синкаются через changelog,
     * чтобы удаление разошлось по всем устройствам и не «воскресло»
     * при merge со старой версией с Диска.
     *
     * В UI такие папки не показываются.
     * Системные папки (isSystem = true) удалять нельзя — tombstone
     * для них не ставится никогда.
     *
     * 🆕 v18.1.1 (fix миграции 18→19): defaultValue = "0".
     */
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,

    /**
     * Когда удалена (timestamp, millis).
     * NULL — если ещё не удалена.
     */
    val deletedAt: Long? = null,

    /**
     * Кто удалил (AppUser.name).
     * NULL — если ещё не удалена.
     */
    val deletedBy: String? = null,

    /**
     * Подтверждён ли tombstone.
     * false — tombstone ещё не разошёлся через changelog.
     * true  — PUSH в changelog прошёл успешно; через 1 год cleanup
     *         может физически удалить запись из базы.
     *
     * 🆕 v18.1.1 (fix миграции 18→19): defaultValue = "0".
     */
    @ColumnInfo(defaultValue = "0")
    val deleteConfirmed: Boolean = false,

    // ===== 🆕 v20.7.5 (миграция 20→21): ИКОНКА НА ДИСКЕ =====
    /**
     * Признак «файл иконки реально загружен на Яндекс.Диск».
     *
     * false — иконка есть только локально (или её вообще нет).
     *         uploadUnsyncedFolderImages такие папки подхватит и зальёт,
     *         если локальный файл folder_<id> существует.
     * true  — uploadFolderImage успешно залил файл. Повторно не грузим.
     *
     * Симметрично ItemEntity.imageSynced. См. комментарий там.
     *
     * ⚠️ defaultValue = "0" обязателен: миграция добавляет колонку
     * через ALTER TABLE folders ADD COLUMN iconSynced INTEGER NOT NULL DEFAULT 0,
     * Room валидирует схему и требует именно defaultValue='0'.
     */
    @ColumnInfo(defaultValue = "0")
    val iconSynced: Boolean = false
) {
    companion object {
        // ===== «🏠 Домашние дела» =====
        const val SYSTEM_KEY_HOME_TASKS = "home_tasks"

        /**
         * Фиксированный id системной папки «Домашние дела».
         * НЕ UUID — чтобы на всех устройствах это была одна и та же папка
         * и синк через folders.json не создавал дубликатов.
         */
        const val SYSTEM_ID_HOME_TASKS = "system-folder-home-tasks"

        // ===== «🛒 Покупки» =====
        const val SYSTEM_KEY_PURCHASES = "purchases"
        const val SYSTEM_ID_PURCHASES = "system-folder-purchases"

        // ===== «🛒 Список покупок» =====
        const val SYSTEM_KEY_SHOPPING_LIST = "shopping_list"
        const val SYSTEM_ID_SHOPPING_LIST = "system-folder-shopping-list"

        // ===== 🆕 v20.4.9: «🖼️ Галерея» =====
        /**
         * Системная папка «🖼️ Галерея».
         *
         * В отличие от остальных системных папок, её содержимое
         * НЕ хранится в Room — оно живёт на Яндекс.Диске (папка
         * «/Романовы»). Клик по этой папке в MainActivity открывает
         * GalleryActivity, а не обычный список предметов.
         *
         * Сама запись FolderEntity нужна только для того, чтобы
         * папка отображалась в корне и перехватывался клик.
         * В неё не кладутся предметы, она не участвует в синке
         * items/folders.json (isSystem = true защищает от tombstone).
         */
        const val SYSTEM_KEY_GALLERY = "gallery"
        const val SYSTEM_ID_GALLERY = "system-folder-gallery"

        // ============================================================
        // 🆕 v21.1.0: «🍳 РЕЦЕПТЫ» (корневая + две дочерние)
        //
        // Корневая системная папка и две её дочерние — тоже isSystem.
        // Создаются идемпотентно в RecipesRepository.ensureRecipesFolders()
        // при старте приложения (по аналогии с ensureGalleryFolder).
        //
        // Фиксированные id (а не UUID) — чтобы на всех устройствах
        // это были одни и те же записи, и синк через folders.json
        // не плодил дубликаты.
        //
        // Поле parentId у дочерних = SYSTEM_ID_RECIPES.
        //
        // Клик по «🍳 Рецепты» в MainActivity → открывает экран
        // с двумя дочерними (или сразу общий список — решим в UI).
        // Клик по «🤖 Рецепты ИИ» / «📝 Мои рецепты» → открывает
        // RecipeListActivity с соответствующим фильтром по source.
        // ============================================================

        // ---------- Корневая «🍳 Рецепты» ----------
        const val SYSTEM_KEY_RECIPES = "recipes"
        const val SYSTEM_ID_RECIPES = "system-folder-recipes"
        const val SYSTEM_NAME_RECIPES = "🍳 Рецепты"

        // ---------- «🤖 Рецепты ИИ» ----------
        const val SYSTEM_KEY_RECIPES_AI = "recipes_ai"
        const val SYSTEM_ID_RECIPES_AI = "system-folder-recipes-ai"
        const val SYSTEM_NAME_RECIPES_AI = "🤖 Рецепты ИИ"

        // ---------- «📝 Мои рецепты» ----------
        const val SYSTEM_KEY_RECIPES_MY = "recipes_my"
        const val SYSTEM_ID_RECIPES_MY = "system-folder-recipes-my"
        const val SYSTEM_NAME_RECIPES_MY = "📝 Мои рецепты"

        const val SYNC_STATUS_SYNCED = "synced"
        const val SYNC_STATUS_PENDING = "pending"
        const val SYNC_STATUS_CONFLICT = "conflict"
    }
}
