package com.family.base.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 🆕 v21.1.0 (Шаг 1.5-1): работа с файлами медиа рецептов.
 *
 * Отвечает за:
 *  - сохранение фото из Uri в filesDir/recipe_media/<recipeId>/<mediaId>.jpg
 *    (с EXIF-поворотом и сжатием до 1024px, как processImage);
 *  - сохранение видео из Uri в filesDir/recipe_media/<recipeId>/<mediaId>.<ext>
 *    (без перекодирования — копируем байт-в-байт, чтобы не терять качество);
 *  - генерацию превью видео через MediaMetadataRetriever → <mediaId>_thumb.jpg;
 *  - удаление файла медиа (фото/видео/превью).
 *
 * ВАЖНО:
 *  - Всё работает ЛОКАЛЬНО, без интернета. Это критично для «Моих рецептов».
 *  - Синк на Яндекс.Диск — Шаг 1.6 (SyncManager + CatalogRepository).
 *
 * Соглашение по именам:
 *  - фото:  "<mediaId>.jpg"
 *  - видео: "<mediaId>.<ext>" (ext из mime или из имени Uri; по умолчанию mp4)
 *  - превью видео: "<mediaId>_thumb.jpg"
 *
 * 🔧 v23.3.18: откат эксперимента со сжатием (FFmpegKit удалён из build.gradle.kts
 * из-за краша нативных библиотек и раздувания APK). Видео снова сохраняется
 * байт-в-байт, без перекодирования.
 */
object RecipeMediaController {

    private const val TAG = "RecipeMediaController"

    /** Максимальный размер фото по большей стороне (как в processImage). */
    private const val PHOTO_MAX_SIZE = 1024

    /** Качество JPEG при сохранении фото. */
    private const val PHOTO_JPEG_QUALITY = 85

    /** Качество JPEG для превью видео. */
    private const val THUMB_JPEG_QUALITY = 80

    /** Ширина превью видео (px). Высота — пропорционально. */
    private const val THUMB_MAX_WIDTH = 512

    /**
     * Результат сохранения медиа.
     *
     * @param mediaId     UUID, который нужно передать в RecipesRepository.addMedia
     *                    (он же станет id строки recipe_media).
     * @param localName   имя файла в filesDir/recipe_media/<recipeId>/ (например "abc.jpg").
     * @param thumbName   имя файла превью (для видео; для фото — null).
     * @param mimeType    MIME-тип ("image/jpeg", "video/mp4", ...).
     * @param fileSize    размер файла в байтах (для логов/диагностики).
     */
    data class SavedMedia(
        val mediaId: String,
        val localName: String,
        val thumbName: String?,
        val mimeType: String?,
        val fileSize: Long
    )

    // ============================================================
    // ФОТО
    // ============================================================

    /**
     * Сохранить фото из Uri в filesDir/recipe_media/<recipeId>/<mediaId>.jpg.
     *
     * Шаги:
     *  1. mediaId = UUID.randomUUID().
     *  2. ensureRecipeMediaDir(context, recipeId) — создаёт папку, если нет.
     *  3. loadBitmapWithExif(context, uri) — читает Bitmap с поворотом по EXIF.
     *  4. processImage(bitmap) — сжимает до 1024px / JPEG q=85.
     *  5. Пишет байты в <mediaId>.jpg.
     *
     * Возвращает SavedMedia или null при ошибке.
     */
    fun savePhotoFromUri(context: Context, recipeId: String, uri: Uri): SavedMedia? {
        return try {
            val mediaId = UUID.randomUUID().toString()
            val dir = ImageUtils.ensureRecipeMediaDir(context, recipeId)
            if (dir == null) {
                Logger.log(TAG, "savePhotoFromUri: cannot create dir for recipeId=$recipeId")
                return null
            }

            val bitmap = ImageUtils.loadBitmapWithExif(context, uri)
            if (bitmap == null) {
                Logger.log(TAG, "savePhotoFromUri: cannot decode bitmap from uri=$uri")
                return null
            }

            // Масштабируем до PHOTO_MAX_SIZE (processImage уже это делает, но
            // проверим на всякий случай — вдруг bitmap меньше, тогда оставляем как есть).
            val scaled = scaleDownIfNeeded(bitmap, PHOTO_MAX_SIZE)

            val bytes = ImageUtils.bitmapToJpegBytes(scaled, PHOTO_JPEG_QUALITY)
            if (scaled !== bitmap) {
                try { scaled.recycle() } catch (_: Exception) {}
            }
            try { bitmap.recycle() } catch (_: Exception) {}

            val localName = "$mediaId.jpg"
            val file = File(dir, localName)
            FileOutputStream(file).use { it.write(bytes) }

            Logger.log(
                TAG,
                "savePhotoFromUri: OK recipeId=$recipeId, mediaId=$mediaId, size=${file.length()}"
            )

            SavedMedia(
                mediaId = mediaId,
                localName = localName,
                thumbName = null,
                mimeType = "image/jpeg",
                fileSize = file.length()
            )
        } catch (e: Exception) {
            Logger.log(TAG, "savePhotoFromUri ERROR: ${e.message}", e)
            null
        }
    }

    // ============================================================
    // ВИДЕО
    // ============================================================

    /**
     * Сохранить видео из Uri в filesDir/recipe_media/<recipeId>/<mediaId>.<ext>.
     *
     * Шаги:
     *  1. mediaId = UUID.randomUUID().
     *  2. Определяем mime и расширение (из ContentResolver.getType(uri),
     *     а если пусто — из имени Uri; fallback — mp4).
     *  3. Копируем байт-в-байт из contentResolver.openInputStream(uri)
     *     в файл. Никакого перекодирования — сохраняем оригинал.
     *  4. Генерируем превью: MediaMetadataRetriever → Bitmap первого кадра
     *     → сжимаем в <mediaId>_thumb.jpg (если не удалось — thumbName = null,
     *     это не ошибка, просто не будет превью).
     *
     * Возвращает SavedMedia или null при ошибке.
     */
    fun saveVideoFromUri(context: Context, recipeId: String, uri: Uri): SavedMedia? {
        return try {
            val mediaId = UUID.randomUUID().toString()
            val dir = ImageUtils.ensureRecipeMediaDir(context, recipeId)
            if (dir == null) {
                Logger.log(TAG, "saveVideoFromUri: cannot create dir for recipeId=$recipeId")
                return null
            }

            val mime = try {
                context.contentResolver.getType(uri)
            } catch (e: Exception) {
                null
            }
            val ext = resolveVideoExtension(mime, uri)
            val localName = "$mediaId.$ext"
            val file = File(dir, localName)

            // Копируем байт-в-байт.
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                Logger.log(TAG, "saveVideoFromUri: cannot open input stream uri=$uri")
                return null
            }
            input.use { ins ->
                FileOutputStream(file).use { outs ->
                    ins.copyTo(outs)
                }
            }

            Logger.log(
                TAG,
                "saveVideoFromUri: OK recipeId=$recipeId, mediaId=$mediaId, ext=$ext, mime=$mime, size=${file.length()}"
            )

            // Превью (не критично — если не получилось, thumbName = null).
            val thumbName = tryGenerateVideoThumb(dir, mediaId, file)

            SavedMedia(
                mediaId = mediaId,
                localName = localName,
                thumbName = thumbName,
                mimeType = mime ?: mimeFromExt(ext),
                fileSize = file.length()
            )
        } catch (e: Exception) {
            Logger.log(TAG, "saveVideoFromUri ERROR: ${e.message}", e)
            null
        }
    }

    /**
     * Сгенерировать превью видео (первый кадр) в <mediaId>_thumb.jpg.
     * Возвращает имя файла или null, если не удалось.
     */
    private fun tryGenerateVideoThumb(dir: File, mediaId: String, videoFile: File): String? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)

            // Берём кадр на 0.5 сек (обычно уже не чёрный экран).
            val frame: Bitmap? = retriever.getFrameAtTime(
                500_000L, // микросекунды
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.frameAtTime

            if (frame == null) {
                Logger.log(TAG, "tryGenerateVideoThumb: no frame for $videoFile")
                return null
            }

            val scaled = scaleDownIfNeeded(frame, THUMB_MAX_WIDTH)
            val bytes = ImageUtils.bitmapToJpegBytes(scaled, THUMB_JPEG_QUALITY)
            if (scaled !== frame) {
                try { scaled.recycle() } catch (_: Exception) {}
            }
            try { frame.recycle() } catch (_: Exception) {}

            val thumbName = "${mediaId}_thumb.jpg"
            val thumbFile = File(dir, thumbName)
            FileOutputStream(thumbFile).use { it.write(bytes) }

            Logger.log(
                TAG,
                "tryGenerateVideoThumb: OK $thumbName, size=${thumbFile.length()}"
            )
            thumbName
        } catch (e: Exception) {
            Logger.log(TAG, "tryGenerateVideoThumb ERROR: ${e.message}", e)
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    // ============================================================
    // УДАЛЕНИЕ
    // ============================================================

    /**
     * Удалить файл медиа (и превью, если есть).
     * Возвращает true, если файлов не было или удаление прошло успешно.
     */
    fun deleteMediaFiles(
        context: Context,
        recipeId: String,
        localName: String?,
        thumbName: String?
    ): Boolean {
        val okMain = ImageUtils.deleteRecipeMediaFile(context, recipeId, localName)
        val okThumb = if (!thumbName.isNullOrBlank()) {
            ImageUtils.deleteRecipeMediaFile(context, recipeId, thumbName)
        } else {
            true
        }
        return okMain && okThumb
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНОЕ
    // ============================================================

    /**
     * Уменьшить Bitmap, если его большая сторона больше maxSize.
     * Если меньше — возвращает исходный bitmap (без копии).
     */
    private fun scaleDownIfNeeded(src: Bitmap, maxSize: Int): Bitmap {
        val w = src.width
        val h = src.height
        val big = maxOf(w, h)
        if (big <= maxSize) return src

        val scale = maxSize.toFloat() / big
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    /**
     * Определить расширение видео.
     * Приоритет: mime → имя Uri → "mp4".
     */
    private fun resolveVideoExtension(mime: String?, uri: Uri): String {
        // 1. По mime
        if (!mime.isNullOrBlank()) {
            val fromMime = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mime)
            if (!fromMime.isNullOrBlank()) {
                return sanitizeExt(fromMime)
            }
        }
        // 2. По имени Uri
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank()) {
            val dot = lastSegment.lastIndexOf('.')
            if (dot >= 0 && dot < lastSegment.length - 1) {
                val ext = lastSegment.substring(dot + 1)
                if (ext.length in 1..5) return sanitizeExt(ext)
            }
        }
        // 3. Fallback
        return "mp4"
    }

    private fun sanitizeExt(ext: String): String {
        val cleaned = ext.lowercase().filter { it.isLetterOrDigit() }
        return if (cleaned.isEmpty()) "mp4" else cleaned
    }

    private fun mimeFromExt(ext: String): String? {
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}
