package com.family.base.data.remote.model

import com.google.gson.annotations.SerializedName

/**
 * 🆕 v20.4.9 (галерея): добавлены поля для работы с фото.
 *
 * Старые поля (name, path, type, file, md5) не тронуты — синк
 * (changelog, items.json, folders.json) продолжает работать как раньше.
 *
 * Новые поля используются ТОЛЬКО галереей (`/Романовы`):
 *  - preview     — временная ссылка на превью (маленькое, для сетки
 *                  и полноэкранного просмотра без скачивания оригинала);
 *  - size        — размер файла в байтах (показываем в «Информация»);
 *  - created     — дата создания (сортировка «новые сверху»);
 *  - mime_type   — MIME-тип (image/jpeg, video/x-msvideo и т.п.);
 *  - media_type  — обобщённый тип от Диска: "image", "video", "audio",
 *                  "document", "data", "compressed" (или null).
 *
 * Все новые поля nullable: если Яндекс.Диск их не вернул — Gson
 * оставит null, и код галереи обработает это как «неизвестно».
 */
data class DiskResourcesResponse(
    @SerializedName("_embedded") val embedded: DiskEmbedded?,
    val name: String?,
    val path: String?
)

data class DiskEmbedded(
    val items: List<DiskResourceItem>?
)

data class DiskResourceItem(
    val name: String,
    val path: String,
    val type: String,
    val file: String? = null,
    val md5: String? = null,

    // ===== 🆕 v20.4.9: поля для галереи =====

    /** Временная ссылка на превью. Для папок — null. */
    val preview: String? = null,

    /** Размер файла в байтах. Для папок — null. */
    val size: Long? = null,

    /** Дата создания (ISO 8601, строка). Для папок — null. */
    val created: String? = null,

    /** MIME-тип: "image/jpeg", "video/x-msvideo" и т.п. */
    @SerializedName("mime_type")
    val mimeType: String? = null,

    /** Обобщённый тип: "image", "video", "audio", "document", "data", "compressed". */
    @SerializedName("media_type")
    val mediaType: String? = null
)
