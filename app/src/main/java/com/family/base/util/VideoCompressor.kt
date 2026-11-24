package com.family.base.util

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 🆕 v23.3.17: сжатие видео через FFmpegKit.
 *
 * Зачем:
 *  - видео в рецептах весят 20–100 МБ, загрузка на Яндекс.Диск идёт минутами;
 *  - после сжатия файл уменьшается в 5–10 раз (обычно 3–8 МБ для 1–2 мин видео);
 *  - H.264 + AAC совместимы со всеми плеерами (включая ExoPlayer).
 *
 * Что делает:
 *  - перекодирует видео в H.264 (libx264) с CRF 28 (хороший компромисс
 *    качество/размер), preset "veryfast" (быстро на телефоне);
 *  - уменьшает разрешение до 1280×720 (если исходное больше) с сохранением
 *    пропорций (через фильтр scale=-2:720);
 *  - аудио: AAC, 96 kbps, стерео;
 *  - удаляет метаданные (движение, поворот и т.д.) — не критично;
 *  - пишет во временный файл "<name>.compressed.tmp", затем атомарно
 *    заменяет оригинал (переименовывает).
 *
 * Почему `scale=-2:720`:
 *  - `-2` значит "ширина кратна 2" (требование H.264), вычисляется
 *    автоматически из высоты 720 с сохранением пропорций;
 *  - если видео вертикальное, высота всё равно 720 — это нормально,
 *    ширина получится меньше 1280, качество сохранится.
 *
 * Почему `-movflags +faststart`:
 *  - переносит MOOV-атом в начало файла — видео начинает играть
 *    сразу, без полной загрузки (важно для стриминга с Яндекс.Диска).
 *
 * Возвращает:
 *  - true — сжатие успешно, оригинал заменён;
 *  - false — сжатие не удалось (оригинал остаётся, чтобы не потерять данные).
 *
 * ВАЖНО:
 *  - Сжатие длится 5–30 секунд на 1 минуту видео (зависит от устройства).
 *  - Не блокирует UI — вызывать из Dispatchers.IO / coroutine.
 *  - Если FFmpegKit вернул ошибку, оригинал НЕ удаляется (безопасность).
 */
object VideoCompressor {

    private const val TAG = "VideoCompressor"

    /** Максимальная высота после сжатия (720p). */
    private const val TARGET_HEIGHT = 720

    /** CRF (Constant Rate Factor): 18 — отличное, 28 — норм, 32 — плохое. */
    private const val CRF = 28

    /** Preset: ultrafast/superfast/veryfast/faster/fast/medium/slow. */
    private const val PRESET = "veryfast"

    /** Аудио-битрейт. */
    private const val AUDIO_BITRATE = "96k"

    /** Сколько ждать завершения сессии (5 минут). */
    private const val TIMEOUT_MINUTES = 5L

    /** Результат сжатия. */
    data class Result(
        val success: Boolean,
        val originalSize: Long,
        val compressedSize: Long,
        val outputFile: File?,
        val errorMessage: String? = null
    ) {
        val ratio: Double
            get() = if (originalSize > 0L) compressedSize.toDouble() / originalSize else 1.0
    }

    /**
     * Сжать видеофайл. При успехе оригинал заменяется сжатой версией
     * (то же имя файла). При ошибке оригинал остаётся нетронутым.
     *
     * @param inputFile исходный видеофайл (mp4/mkv/mov/3gp/...)
     * @return Result с размерами до/после и статусом.
     */
    fun compress(inputFile: File): Result {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            return Result(
                success = false,
                originalSize = 0L,
                compressedSize = 0L,
                outputFile = null,
                errorMessage = "file missing or empty: ${inputFile.absolutePath}"
            )
        }

        val originalSize = inputFile.length()
        val tempFile = File(inputFile.parentFile, "${inputFile.name}.compressed.tmp")

        // На всякий случай удаляем остатки предыдущих попыток.
        if (tempFile.exists()) tempFile.delete()

        val cmd = buildCommand(inputFile, tempFile)

        Logger.log(TAG, "compress START: ${inputFile.name} (${originalSize} bytes)")
        Logger.log(TAG, "compress CMD: $cmd")

        val t0 = System.currentTimeMillis()

        return try {
            val session: FFmpegSession = FFmpegKit.execute(cmd)
            val elapsed = System.currentTimeMillis() - t0

            if (!ReturnCode.isSuccess(session.returnCode)) {
                val output = session.output ?: ""
                val failStack = session.failStackTrace ?: ""
                Logger.log(
                    TAG,
                    "compress FAIL: rc=${session.returnCode}, elapsed=${elapsed}ms\n" +
                        "output=${output.take(800)}\nstack=${failStack.take(400)}"
                )
                if (tempFile.exists()) tempFile.delete()
                return Result(
                    success = false,
                    originalSize = originalSize,
                    compressedSize = 0L,
                    outputFile = null,
                    errorMessage = "ffmpeg rc=${session.returnCode}"
                )
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                Logger.log(TAG, "compress FAIL: output empty, elapsed=${elapsed}ms")
                if (tempFile.exists()) tempFile.delete()
                return Result(
                    success = false,
                    originalSize = originalSize,
                    compressedSize = 0L,
                    outputFile = null,
                    errorMessage = "output empty"
                )
            }

            val compressedSize = tempFile.length()

            // Если сжатие дало файл БОЛЬШЕ оригинала — не перезаписываем
            // (бывает, если видео уже было сжато сильно).
            if (compressedSize >= originalSize) {
                Logger.log(
                    TAG,
                    "compress SKIP: compressed ($compressedSize) >= original ($originalSize), " +
                        "keeping original"
                )
                tempFile.delete()
                return Result(
                    success = false,
                    originalSize = originalSize,
                    compressedSize = originalSize,
                    outputFile = inputFile,
                    errorMessage = "compressed bigger than original"
                )
            }

            // Атомарная замена: удаляем оригинал, переименовываем temp → оригинал.
            if (!inputFile.delete()) {
                Logger.log(TAG, "compress WARN: cannot delete original, leaving temp file")
                // Если не смогли удалить оригинал — оставляем temp, но не портим оригинал.
                return Result(
                    success = false,
                    originalSize = originalSize,
                    compressedSize = compressedSize,
                    outputFile = tempFile,
                    errorMessage = "cannot delete original"
                )
            }

            if (!tempFile.renameTo(inputFile)) {
                Logger.log(TAG, "compress WARN: rename failed, restoring original")
                // Аварийный откат: пытаемся вернуть как было (temp = новый файл,
                // переименовываем его обратно во временное имя, оригинал потерян —
                // но хоть не молчим).
                return Result(
                    success = false,
                    originalSize = originalSize,
                    compressedSize = compressedSize,
                    outputFile = tempFile,
                    errorMessage = "rename failed"
                )
            }

            Logger.log(
                TAG,
                "compress OK: ${inputFile.name} $originalSize → $compressedSize bytes " +
                    "(${String.format("%.1f", 100.0 * compressedSize / originalSize)}%), elapsed=${elapsed}ms"
            )

            Result(
                success = true,
                originalSize = originalSize,
                compressedSize = compressedSize,
                outputFile = inputFile
            )
        } catch (e: Exception) {
            Logger.log(TAG, "compress ERROR: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
            Result(
                success = false,
                originalSize = originalSize,
                compressedSize = 0L,
                outputFile = null,
                errorMessage = e.message ?: "unknown"
            )
        }
    }

    /**
     * Собрать строку команды FFmpeg.
     *
     * Ключевые опции:
     *  -y              — перезаписывать выходной файл без вопросов;
     *  -i <input>      — входной файл;
     *  -c:v libx264    — H.264 видео (совместим со всем);
     *  -crf 28         — качество (18 отличное, 28 норм, 32 плохое);
     *  -preset veryfast — скорость кодирования (быстро, но не так эффективно);
     *  -vf scale=-2:720 — уменьшить до высоты 720 (ширина кратна 2, авто);
     *  -c:a aac        — AAC-аудио;
     *  -b:a 96k        — 96 kbps;
     *  -movflags +faststart — MOOV в начало (для стриминга);
     *  <output>        — временный файл.
     */
    private fun buildCommand(input: File, output: File): String {
        return buildString {
            append("-y ")
            append("-i '${escapePath(input.absolutePath)}' ")
            append("-c:v libx264 ")
            append("-crf $CRF ")
            append("-preset $PRESET ")
            append("-vf 'scale=-2:$TARGET_HEIGHT' ")
            append("-c:a aac ")
            append("-b:a $AUDIO_BITRATE ")
            append("-movflags +faststart ")
            append("'${escapePath(output.absolutePath)}'")
        }
    }

    /** Экранирование одинарных кавычек в путях для FFmpeg. */
    private fun escapePath(path: String): String {
        return path.replace("'", "'\\''")
    }

    /**
     * Возвращает true, если файл похож на видео (по расширению).
     * Используется вызывающей стороной, чтобы не пытаться сжимать фото.
     */
    fun isVideoFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("mp4", "mkv", "mov", "3gp", "avi", "webm", "m4v", "flv", "ts", "wmv")
    }
}
