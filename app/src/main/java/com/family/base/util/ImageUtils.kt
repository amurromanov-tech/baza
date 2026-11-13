package com.family.base.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImageUtils {

    private const val TAG = "ImageUtils"

    /** 🆕 v21.1.0 (Шаг 1.5): подпапка в filesDir для медиа рецептов. */
    private const val RECIPE_MEDIA_DIR = "recipe_media"

    // ===== ОБРАБОТКА ИЗОБРАЖЕНИЯ (МАСШТАБИРОВАНИЕ + СЖАТИЕ) =====
    fun processImage(bitmap: Bitmap): ByteArray {
        // Максимальный размер по большей стороне
        val maxSize = 1024

        val width = bitmap.width
        val height = bitmap.height

        val scale = if (width > height) {
            maxSize.toFloat() / width
        } else {
            maxSize.toFloat() / height
        }

        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return stream.toByteArray()
    }

    // ===== ЗАГРУЗКА BITMAP С УЧЁТОМ EXIF =====
    fun loadBitmapWithExif(context: Context, uri: Uri): Bitmap? {
        return try {
            // 1. Читаем EXIF
            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                inputStream?.let {
                    val exif = ExifInterface(it)
                    orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    it.close()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error reading EXIF: ${e.message}")
            }

            // 2. Загружаем bitmap
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null

            // 3. Поворачиваем по EXIF
            return rotateBitmap(bitmap, orientation)
        } catch (e: Exception) {
            Logger.log(TAG, "Error loading bitmap with EXIF", e)
            null
        }
    }

    // ===== ЗАГРУЗКА BITMAP ИЗ ФАЙЛА С УЧЁТОМ EXIF =====
    fun loadBitmapFromFileWithExif(file: File): Bitmap? {
        return try {
            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                val exif = ExifInterface(file.absolutePath)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (e: Exception) {
                Logger.log(TAG, "Error reading EXIF from file: ${e.message}")
            }

            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            return rotateBitmap(bitmap, orientation)
        } catch (e: Exception) {
            Logger.log(TAG, "Error loading bitmap from file", e)
            null
        }
    }

    // ===== ПОВОРОТ BITMAP ПО EXIF =====
    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap // ORIENTATION_NORMAL или ORIENTATION_UNDEFINED
        }

        return try {
            Bitmap.createBitmap(
                bitmap,
                0, 0,
                bitmap.width, bitmap.height,
                matrix,
                true
            )
        } catch (e: Exception) {
            Logger.log(TAG, "Error rotating bitmap", e)
            bitmap
        }
    }

    // ===== СОХРАНЕНИЕ ЛОКАЛЬНО =====
    fun saveImageLocally(context: Context, imageId: String, bytes: ByteArray): File {
        val imagesDir = File(context.filesDir, "images")
        if (!imagesDir.exists()) imagesDir.mkdirs()
        val file = File(imagesDir, "$imageId.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        Logger.log(TAG, "Image saved locally: ${file.absolutePath}")
        return file
    }

    // ===== ПОЛУЧЕНИЕ ЛОКАЛЬНОГО ФАЙЛА =====
    fun getLocalImageFile(context: Context, imageId: String): File? {
        val file = File(File(context.filesDir, "images"), "$imageId.jpg")
        return if (file.exists()) file else null
    }

    /**
     * 🆕 ЧЕКИ + ИСТОРИЯ (C): получить локальный файл по imageUrl
     * из ItemEntity (там лежит имя файла, например "folder_<id>.jpg"
     * или "<uuid>.jpg").
     *
     * В отличие от getLocalImageFile(context, imageId), этот метод:
     *  - не добавляет ".jpg", если он уже есть в imageUrl;
     *  - если файла нет — возвращает null.
     *
     * Пример:
     *   imageUrl = "abc-123.jpg" → filesDir/images/abc-123.jpg
     *   imageUrl = "abc-123"     → filesDir/images/abc-123.jpg
     */
    fun getLocalImageFileByUrl(context: Context, imageUrl: String?): File? {
        if (imageUrl.isNullOrBlank()) return null
        val fileName = if (imageUrl.endsWith(".jpg", ignoreCase = true)) {
            imageUrl
        } else {
            "$imageUrl.jpg"
        }
        val file = File(File(context.filesDir, "images"), fileName)
        return if (file.exists()) file else null
    }

    // ===== УДАЛЕНИЕ ЛОКАЛЬНОГО ИЗОБРАЖЕНИЯ =====
    fun deleteLocalImage(context: Context, imageId: String): Boolean {
        val file = File(File(context.filesDir, "images"), "$imageId.jpg")
        return if (file.exists()) file.delete() else false
    }

    // ===== BITMAP → JPEG BYTES =====
    fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    // ===== BYTES → BITMAP =====
    fun bytesToBitmap(bytes: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Logger.log(TAG, "Error converting bytes to bitmap", e)
            null
        }
    }

    // ============================================================
    // 🆕 v21.1.0 (Шаг 1.5): МЕДИА РЕЦЕПТОВ
    // ============================================================
    //
    // Локальное хранение (обязательно работает без интернета):
    //   filesDir/recipe_media/<recipeId>/<mediaId>.jpg
    //   filesDir/recipe_media/<recipeId>/<mediaId>.mp4
    //   filesDir/recipe_media/<recipeId>/<mediaId>_thumb.jpg  (превью видео)
    //
    // На Яндекс.Диске (Шаг 1.6):
    //   /BAZA/data/recipe_media/<recipeId>/<mediaId>.jpg
    //   /BAZA/data/recipe_media/<recipeId>/<mediaId>.mp4
    //
    // ВАЖНО:
    //  - папка recipe_media/<recipeId> создаётся лениво (ensure);
    //  - getRecipeMediaFile/ getRecipeThumbFile НЕ создают папку,
    //    только возвращают File (существует или нет — пусть решает вызывающий);
    //  - deleteRecipeMediaDir удаляет папку со всем содержимым (при hard-delete
    //    рецепта в cleanup — Шаг 1.6).
    // ============================================================

    /**
     * Папка для медиа конкретного рецепта: filesDir/recipe_media/<recipeId>/.
     * Если её нет — создаёт. Возвращает null только если создать не удалось.
     */
    fun ensureRecipeMediaDir(context: Context, recipeId: String): File? {
        return try {
            val root = File(context.filesDir, RECIPE_MEDIA_DIR)
            if (!root.exists()) root.mkdirs()
            val dir = File(root, recipeId)
            if (!dir.exists()) dir.mkdirs()
            if (dir.exists() && dir.isDirectory) dir else null
        } catch (e: Exception) {
            Logger.log(TAG, "ensureRecipeMediaDir error for $recipeId: ${e.message}", e)
            null
        }
    }

    /**
     * Папка медиа рецепта без создания: filesDir/recipe_media/<recipeId>/.
     * Может не существовать — это нормально, вызывающий сам проверяет file.exists().
     */
    fun getRecipeMediaDir(context: Context, recipeId: String): File {
        return File(File(context.filesDir, RECIPE_MEDIA_DIR), recipeId)
    }

    /**
     * Файл медиа рецепта: filesDir/recipe_media/<recipeId>/<fileName>.
     *
     * fileName — то, что лежит в RecipeMediaEntity.imageUrl
     * (например "abc-123.jpg" или "abc-123.mp4").
     *
     * Не создаёт папку, не проверяет существование — просто возвращает File.
     * Для UI обычно:
     *   val f = ImageUtils.getRecipeMediaFile(ctx, recipeId, media.imageUrl)
     *   if (f.exists()) Coil.load(f) else placeholder
     */
    fun getRecipeMediaFile(context: Context, recipeId: String, fileName: String?): File {
        val name = fileName ?: ""
        return File(File(File(context.filesDir, RECIPE_MEDIA_DIR), recipeId), name)
    }

    /**
     * Файл превью видео: filesDir/recipe_media/<recipeId>/<thumbName>.
     * thumbName — RecipeMediaEntity.thumbnailUrl (например "abc-123_thumb.jpg").
     */
    fun getRecipeThumbFile(context: Context, recipeId: String, thumbName: String?): File {
        val name = thumbName ?: ""
        return File(File(File(context.filesDir, RECIPE_MEDIA_DIR), recipeId), name)
    }

    /**
     * Удалить папку медиа конкретного рецепта целиком (рекурсивно).
     * Используется в cleanup при hard-delete рецепта (Шаг 1.6).
     */
    fun deleteRecipeMediaDir(context: Context, recipeId: String): Boolean {
        return try {
            val dir = getRecipeMediaDir(context, recipeId)
            if (!dir.exists()) return true
            val ok = dir.deleteRecursively()
            Logger.log(TAG, "deleteRecipeMediaDir: recipeId=$recipeId, ok=$ok")
            ok
        } catch (e: Exception) {
            Logger.log(TAG, "deleteRecipeMediaDir error for $recipeId: ${e.message}", e)
            false
        }
    }

    /**
     * Удалить один файл медиа рецепта (фото/видео/превью).
     * Возвращает true, если файла не было или удаление прошло успешно.
     */
    fun deleteRecipeMediaFile(context: Context, recipeId: String, fileName: String?): Boolean {
        return try {
            if (fileName.isNullOrBlank()) return true
            val f = getRecipeMediaFile(context, recipeId, fileName)
            if (!f.exists()) return true
            val ok = f.delete()
            Logger.log(TAG, "deleteRecipeMediaFile: $recipeId/$fileName, ok=$ok")
            ok
        } catch (e: Exception) {
            Logger.log(TAG, "deleteRecipeMediaFile error: ${e.message}", e)
            false
        }
    }
}
