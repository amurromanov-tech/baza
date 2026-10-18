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
}
