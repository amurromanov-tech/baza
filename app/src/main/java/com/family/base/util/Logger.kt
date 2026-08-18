package com.family.base.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val LOG_FILE_TODAY = "baza_log.txt"
    private const val LOG_FILE_ARCHIVE_PREFIX = "baza_log_"
    private const val LOG_FILE_EXTENSION = ".txt"
    private const val MAX_LOG_SIZE_MB = 5L
    private var isEnabled: Boolean = true
    private var logDir: File? = null
    private var currentLogFile: File? = null
    private var fileWriter: FileWriter? = null
    
    // Формат времени: только часы и минуты [HH:mm]
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormatFile = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    
    private val TAG = "Logger"

    fun init(context: Context) {
        try {
            logDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "BAZA_LOGS"
            )
            if (!logDir!!.exists()) {
                logDir!!.mkdirs()
            }
            
            // Загружаем настройку логирования
            val prefs = context.getSharedPreferences("baza_settings", Context.MODE_PRIVATE)
            isEnabled = prefs.getBoolean("logging_enabled", true)
            
            // Проверяем ротацию
            checkRotation()
            
            Log.d(TAG, "Logger initialized, enabled: $isEnabled, dir: ${logDir?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize logger", e)
        }
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Log.d(TAG, "Logging ${if (enabled) "enabled" else "disabled"}")
    }

    fun isEnabled(): Boolean = isEnabled

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        
        try {
            checkRotation()
            
            // Формат: [HH:mm] [Tag] message
            val timestamp = timeFormat.format(Date())
            val logMessage = buildString {
                append("[$timestamp] [$tag] ")
                append(message)
                if (throwable != null) {
                    append("\n")
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    append(sw.toString())
                }
                append("\n")
            }
            
            // Пишем в файл с принудительным сбросом
            fileWriter?.let { writer ->
                writer.append(logMessage)
                writer.flush()
            } ?: run {
                // Если fileWriter не инициализирован, используем appendText с flush
                currentLogFile?.appendText(logMessage, Charsets.UTF_8)
                // Для appendText нужно принудительно сбросить
                try {
                    currentLogFile?.let { file ->
                        val fos = java.io.FileOutputStream(file, true)
                        fos.write(logMessage.toByteArray(Charsets.UTF_8))
                        fos.flush()
                        fos.close()
                    }
                } catch (e: Exception) {
                    // fallback
                    currentLogFile?.appendText(logMessage, Charsets.UTF_8)
                }
            }
            
            // Пишем в Logcat
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.d(tag, message)
            }
            
            // Проверяем размер файла
            checkFileSize()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    fun log(tag: String, message: String) {
        log(tag, message, null)
    }

    private fun checkRotation() {
        if (logDir == null) return
        
        val todayFile = File(logDir, LOG_FILE_TODAY)
        currentLogFile = todayFile
        
        // Закрываем старый writer
        try {
            fileWriter?.close()
        } catch (e: Exception) {
            // ignore
        }
        fileWriter = null
        
        if (!todayFile.exists()) {
            todayFile.createNewFile()
            fileWriter = FileWriter(todayFile, true)
            return
        }
        
        // Проверяем дату создания файла
        val lastModified = todayFile.lastModified()
        val fileDate = Date(lastModified)
        val today = Date()
        
        val fileDateStr = dateFormatFile.format(fileDate)
        val todayDateStr = dateFormatFile.format(today)
        
        if (fileDateStr != todayDateStr) {
            // Файл не сегодняшний → архивируем
            val archiveName = "$LOG_FILE_ARCHIVE_PREFIX$fileDateStr$LOG_FILE_EXTENSION"
            val archiveFile = File(logDir, archiveName)
            
            // Переименовываем
            todayFile.renameTo(archiveFile)
            
            // Удаляем старый архив (если есть)
            val oldArchives = logDir?.listFiles { file ->
                file.name.startsWith(LOG_FILE_ARCHIVE_PREFIX) && 
                file.name != archiveName
            }
            oldArchives?.forEach { it.delete() }
            
            // Создаем новый файл
            todayFile.createNewFile()
            currentLogFile = todayFile
        }
        
        // Открываем writer для текущего файла
        fileWriter = FileWriter(todayFile, true)
    }

    private fun checkFileSize() {
        currentLogFile?.let { file ->
            if (file.length() > MAX_LOG_SIZE_MB * 1024 * 1024) {
                try {
                    fileWriter?.close()
                } catch (e: Exception) {
                    // ignore
                }
                fileWriter = null
                
                val dateStr = dateFormatFile.format(Date())
                val archiveName = "$LOG_FILE_ARCHIVE_PREFIX$dateStr$LOG_FILE_EXTENSION"
                val archiveFile = File(logDir, archiveName)
                
                file.renameTo(archiveFile)
                file.createNewFile()
                currentLogFile = file
                fileWriter = FileWriter(file, true)
            }
        }
    }

    fun clearLogs() {
        try {
            fileWriter?.close()
            fileWriter = null
            logDir?.listFiles()?.forEach { it.delete() }
            currentLogFile = null
            Log.d(TAG, "All logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }

    fun getTodayLog(): File? = currentLogFile

    fun getArchiveLog(): File? {
        logDir?.let { dir ->
            val archives = dir.listFiles { file ->
                file.name.startsWith(LOG_FILE_ARCHIVE_PREFIX)
            }
            return archives?.maxByOrNull { it.lastModified() }
        }
        return null
    }

    fun getLogsDirectory(): File? = logDir
}
