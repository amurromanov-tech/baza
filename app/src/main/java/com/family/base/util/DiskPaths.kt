package com.family.base.util

import com.family.base.Config

/**
 * 🆕 B3: единое место для всех путей Яндекс.Диска.
 *
 * Все пути — ОТНОСИТЕЛЬНО корня приложения (папки пользователя на Диске).
 * Корень определяется динамически через TokenStorage.getFolderName()
 * (fallback — Config.SHARED_FOLDER_NAME = "BAZA").
 *
 * Использование:
 *   val root = DiskPaths.root(folderName)          // "/BAZA"
 *   val changelog = DiskPaths.changelog(folderName) // "/BAZA/changelog"
 *   val itemImage = DiskPaths.itemImage(folderName, itemId)
 *
 * ⚠️ Не хранить здесь абсолютные URL — только пути на Диске.
 * Базовые URL API — в Config (YANDEX_DISK_API_BASE и т.д.).
 */
object DiskPaths {

    /** Дефолтное имя корневой папки, если TokenStorage не вернул. */
    const val DEFAULT_ROOT_NAME = Config.SHARED_FOLDER_NAME // "BAZA"

    // ===== Имена подпапок =====
    const val CHANGELOG = "changelog"
    const val IMAGES = "images"
    const val DATA = "data"
    const val LOGS = "logs"
    const val RESTORE = "restore"
    const val BACKUP = "backup"

    // ===== Имена файлов в data/ =====
    const val ITEMS_JSON = "items.json"
    const val FOLDERS_JSON = "folders.json"
    const val HISTORY_JSON = "history.json"
    const val TASKS_JSON = "tasks.json"

    // ===== Служебные файлы в корне =====
    const val LAST_MODIFIED = ".last_modified"

    // ===== Корень =====

    /** "/{folderName}" — корень приложения на Диске. */
    fun root(folderName: String?): String {
        val name = if (folderName.isNullOrEmpty()) DEFAULT_ROOT_NAME else folderName
        return "/$name"
    }

    // ===== Подпапки =====

    fun changelog(folderName: String?): String = "${root(folderName)}/$CHANGELOG"
    fun images(folderName: String?): String = "${root(folderName)}/$IMAGES"
    fun data(folderName: String?): String = "${root(folderName)}/$DATA"
    fun logs(folderName: String?): String = "${root(folderName)}/$LOGS"
    fun restore(folderName: String?): String = "${root(folderName)}/$RESTORE"
    fun backup(folderName: String?): String = "${root(folderName)}/$BACKUP"

    // ===== Файлы в data/ =====

    fun itemsJson(folderName: String?): String = "${data(folderName)}/$ITEMS_JSON"
    fun foldersJson(folderName: String?): String = "${data(folderName)}/$FOLDERS_JSON"
    fun historyJson(folderName: String?): String = "${data(folderName)}/$HISTORY_JSON"
    fun tasksJson(folderName: String?): String = "${data(folderName)}/$TASKS_JSON"

    // ===== Служебные =====

    fun lastModified(folderName: String?): String = "${root(folderName)}/$LAST_MODIFIED"

    // ===== Файлы картинок =====

    /** Фото предмета: /BAZA/images/{itemId}.jpg */
    fun itemImage(folderName: String?, itemId: String): String =
        "${images(folderName)}/$itemId.jpg"

    /** Фото папки: /BAZA/images/folder_{folderId}.jpg */
    fun folderImage(folderName: String?, folderId: String): String =
        "${images(folderName)}/folder_$folderId.jpg"

    /** Относительный путь для imageUrl в ItemEntity (без корня и /images). */
    fun itemImageRelative(itemId: String): String = "$IMAGES/$itemId.jpg"

    // ===== Changelog-файлы =====

    /** Один changelog-файл: /BAZA/changelog/{fileName}. */
    fun changelogFile(folderName: String?, fileName: String): String =
        "${changelog(folderName)}/$fileName"

    // ===== Restore-файлы =====

    /** /BAZA/restore/{fileName}. */
    fun restoreFile(folderName: String?, fileName: String): String =
        "${restore(folderName)}/$fileName"

    // ===== Backup-файлы =====

    /** /BAZA/backup/{fileName}. */
    fun backupFile(folderName: String?, fileName: String): String =
        "${backup(folderName)}/$fileName"

    // ===== Логи =====

    /** /BAZA/logs/{fileName}. */
    fun logFile(folderName: String?, fileName: String): String =
        "${logs(folderName)}/$fileName"
}
