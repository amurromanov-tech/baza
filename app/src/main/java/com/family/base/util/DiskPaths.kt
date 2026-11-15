package com.family.base.util

import com.family.base.Config

/**
 * 🆕 B3: централизованные пути Яндекс.Диска.
 *
 * Все магические строки вида "/BAZA/changelog", "/BAZA/images" и т.п.
 * собраны здесь. Корень строится из Config.SHARED_FOLDER_NAME — если
 * когда-нибудь поменяется имя корневой папки, достаточно поменять его
 * в Config.kt.
 *
 * ⚠️ Локальные пути устройства (Downloads/BAZA/backup в BackupManagerV2)
 * сюда НЕ входят — это не Яндекс.Диск.
 *
 * 🆕 v20.4.9: добавлен GALLERY — путь к папке «Романовы».
 * 🆕 v21.1.0 (Шаг 1.6): добавлен RECIPE_MEDIA — путь к медиа рецептов:
 *   /BAZA/data/recipe_media/<recipeId>/<fileName>
 *   Файлы называются по mediaId: <mediaId>.jpg / <mediaId>.mp4 /
 *   <mediaId>_thumb.jpg. См. RecipeMediaController.
 */
object DiskPaths {

    /** Корень на Диске: "/BAZA" */
    val ROOT: String = "/" + Config.SHARED_FOLDER_NAME

    /** /BAZA/data — items.json, folders.json, history.json, tasks.json, users.json */
    val DATA: String = "$ROOT/data"

    /** /BAZA/images — фото предметов и папок */
    val IMAGES: String = "$ROOT/images"

    /** /BAZA/changelog — пакеты изменений */
    val CHANGELOG: String = "$ROOT/changelog"

    /** /BAZA/logs — логи приложения */
    val LOGS: String = "$ROOT/logs"

    /** /BAZA/restore — restore_*.json для RestoreManager */
    val RESTORE: String = "$ROOT/restore"

    /** /BAZA/backup — облачные бэкапы BackupManagerV2 */
    val BACKUP: String = "$ROOT/backup"

    /** /BAZA/.last_modified — timestamp последнего изменения */
    val LAST_MODIFIED: String = "$ROOT/.last_modified"

    /** /BAZA/data/users.json */
    val USERS_JSON: String = "$DATA/users.json"

    // ============================================================
    // 🆕 v21.1.0 (Шаг 1.6): МЕДИА РЕЦЕПТОВ
    // ============================================================

    /**
     * /BAZA/data/recipe_media — корень для медиа всех рецептов.
     *
     * Внутри — подпапки по recipeId:
     *   /BAZA/data/recipe_media/<recipeId>/<mediaId>.jpg
     *   /BAZA/data/recipe_media/<recipeId>/<mediaId>.mp4
     *   /BAZA/data/recipe_media/<recipeId>/<mediaId>_thumb.jpg
     *
     * Такая структура:
     *  - удобно удалять папку рецепта целиком при hard-delete;
     *  - recipeId в пути = быстрый листинг медиа одного рецепта;
     *  - mediaId в имени = поштучный синк (imageSynced на каждой строке).
     */
    val RECIPE_MEDIA: String = "$DATA/recipe_media"

    // ============================================================
    // 🆕 v20.4.9: ГАЛЕРЕЯ (вне /BAZA, в корне Яндекс.Диска)
    // ============================================================

    /**
     * Папка «Романовы» в корне Яндекс.Диска.
     *
     * В отличие от ROOT/DATA/IMAGES/..., это НЕ внутри /BAZA.
     * Причина: папка «Романовы» — семейный фотоархив, расшаренный
     * на несколько аккаунтов (Алексей, Рима, Дима), лежит в корне
     * Диска. Приложение БАЗА ходит туда только на чтение (листинг
     * и превью), ничего не пишет и не удаляет.
     *
     * Путь от корня диска: "/Романовы".
     * Если папку переименуют на Диске — достаточно поменять здесь.
     */
    const val GALLERY: String = "/Романовы"

    // ============================================================
    // ДИНАМИЧЕСКИЕ ПУТИ
    // ============================================================

    /** /BAZA/changelog/{fileName} */
    fun changelogFile(fileName: String): String = "$CHANGELOG/$fileName"

    /** /BAZA/images/{itemId}.jpg */
    fun itemImage(itemId: String): String = "$IMAGES/$itemId.jpg"

    /** /BAZA/images/folder_{folderId}.jpg */
    fun folderImage(folderId: String): String = "$IMAGES/folder_$folderId.jpg"

    /** /BAZA/data/{fileName} — items.json, folders.json, history.json, tasks.json */
    fun dataFile(fileName: String): String = "$DATA/$fileName"

    /** /BAZA/logs/{fileName} */
    fun logFile(fileName: String): String = "$LOGS/$fileName"

    /** /BAZA/restore/{fileName} — restore_items.json и т.п. */
    fun restoreFile(fileName: String): String = "$RESTORE/$fileName"

    /** /BAZA/backup/{fileName} */
    fun backupFile(fileName: String): String = "$BACKUP/$fileName"

    /**
     * 🆕 v20.4.9: путь внутри галереи.
     * Пример: galleryPath("Праздники/1998 Мой др")
     *        → "/Романовы/Праздники/1998 Мой др"
     *
     * relativePath передаётся БЕЗ ведущего слэша.
     * Пустая строка или null → корень галереи ("/Романовы").
     */
    fun galleryPath(relativePath: String?): String {
        if (relativePath.isNullOrEmpty()) return GALLERY
        val clean = relativePath.trimStart('/').trimEnd('/')
        return if (clean.isEmpty()) GALLERY else "$GALLERY/$clean"
    }

    // ============================================================
    // 🆕 v21.1.0 (Шаг 1.6): МЕДИА РЕЦЕПТОВ — ДИНАМИЧЕСКИЕ ПУТИ
    // ============================================================

    /**
     * Папка медиа конкретного рецепта: /BAZA/data/recipe_media/<recipeId>.
     * Используется, когда нужно создать папку перед заливкой файла.
     */
    fun recipeMediaFolder(recipeId: String): String = "$RECIPE_MEDIA/$recipeId"

    /**
     * Файл медиа рецепта: /BAZA/data/recipe_media/<recipeId>/<fileName>.
     *
     * fileName — это RecipeMediaEntity.imageUrl
     * (например "abc-123.jpg" или "abc-123.mp4").
     */
    fun recipeMediaFile(recipeId: String, fileName: String): String =
        "$RECIPE_MEDIA/$recipeId/$fileName"

    /**
     * Файл превью видео рецепта: /BAZA/data/recipe_media/<recipeId>/<thumbName>.
     *
     * thumbName — это RecipeMediaEntity.thumbnailUrl
     * (например "abc-123_thumb.jpg").
     */
    fun recipeMediaThumbFile(recipeId: String, thumbName: String): String =
        "$RECIPE_MEDIA/$recipeId/$thumbName"
}
