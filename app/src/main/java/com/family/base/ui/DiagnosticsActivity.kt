package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.repository.CatalogRepository
import com.family.base.databinding.ActivityDiagnosticsBinding
import com.family.base.util.Logger
import com.family.base.util.RestoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val TAG = "DiagnosticsActivity"

    private lateinit var tokenStorage: TokenStorage
    private lateinit var repository: CatalogRepository
    private lateinit var restoreManager: RestoreManager

    private var isUploading = false
    private var isRestoring = false

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

        val db = AppDatabase.getInstance(this)
        tokenStorage = TokenStorage(this)
        repository = CatalogRepository(db)
        restoreManager = RestoreManager(db, repository)

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

        // ===== ОТПРАВКА ЛОГОВ НА ЯНДЕКС.ДИСК =====
        binding.btnSendLog.setOnClickListener {
            Logger.log(TAG, "Send log clicked")
            uploadLogsToDisk()
        }

        // ===== 🆕 ИСТОРИЯ ИЗМЕНЕНИЙ =====
        binding.btnHistory.setOnClickListener {
            Logger.log(TAG, "History clicked")
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // ===== 🆕 v17.2.7: ВОССТАНОВЛЕНИЕ БД =====
        binding.btnRestore.setOnClickListener {
            Logger.log(TAG, "Restore clicked")
            showRestoreConfirmDialog()
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
    // ОТПРАВКА ЛОГОВ НА ЯНДЕКС.ДИСК (папка /logs/)
    // ============================================================

    private fun uploadLogsToDisk() {
        if (isUploading) {
            Toast.makeText(this, "Отправка уже идёт…", Toast.LENGTH_SHORT).show()
            return
        }

        val token = tokenStorage.getAccessToken()
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "Требуется вход в аккаунт Яндекс", Toast.LENGTH_LONG).show()
            Logger.log(TAG, "uploadLogsToDisk: no token, aborting")
            return
        }

        val logsDir = Logger.getLogsDirectory()
        if (logsDir == null || !logsDir.exists()) {
            Toast.makeText(this, "Папка логов не найдена", Toast.LENGTH_SHORT).show()
            return
        }

        val logFiles = logsDir.listFiles { file ->
            file.isFile && file.name.endsWith(".txt")
        }?.sortedBy { it.lastModified() }

        if (logFiles.isNullOrEmpty()) {
            Toast.makeText(this, "Логи не найдены", Toast.LENGTH_SHORT).show()
            return
        }

        isUploading = true
        val dialog = createProgressDialog("Отправка логов на Яндекс.Диск…")
        dialog.show()

        lifecycleScope.launch {
            try {
                val merged = withContext(Dispatchers.IO) {
                    buildMergedLogContent(logFiles)
                }

                val user = sanitizeFileName(
                    tokenStorage.getCurrentUser()
                        ?: tokenStorage.getUserDisplayName()
                        ?: "User"
                )
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                    .format(Date())
                val fileName = "baza_log_${user}_${timestamp}.txt"

                Logger.log(TAG, "uploadLogsToDisk: uploading '$fileName' (${merged.length} chars from ${logFiles.size} files)")

                val success = repository.uploadLogToDisk(fileName, merged.toByteArray(Charsets.UTF_8))

                dialog.dismiss()
                isUploading = false

                if (success) {
                    Toast.makeText(
                        this@DiagnosticsActivity,
                        "✅ Логи отправлены в /logs/$fileName",
                        Toast.LENGTH_LONG
                    ).show()
                    Logger.log(TAG, "uploadLogsToDisk: success ($fileName)")
                } else {
                    Toast.makeText(
                        this@DiagnosticsActivity,
                        "❌ Не удалось отправить логи. Проверьте сеть.",
                        Toast.LENGTH_LONG
                    ).show()
                    Logger.log(TAG, "uploadLogsToDisk: failed")
                }
            } catch (e: Exception) {
                dialog.dismiss()
                isUploading = false
                Logger.log(TAG, "uploadLogsToDisk error: ${e.message}", e)
                Toast.makeText(
                    this@DiagnosticsActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildMergedLogContent(files: List<File>): String {
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        sb.append("BAZA — merged log dump\n")
        sb.append("Generated: ${df.format(Date())}\n")
        sb.append("User: ${tokenStorage.getCurrentUser() ?: "—"}\n")
        sb.append("Files: ${files.size}\n")
        sb.append("============================================================\n\n")

        for (file in files) {
            sb.append("============================================================\n")
            sb.append("FILE: ${file.name} (${file.length()} bytes, modified ${df.format(Date(file.lastModified()))})\n")
            sb.append("============================================================\n")
            try {
                val content = file.readText(Charsets.UTF_8)
                sb.append(content)
                if (!content.endsWith("\n")) sb.append("\n")
            } catch (e: Exception) {
                sb.append("[ERROR reading file: ${e.message}]\n")
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
    }

    private fun createProgressDialog(message: String): AlertDialog {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(48, 48, 48, 48)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val text = TextView(this).apply {
            this.text = message
            setPadding(32, 0, 0, 0)
            textSize = 16f
        }
        container.addView(progress)
        container.addView(text)

        return AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(false)
            .create()
    }

    // ============================================================
    // 🆕 v17.2.7: ВОССТАНОВЛЕНИЕ БД ИЗ RESTORE-ФАЙЛОВ
    // ============================================================

    private fun showRestoreConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Восстановление БД")
            .setMessage(
                "Будут скачаны 4 restore-файла с Яндекс.Диска:\n" +
                    "• restore_items.json\n" +
                    "• restore_folders.json\n" +
                    "• restore_history.json\n" +
                    "• restore_tasks.json\n\n" +
                    "Затем они будут залиты в БД.\n\n" +
                    "Существующие объекты с теми же id будут ОБНОВЛЕНЫ. " +
                    "Новые объекты будут ДОБАВЛЕНЫ. Ничего не удаляется.\n\n" +
                    "Рекомендуется делать это в авиарежиме, чтобы случайно не запустить синк."
            )
            .setPositiveButton("Восстановить") { _, _ ->
                doRestore()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doRestore() {
        if (isRestoring) {
            Toast.makeText(this, "Восстановление уже идёт…", Toast.LENGTH_SHORT).show()
            return
        }

        val token = tokenStorage.getAccessToken()
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "Требуется вход в аккаунт Яндекс", Toast.LENGTH_LONG).show()
            Logger.log(TAG, "doRestore: no token, aborting")
            return
        }

        isRestoring = true

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val stageText = TextView(this).apply {
            text = "Подготовка…"
            setPadding(0, 24, 0, 0)
            textSize = 15f
        }
        val messageText = TextView(this).apply {
            text = ""
            setPadding(0, 8, 0, 0)
            textSize = 13f
        }
        container.addView(progressBar)
        container.addView(stageText)
        container.addView(messageText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Восстановление БД")
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            try {
                Logger.log(TAG, "doRestore: starting…")

                val result = restoreManager.restoreFromDisk { stage, message ->
                    runOnUiThread {
                        stageText.text = stage
                        messageText.text = message
                    }
                }

                dialog.dismiss()
                isRestoring = false

                showRestoreResultDialog(result)

            } catch (e: Exception) {
                dialog.dismiss()
                isRestoring = false
                Logger.log(TAG, "doRestore error: ${e.message}", e)
                AlertDialog.Builder(this@DiagnosticsActivity)
                    .setTitle("❌ Ошибка восстановления")
                    .setMessage("${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showRestoreResultDialog(result: RestoreManager.RestoreResult) {
        val message = result.summary()
        val title = if (result.hasErrors) "⚠️ Восстановление (с ошибками)" else "✅ Восстановление завершено"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                if (!result.hasErrors && !result.isEmpty) {
                    Toast.makeText(
                        this,
                        "Перезапустите приложение, чтобы увидеть изменения",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
