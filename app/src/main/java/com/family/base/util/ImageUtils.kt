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
}
