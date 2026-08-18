package com.family.base.util

import android.content.Context
import android.graphics.*
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {

    private const val TAG = "ImageUtils"
    private const val TARGET_SIZE = 1024
    private const val JPEG_QUALITY = 85

    // ============================================================
    // ОСНОВНЫЕ МЕТОДЫ ОБРАБОТКИ
    // ============================================================

    fun processImage(bitmap: Bitmap): ByteArray {
        Logger.log(TAG, "Processing image: ${bitmap.width}x${bitmap.height}")
        val cropped = cropToSquare(bitmap)
        val resized = resize(cropped, TARGET_SIZE)
        val bytes = compressToJpeg(resized, JPEG_QUALITY)
        if (cropped != bitmap) cropped.recycle()
        if (resized != cropped) resized.recycle()
        return bytes
    }

    fun cropToSquare(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width == height) return bitmap
        val size = minOf(width, height)
        val x = (width - size) / 2
        val y = (height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    fun resize(bitmap: Bitmap, targetSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width == targetSize && height == targetSize) return bitmap
        val scale = targetSize.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 80): ByteArray {
        return compressToJpeg(bitmap, quality)
    }

    fun fitIntoSquere(source: Bitmap, targetSize: Int): Bitmap {
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val srcWidth = source.width
        val srcHeight = source.height
        val scale = minOf(targetSize.toFloat() / srcWidth, targetSize.toFloat() / srcHeight)
        val newWidth = (srcWidth * scale).toInt()
        val newHeight = (srcHeight * scale).toInt()
        val left = (targetSize - newWidth) / 2
        val top = (targetSize - newHeight) / 2
        val scaledBitmap = Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
        canvas.drawBitmap(scaledBitmap, left.toFloat(), top.toFloat(), null)
        scaledBitmap.recycle()
        return result
    }

    // ============================================================
    // ДЕКОДИРОВАНИЕ И ПОВОРОТ
    // ============================================================

    fun decodeByteArray(bytes: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Logger.log(TAG, "Error decoding byte array: ${e.message}")
            null
        }
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ============================================================
    // РАБОТА С ЛОКАЛЬНЫМИ ИЗОБРАЖЕНИЯМИ
    // ============================================================

    fun saveImageLocally(context: Context, itemId: String, imageBytes: ByteArray): Boolean {
        return try {
            val dir = File(context.filesDir, "images")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "$itemId.jpg")
            file.writeBytes(imageBytes)
            Logger.log(TAG, "Image saved locally: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Logger.log(TAG, "Failed to save image locally: ${e.message}")
            false
        }
    }

    fun getLocalImageFile(context: Context, itemId: String): File? {
        val dir = File(context.filesDir, "images")
        val file = File(dir, "$itemId.jpg")
        return if (file.exists()) file else null
    }

    fun deleteLocalImage(context: Context, itemId: String): Boolean {
        val file = getLocalImageFile(context, itemId) ?: return false
        return file.delete()
    }
}
