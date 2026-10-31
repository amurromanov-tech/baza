package com.family.base.data.model

import com.family.base.data.remote.model.DiskResourceItem

/**
 * 🆕 v20.4.9: модель одного элемента галереи.
 *
 * Строится из DiskResourceItem (ответ Яндекс.Диска) и используется
 * в GalleryRepository → GalleryViewModel → GalleryAdapter.
 *
 * Что важно:
 *  - previewUrl — временная ссылка на превью (живёт несколько часов).
 *    Именно её грузит Coil в сетке и в полноэкранном просмотре.
 *    Оригинал (fileUrl) НЕ качается, пока пользователь явно
 *    не нажмёт «Сохранить» (Шаг 2).
 *  - isVideo — плейсхолдер вместо картинки; в Шаге 1 видео не
 *    открывается (Toast «Видео будет в следующей версии»).
 *  - isFolder — подпапка; тап открывает новую GalleryActivity
 *    с путём этой папки.
 *
 * Поля намеренно простые и иммутабельные (val) — модель живёт
 * в списке, никакой логики не несёт.
 */
data class GalleryItem(

    /** Имя файла/папки (например, "IMG_1234.jpg" или "1998 Мой др"). */
    val name: String,

    /**
     * Абсолютный путь на Яндекс.Диске.
     * Пример: "/Романовы/Праздники/1998 Мой др/IMG_1234.jpg".
     * Используется для навигации (подпапки) и для «Информация».
     */
    val path: String,

    /** true — папка; false — файл (фото или видео). */
    val isFolder: Boolean,

    /** true — видео (AVI и т.п.); false — фото. Для папок всегда false. */
    val isVideo: Boolean,

    /**
     * Временная ссылка на превью (маленькое изображение).
     * Для папок — null. Для видео — обычно null (Диск не делает
     * превью для AVI), в этом случае в адаптере рисуем плейсхолдер.
     *
     * Живёт несколько часов. Если Coil получит 403/410 — нужно
     * перезапросить листинг (в Шаге 1 при ошибке просто показываем
     * плейсхолдер; перезапрос — в Шаге 3).
     */
    val previewUrl: String?,

    /**
     * Прямая ссылка на ОРИГИНАЛ файла.
     * НЕ используется в Шаге 1 (ни для показа, ни для сохранения).
     * Пригодится в Шаге 2 для «💾 Сохранить в галерею».
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
         * Логика определения типа:
         *  - type == "dir"                    → папка;
         *  - mediaType == "video"             → видео;
         *  - mimeType startsWith("video/")    → видео;
         *  - mimeType startsWith("image/")    → фото;
         *  - иначе (документ, архив и т.п.)   → фото-плейсхолдер
         *    (в сетке будет иконка «файл», но это редкий случай
         *    в папке с фото; ничего не ломает).
         *
         * @param item  элемент ответа Диска.
         * @param parentPath путь родительской папки (без завершающего слэша),
         *                   чтобы собрать абсолютный путь элемента.
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

            return GalleryItem(
                name = item.name,
                path = fullPath,
                isFolder = isFolder,
                isVideo = isVideo,
                previewUrl = item.preview?.takeIf { it.isNotEmpty() },
                fileUrl = item.file?.takeIf { it.isNotEmpty() },
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

            // Основной формат Диска: с двоеточием в часовом поясе.
            val patterns = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",   // 2024-12-31T23:59:59+00:00
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",   // 2024-12-31T23:59:59Z (без двоеточия)
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
