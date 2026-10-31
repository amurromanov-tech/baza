package com.family.base.data.model

import com.family.base.data.remote.model.DiskResourceItem

/**
 * 🆕 v20.4.9: модель одного элемента галереи.
 *
 * 🆕 v20.5.9: предпросмотр грузится с ОРИГИНАЛА (file), а не с preview.
 * Причина: Яндекс.Диск отдаёт 403 на preview-ссылки при запросе из кода
 * (даже с браузерными заголовками), а file-ссылки (downloader.disk.yandex.ru/disk/...)
 * работают. Для сетки 3 колонки Coil декодирует оригинал с size(300,300),
 * так что визуально всё ок, но трафик больше.
 *
 * Поля:
 *  - previewUrl — теперь ВСЕГДА file-ссылка (для отображения).
 *  - fileUrl    — оригинал (для «Сохранить» в Шаге 2).
 */
data class GalleryItem(

    /** Имя файла/папки (например, "IMG_1234.jpg" или "1998 Мой др"). */
    val name: String,

    /**
     * Абсолютный путь на Яндекс.Диске.
     * Пример: "/Романовы/Праздники/1998 Мой др/IMG_1234.jpg".
     */
    val path: String,

    /** true — папка; false — файл (фото или видео). */
    val isFolder: Boolean,

    /** true — видео (AVI и т.п.); false — фото. Для папок всегда false. */
    val isVideo: Boolean,

    /**
     * 🆕 v20.5.9: ссылка для ОТОБРАЖЕНИЯ.
     * Теперь это file-ссылка (оригинал), т.к. preview-ссылки Диска
     * отдают 403 при запросе из кода. Для папок — null.
     */
    val previewUrl: String?,

    /**
     * Прямая ссылка на ОРИГИНАЛ файла.
     * Используется в Шаге 2 для «💾 Сохранить в галерею».
     * Для папок — null.
     */
    val fileUrl: String?,

    /** Размер файла в байтах. Для папок — 0. Может быть null, если Диск не вернул. */
    val sizeBytes: Long?,

    /**
     * Дата создания (millis). Парсится из ISO 8601-строки Диска.
     * Если парсинг не удался — null; в этом случае сортировка
     * по дате ставит элемент в конец.
     */
    val createdMillis: Long?
) {
    companion object {
        /**
         * Фабрика: DiskResourceItem → GalleryItem.
         *
         * 🆕 v20.5.9: previewUrl = item.file (оригинал), т.к. item.preview
         * даёт 403 при загрузке через Coil. file-ссылки работают.
         */
        fun fromDiskItem(item: DiskResourceItem, parentPath: String): GalleryItem {
            val isFolder = item.type.equals("dir", ignoreCase = true)

            val isVideo = !isFolder && (
                item.mediaType?.equals("video", ignoreCase = true) == true ||
                    item.mimeType?.startsWith("video/", ignoreCase = true) == true
                )

            val createdMillis = parseIso8601(item.created)

            val fullPath = when {
                parentPath.isEmpty() -> "/${item.name}"
                parentPath.endsWith("/") -> "$parentPath${item.name}"
                else -> "$parentPath/${item.name}"
            }

            val fileLink = item.file?.takeIf { it.isNotEmpty() }
            val previewLink = item.preview?.takeIf { it.isNotEmpty() }

            return GalleryItem(
                name = item.name,
                path = fullPath,
                isFolder = isFolder,
                isVideo = isVideo,
                // 🆕 v20.5.9: для отображения используем file-ссылку.
                // previewLink оставлен в коде «на всякий случай» — если
                // Яндекс когда-нибудь починит preview, вернём его.
                previewUrl = fileLink ?: previewLink,
                fileUrl = fileLink,
                sizeBytes = item.size,
                createdMillis = createdMillis
            )
        }

        /**
         * Парсит ISO 8601 дату из ответа Яндекс.Диска.
         * Формат: "2024-12-31T23:59:59+00:00" (или с "Z").
         *
         * Используем java.text.SimpleDateFormat для minSdk 24
         * (java.time доступен с API 26, а у нас minSdk = 24).
         *
         * Если формат не распознан — возвращаем null (элемент
         * уйдёт в конец при сортировке по дате).
         */
        private fun parseIso8601(raw: String?): Long? {
            if (raw.isNullOrEmpty()) return null

            val patterns = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            )
            for (pattern in patterns) {
                try {
                    val fmt = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val date = fmt.parse(raw) ?: continue
                    return date.time
                } catch (_: Exception) {
                    // пробуем следующий формат
                }
            }
            return null
        }
    }
}
