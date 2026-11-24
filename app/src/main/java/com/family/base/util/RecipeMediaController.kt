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
 *    (байт-в-байт, затем СЖАТИЕ через FFmpeg для уменьшения размера);
 *  - генерацию превью видео через MediaMetadataRetriever → <mediaId>_thumb.jpg;
 *  - удаление файла медиа (фото/видео/превью).
 *
 * 🔧 v23.3.17: добавлено сжатие видео через VideoCompressor (H.264/CRF28/720p).
 * Видео теперь сохраняется → сжимается → превью генерируется уже из сжатого.
 * Плюс метод recompressExistingVideos() для пережатия старых файлов.
 *
 * ВАЖНО:
 *  - Всё работает ЛОКАЛЬНО, без интернета. Это критично для «Моих рецептов».
 *  - Синк на Яндекс.Диск — Шаг 1.6 (SyncManager + CatalogRepository).
 *
 * Соглашение по именам:
 *  - фото:  "<mediaId>.jpg"
 *  - видео: "<mediaId>.<ext>" (ext из mime или из имени Uri; по умолчанию mp4)
 *  - превью видео: "<mediaId>_thumb.jpg"
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
     * 🆕 v23.3.17: порог, выше которого видео считается "несжатым".
     * Для recompressExistingVideos: сжимаем только файлы больше 10 МБ.
     */
    private const val RECOMPRESS_MIN_SIZE_BYTES = 10L * 1024L * 1024L

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
     *  2. Определяем mime и расширение.
     *  3. Копируем байт-в-байт из contentResolver.openInputStream(uri) во временный файл.
     *  4. 🆕 v23.3.17: сжимаем через VideoCompressor.compress() (H.264/CRF28/720p).
     *     Если сжатие упало — оставляем оригинал (не теряем данные).
     *  5. Генерируем превью уже из сжатого файла.
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

            // 1. Копируем байт-в-байт.
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

            val originalSize = file.length()
            Logger.log(
                TAG,
                "saveVideoFromUri: copied original recipeId=$recipeId, mediaId=$mediaId, " +
                    "ext=$ext, mime=$mime, size=$originalSize"
            )

            // 2. 🆕 Сжимаем (если файл похож на видео и не пуст).
            if (originalSize > 0L && VideoCompressor.isVideoFile(file)) {
                val t0 = System.currentTimeMillis()
                val result = VideoCompressor.compress(file)
                val elapsed = System.currentTimeMillis() - t0
                if (result.success) {
                    Logger.log(
                        TAG,
                        "saveVideoFromUri: compressed $originalSize → ${result.compressedSize} " +
                            "(${String.format("%.0f", result.ratio * 100)}%), elapsed=${elapsed}ms"
                    )
                } else {
                    Logger.log(
                        TAG,
                        "saveVideoFromUri: compression skipped/failed " +
                            "(originalSize=$originalSize, error=${result.errorMessage}), " +
                            "keeping original. elapsed=${elapsed}ms"
                    )
                }
            }

            // 3. Превью генерируем ПОСЛЕ сжатия (из актуального файла).
            val thumbName = tryGenerateVideoThumb(dir, mediaId, file)
            val finalSize = file.length()

            SavedMedia(
                mediaId = mediaId,
                localName = localName,
                thumbName = thumbName,
                mimeType = mime ?: mimeFromExt(ext),
                fileSize = finalSize
            )
        } catch (e: Exception) {
            Logger.log(TAG, "saveVideoFromUri ERROR: ${e.message}", e)
            null
        }
    }

    /**
     * 🆕 v23.3.17: пройтись по всем существующим видеофайлам в filesDir/recipe_media/
     * и сжать те, что больше RECOMPRESS_MIN_SIZE_BYTES.
     *
     * Возвращает Pair(compressedCount, skippedCount).
     *
     * ВАЖНО: этот метод НЕ обновляет БД. Если файл был сжат, его размер уменьшился,
     * но имя и mime не изменились — синк продолжит работать. Если хочется заново
     * отправить сжатый файл на Диск (потому что предыдущий не залился из-за размера) —
     * нужно сбросить у этой строки recipe_media флаг imageSynced = false.
     *
     * Проще всего: после вызова recompressExistingVideos() нажать "Синхронизировать",
     * и незалитые видео поедут уже в сжатом виде (их imageSynced = false).
     */
    fun recompressExistingVideos(context: Context): Pair<Int, Int> {
        val rootDir = File(context.filesDir, "recipe_media")
        if (!rootDir.exists() || !rootDir.isDirectory) {
            Logger.log(TAG, "recompressExistingVideos: no recipe_media dir")
            return 0 to 0
        }

        var compressed = 0
        var skipped = 0

        val recipeDirs = rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        Logger.log(TAG, "recompressExistingVideos: found ${recipeDirs.size} recipe dirs")

        recipeDirs.forEach { recipeDir ->
            val videoFiles = recipeDir.listFiles()?.filter { file ->
                file.isFile &&
                    VideoCompressor.isVideoFile(file) &&
                    !file.name.endsWith("_thumb.jpg") &&
                    !file.name.endsWith(".compressed.tmp")
            } ?: emptyList()

            videoFiles.forEach { file ->
                if (file.length() < RECOMPRESS_MIN_SIZE_BYTES) {
                    skipped++
                    Logger.log(
                        TAG,
                        "recompressExistingVideos: skip ${file.name} (${file.length()} bytes < $RECOMPRESS_MIN_SIZE_BYTES)"
                    )
                    return@forEach
                }
                val t0 = System.currentTimeMillis()
                val result = VideoCompressor.compress(file)
                val elapsed = System.currentTimeMillis() - t0
                if (result.success) {
                    compressed++
                    Logger.log(
                        TAG,
                        "recompressExistingVideos: OK ${file.parentFile?.name}/${file.name} " +
                            "$(result.originalSize) → ${result.compressedSize} " +
                            "(${String.format("%.0f", result.ratio * 100)}%), elapsed=${elapsed}ms"
                    )
                } else {
                    skipped++
                    Logger.log(
                        TAG,
                        "recompressExistingVideos: FAIL ${file.name}: ${result.errorMessage}, elapsed=${elapsed}ms"
                    )
                }
            }
        }

        Logger.log(
            TAG,
            "recompressExistingVideos: DONE — compressed=$compressed, skipped=$skipped"
        )
        return compressed to skipped
    }

    // ============================================================
    // УДАЛЕНИЕ
    // ============================================================

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

    private fun tryGenerateVideoThumb(dir: File, mediaId: String, videoFile: File): String? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)

            val frame: Bitmap? = retriever.getFrameAtTime(
                500_000L,
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

    private fun resolveVideoExtension(mime: String?, uri: Uri): String {
        if (!mime.isNullOrBlank()) {
            val fromMime = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mime)
            if (!fromMime.isNullOrBlank()) {
                return sanitizeExt(fromMime)
            }
        }
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank()) {
            val dot = lastSegment.lastIndexOf('.')
            if (dot >= 0 && dot < lastSegment.length - 1) {
                val ext = lastSegment.substring(dot + 1)
                if (ext.length in 1..5) return sanitizeExt(ext)
            }
        }
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
