package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.family.base.databinding.ActivityDiagnosticsBinding
import com.family.base.util.Logger

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val TAG = "DiagnosticsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== DiagnosticsActivity onCreate START ===")

        try {
            binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        binding.btnBack.setOnClickListener { finish() }

        // ===== ЗАГРУЖАЕМ НАСТРОЙКУ ЛОГИРОВАНИЯ =====
        loadLoggingSetting()

        // ===== ПЕРЕКЛЮЧАТЕЛЬ ЛОГИРОВАНИЯ =====
        binding.switchLogging.setOnCheckedChangeListener { _, isChecked ->
            Logger.log(TAG, "Logging enabled: $isChecked")
            Logger.setEnabled(isChecked)
            saveLoggingSetting(isChecked)
        }

        // ===== ОЧИСТКА ЛОГОВ =====
        binding.btnClearLogs.setOnClickListener {
            Logger.log(TAG, "Clear logs clicked")
            showClearLogsDialog()
        }

        // ===== ОТПРАВКА ЛОГОВ =====
        binding.btnSendLog.setOnClickListener {
            Logger.log(TAG, "Send log clicked")
            sendLogs()
        }

        Logger.log(TAG, "=== DiagnosticsActivity onCreate FINISHED ===")
    }

    // ============================================================
    // НАСТРОЙКИ ЛОГИРОВАНИЯ
    // ============================================================

    private fun loadLoggingSetting() {
        try {
            val prefs = getSharedPreferences("baza_settings", MODE_PRIVATE)
            val loggingEnabled = prefs.getBoolean("logging_enabled", true)
            binding.switchLogging.isChecked = loggingEnabled
            Logger.setEnabled(loggingEnabled)
            Logger.log(TAG, "Logging setting loaded: $loggingEnabled")
        } catch (e: Exception) {
            Logger.log(TAG, "Error loading logging setting", e)
        }
    }

    private fun saveLoggingSetting(enabled: Boolean) {
        try {
            val prefs = getSharedPreferences("baza_settings", MODE_PRIVATE)
            prefs.edit().putBoolean("logging_enabled", enabled).apply()
            Logger.log(TAG, "Logging setting saved: $enabled")
        } catch (e: Exception) {
            Logger.log(TAG, "Error saving logging setting", e)
        }
    }

    // ============================================================
    // ОЧИСТКА ЛОГОВ
    // ============================================================

    private fun showClearLogsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Очистить логи")
            .setMessage("Удалить все файлы логов?")
            .setPositiveButton("Да") { _, _ ->
                Logger.clearLogs()
                Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
                Logger.log(TAG, "Logs cleared")
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    // ============================================================
    // ОТПРАВКА ЛОГОВ
    // ============================================================

    private fun sendLogs() {
        Logger.log(TAG, "Sending logs...")
        try {
            val logsDir = Logger.getLogsDirectory()
            if (logsDir == null || !logsDir.exists()) {
                Toast.makeText(this, "Логи не найдены", Toast.LENGTH_SHORT).show()
                return
            }

            val logFiles = logsDir.listFiles()
            if (logFiles.isNullOrEmpty()) {
                Toast.makeText(this, "Логи не найдены", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("support@familybase.com"))
                putExtra(Intent.EXTRA_SUBJECT, "Логи приложения БАЗА")
                val uris = logFiles.map { file ->
                    android.net.Uri.fromFile(file)
                }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }

            startActivity(Intent.createChooser(intent, "Отправить логи"))
            Logger.log(TAG, "Logs sent successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "Error sending logs", e)
            Toast.makeText(this, "Ошибка отправки логов", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
